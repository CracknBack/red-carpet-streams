// POST /api/webhook/creem   Creem "checkout.completed" -> issue a key + email it; "refund.created" -> revoke it.
// Env: CREEM_WEBHOOK_SECRET (required), CREEM_PRODUCT_ID (optional: ignore orders for any other product).
import { verifyCreem } from '../_lib/verify.mjs';
import { issueLicense, revokeOrder, wasEmailed, markEmailed } from '../_lib/license.mjs';
import { sendKeyEmail } from '../_lib/email.mjs';

const ok = (msg = 'ok') => new Response(msg, { status: 200 });

export async function POST(request) {
  const raw = await request.text();
  if (!verifyCreem(raw, request.headers, process.env.CREEM_WEBHOOK_SECRET)) return new Response('bad signature', { status: 403 });

  let event;
  try { event = JSON.parse(raw); } catch { return new Response('bad json', { status: 400 }); }
  const type = event && event.eventType, o = event && event.object;
  if (!o) return ok('ignored');
  const want = process.env.CREEM_PRODUCT_ID;

  try {
    if (type === 'checkout.completed') {
      const orderId = o.order && o.order.id;
      if (!orderId) return ok('ignored');
      if (want && (!o.product || o.product.id !== want)) return ok('other product');
      const email = o.customer && o.customer.email;
      const { key } = await issueLicense({ provider: 'creem', orderId, checkoutId: o.id, email });
      if (!(await wasEmailed(key)) && (await sendKeyEmail(email, key))) await markEmailed(key);
      return ok();
    }
    if (type === 'refund.created') {
      const orderId = o.order && o.order.id;
      if (orderId) await revokeOrder({ provider: 'creem', orderId });
      return ok();
    }
    return ok('ignored');
  } catch (e) {
    console.error('creem webhook failed', type, e && e.message);
    return new Response('error', { status: 500 });
  }
}
