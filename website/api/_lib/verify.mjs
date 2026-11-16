// Webhook signature checks for Polar and Creem. Both need the RAW request body, exactly as sent.
import { createHmac, timingSafeEqual } from 'node:crypto';

function safeEq(a, b) {
  const x = Buffer.from(a), y = Buffer.from(b);
  return x.length === y.length && timingSafeEqual(x, y);
}

// Creem: hex HMAC-SHA256 of the raw body, key = webhook secret, in the `creem-signature` header.
export function verifyCreem(rawBody, headers, secret) {
  const sig = headers.get('creem-signature');
  if (!sig || !secret) return false;
  const expected = createHmac('sha256', secret).update(rawBody).digest('hex');
  return safeEq(sig.trim().toLowerCase(), expected);
}

// Polar: Standard Webhooks. Signed text is `${webhook-id}.${webhook-timestamp}.${body}`, HMAC-SHA256, base64,
// sent as one or more space-separated "v1,<sig>" values. Polar's docs say secrets created on/after 2026-09-08 use
// the standard key (base64 of what follows "whsec_") while older ones use the UTF-8 bytes of the whole secret
// string, so try both keys.
export function verifyPolar(rawBody, headers, secret, nowMs = Date.now()) {
  const id = headers.get('webhook-id'), ts = headers.get('webhook-timestamp'), sigHeader = headers.get('webhook-signature');
  if (!id || !ts || !sigHeader || !secret) return false;
  const t = Number(ts);
  if (!Number.isFinite(t) || Math.abs(nowMs / 1000 - t) > 300) return false; // replay protection: 5 min window
  const keys = [Buffer.from(secret, 'utf8')];
  if (secret.startsWith('whsec_')) {
    const b = Buffer.from(secret.slice(6), 'base64');
    if (b.length) keys.unshift(b);
  }
  const signed = `${id}.${ts}.${rawBody}`;
  const given = sigHeader.split(' ').map(s => s.trim()).filter(s => s.startsWith('v1,')).map(s => s.slice(3));
  for (const k of keys) {
    const expected = createHmac('sha256', k).update(signed).digest('base64');
    if (given.some(g => safeEq(g, expected))) return true;
  }
  return false;
}
