// POST /api/webhook/polar   Polar "order.paid" -> issue a key + email it; "order.refunded" -> revoke it.
// Env: POLAR_WEBHOOK_SECRET (required), POLAR_PRODUCT_ID (optional: ignore orders for any other product).
import { verifyPolar } from '../_lib/verify.mjs';
import { issueLicense, revokeOrder, wasEmailed, markEmailed } from '../_lib/license.mjs';
import { sendKeyEmail } from '../_lib/email.mjs';

const ok = (msg = 'ok') => new Response(msg, { status: 200 });

export async function POST(request) {
  const raw = await request.text(); // raw body, untouched: the signature covers the exact bytes
  if (!verifyPolar(raw, request.headers, process.env.POLAR_WEBHOOK_SECRET)) return new Response('bad signature', { status: 403 });

  let event;
  try { event = JSON.parse(raw); } catch { return new Response('bad json', { status: 400 }); }
  const d = event && event.data;
  if (!d || !d.id) return ok('ignored');
  const want = process.env.POLAR_PRODUCT_ID;
  if (want && d.product_id !== want) return ok('other product');

  try {
    if (event.type === 'order.paid' && d.billing_reason === 'purchase') {
      const email = d.customer && d.customer.email;
      const { key } = await issueLicense({ provider: 'polar', orderId: d.id, checkoutId: d.checkout_id, email });
      if (!(await wasEmailed(key)) && (await sendKeyEmail(email, key))) await markEmailed(key);
      return ok();
    }
    // order.refunded also fires for partial refunds; only a full refund takes the licence away.
    if (event.type === 'order.refunded' && (d.status === 'refunded' || (d.total_amount > 0 && d.refunded_amount >= d.total_amount))) {
      await revokeOrder({ provider: 'polar', orderId: d.id });
      return ok();
    }
    return ok('ignored');
  } catch (e) {
    console.error('polar webhook failed', event.type, d.id, e && e.message);
    return new Response('error', { status: 500 }); // non-2xx makes Polar retry; the handler is safe to repeat
  }
}
