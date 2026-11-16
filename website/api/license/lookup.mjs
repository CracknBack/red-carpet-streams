// GET /api/license/lookup?provider=polar|creem&checkout_id=...
// Used by the thank-you page right after purchase. The checkout id is an unguessable value only the buyer's
// browser was redirected with, so it acts as the password. "pending" means the payment webhook hasn't landed yet.
import { json, preflight } from '../_lib/http.mjs';
import { findKeyByCheckout } from '../_lib/license.mjs';

export function OPTIONS() { return preflight(); }

export async function GET(request) {
  try {
    const q = new URL(request.url).searchParams;
    const provider = q.get('provider'), id = q.get('checkout_id');
    if (!['polar', 'creem'].includes(provider) || !/^[A-Za-z0-9_-]{8,100}$/.test(id || '')) return json({ status: 'invalid' }, 400);
    const key = await findKeyByCheckout(provider, id);
    return json(key ? { status: 'ready', key } : { status: 'pending' });
  } catch (e) {
    console.error('lookup failed', e && e.message);
    return json({ status: 'error' }, 500);
  }
}
