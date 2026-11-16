// Tiny Redis client over the Upstash REST API (no npm dependency).
// Vercel's Upstash integration sets UPSTASH_REDIS_REST_URL/TOKEN (or the older KV_REST_API_URL/TOKEN).
// Tests can inject an in-memory implementation via globalThis.__RC_KV__.

export async function cmd(...args) {
  if (globalThis.__RC_KV__) return globalThis.__RC_KV__(args);
  const url = process.env.UPSTASH_REDIS_REST_URL || process.env.KV_REST_API_URL;
  const token = process.env.UPSTASH_REDIS_REST_TOKEN || process.env.KV_REST_API_TOKEN;
  if (!url || !token) throw new Error('license storage is not configured');
  const res = await fetch(url, {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' },
    body: JSON.stringify(args.map(String)),
  });
  const j = await res.json().catch(() => null);
  if (!res.ok || !j || j.error) throw new Error('storage error: ' + ((j && j.error) || res.status));
  return j.result;
}

// HGETALL comes back as a flat [k, v, k, v...] array; turn it into an object (or null if empty).
export async function hgetall(key) {
  const flat = await cmd('HGETALL', key);
  if (!flat) return null;
  if (!Array.isArray(flat)) return Object.keys(flat).length ? flat : null;
  if (!flat.length) return null;
  const o = {};
  for (let i = 0; i < flat.length; i += 2) o[flat[i]] = flat[i + 1];
  return o;
}
