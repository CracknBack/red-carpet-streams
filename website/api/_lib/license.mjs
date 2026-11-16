// Red Carpet Pro licence logic: issue keys, activate/deactivate devices, revoke on refund.
// All state lives in Redis (see kv.mjs):
//   lic:<KEY>           hash   email, provider, orderId, checkoutId, status (active|revoked), createdAt, emailedAt
//   lic:<KEY>:inst      hash   device name -> instance id   (size is capped at MAX_DEVICES)
//   ord:<provider>:<id> string KEY   (claims an order so webhook retries never issue a second key)
//   ck:<provider>:<id>  string KEY   (lets the thank-you page find the key from the checkout id)
//   ref:<provider>:<id> string 1     (a refund seen before its order, so a late order is issued revoked)
import { randomBytes, randomUUID } from 'node:crypto';
import { cmd, hgetall } from './kv.mjs';

export const MAX_DEVICES = 5;
export const SUPPORT_EMAIL = 'redcarpetplayer@pm.me';

// 31 symbols: no 0/O/1/I/L, so a key read off a screen and typed with a TV remote isn't ambiguous.
const ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';

// 3 groups of 5 = 15 symbols x ~4.95 bits = ~74 bits of randomness: not guessable.
export function generateKey() {
  let s = '';
  while (s.length < 15) {
    for (const b of randomBytes(32)) {
      if (b >= 248) continue; // 248 = 31 * 8: discard the leftover range so every symbol is equally likely
      s += ALPHABET[b % 31];
      if (s.length === 15) break;
    }
  }
  return `${s.slice(0, 5)}-${s.slice(5, 10)}-${s.slice(10, 15)}`;
}

// Forgiving input: any case, with or without dashes/spaces.
export function normalizeKey(raw) {
  const s = String(raw || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
  return s.length === 15 ? `${s.slice(0, 5)}-${s.slice(5, 10)}-${s.slice(10, 15)}` : '';
}

export async function issueLicense({ provider, orderId, checkoutId, email }) {
  const orderKey = `ord:${provider}:${orderId}`;
  let key = generateKey();
  const claimed = await cmd('SET', orderKey, key, 'NX');
  if (!claimed) key = await cmd('GET', orderKey); // a retry: reuse the key already issued for this order

  const lic = `lic:${key}`;
  if ((await cmd('HGET', lic, 'status')) == null) {
    const refunded = await cmd('GET', `ref:${provider}:${orderId}`);
    await cmd('HSET', lic,
      'email', email || '', 'provider', provider, 'orderId', orderId, 'checkoutId', checkoutId || '',
      'createdAt', new Date().toISOString(), 'status', refunded ? 'revoked' : 'active');
  }
  if (checkoutId) await cmd('SET', `ck:${provider}:${checkoutId}`, key);
  return { key, firstTime: !!claimed };
}

export async function markEmailed(key) {
  await cmd('HSET', `lic:${key}`, 'emailedAt', new Date().toISOString());
}
export async function wasEmailed(key) {
  return !!(await cmd('HGET', `lic:${key}`, 'emailedAt'));
}

export async function revokeOrder({ provider, orderId }) {
  await cmd('SET', `ref:${provider}:${orderId}`, '1');
  const key = await cmd('GET', `ord:${provider}:${orderId}`);
  if (key) await cmd('HSET', `lic:${key}`, 'status', 'revoked', 'revokedAt', new Date().toISOString());
  return key || null;
}

export async function findKeyByCheckout(provider, checkoutId) {
  return (await cmd('GET', `ck:${provider}:${checkoutId}`)) || null;
}

const BAD_KEY = 'That key wasn’t accepted.';

export async function activate(rawKey, rawName) {
  const key = normalizeKey(rawKey);
  if (!key) return { ok: false, error: BAD_KEY };
  const rec = await hgetall(`lic:${key}`);
  if (!rec || !rec.status) return { ok: false, error: BAD_KEY };
  if (rec.status !== 'active') return { ok: false, error: `This license was refunded or revoked. Email ${SUPPORT_EMAIL} if you think that’s a mistake.` };

  const name = String(rawName || 'device').slice(0, 100);
  const inst = `lic:${key}:inst`;
  const fresh = randomUUID();
  const added = await cmd('HSETNX', inst, name, fresh);
  if (Number(added) === 1) {
    // Add first, then count: if two devices race for the last slot, the loser backs out, so the cap is never exceeded.
    if (Number(await cmd('HLEN', inst)) > MAX_DEVICES) {
      await cmd('HDEL', inst, name);
      return { ok: false, error: `This key is already active on ${MAX_DEVICES} devices. Deactivate one first, or email ${SUPPORT_EMAIL}.` };
    }
    return { ok: true, instanceId: fresh };
  }
  // Same device name activating again: hand back its existing slot instead of using another.
  return { ok: true, instanceId: await cmd('HGET', inst, name) };
}

export async function deactivate(rawKey, instanceId) {
  const key = normalizeKey(rawKey);
  if (!key || !instanceId) return false;
  const inst = `lic:${key}:inst`;
  const all = await hgetall(inst);
  if (all) for (const [name, id] of Object.entries(all)) if (id === instanceId) await cmd('HDEL', inst, name);
  return true; // already-gone counts as deactivated
}
