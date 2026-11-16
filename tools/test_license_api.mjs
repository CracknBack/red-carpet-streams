// Offline test of the licence API (website/api). Run: node tools/test_license_api.mjs
// Uses an in-memory stand-in for Redis and fake signed webhooks; touches no network and no real data.
import { createHmac } from 'node:crypto';
import assert from 'node:assert/strict';

const store = new Map();
globalThis.__RC_KV__ = ([c, ...a]) => {
  c = c.toUpperCase();
  const h = k => store.get(k) || (store.set(k, new Map()), store.get(k));
  switch (c) {
    case 'SET': if (a[2] === 'NX' && store.has(a[0])) return null; store.set(a[0], a[1]); return 'OK';
    case 'GET': { const v = store.get(a[0]); return v === undefined || v instanceof Map ? null : v; }
    case 'HGET': { const m = store.get(a[0]); return m && m.has(a[1]) ? m.get(a[1]) : null; }
    case 'HSET': { const m = h(a[0]); let n = 0; for (let i = 1; i < a.length; i += 2) { if (!m.has(a[i])) n++; m.set(a[i], a[i + 1]); } return n; }
    case 'HSETNX': { const m = h(a[0]); if (m.has(a[1])) return 0; m.set(a[1], a[2]); return 1; }
    case 'HLEN': return store.get(a[0])?.size || 0;
    case 'HDEL': return store.get(a[0])?.delete(a[1]) ? 1 : 0;
    case 'HGETALL': return [...(store.get(a[0])?.entries() || [])].flat();
    default: throw new Error('unmocked ' + c);
  }
};

process.env.POLAR_WEBHOOK_SECRET = 'whsec_' + Buffer.from('polar-test-secret-bytes').toString('base64');
process.env.CREEM_WEBHOOK_SECRET = 'creem_test_secret';
delete process.env.RESEND_API_KEY;

const { generateKey, normalizeKey } = await import('../website/api/_lib/license.mjs');
const polar = await import('../website/api/webhook/polar.mjs');
const creem = await import('../website/api/webhook/creem.mjs');
const act = await import('../website/api/license/activate.mjs');
const deact = await import('../website/api/license/deactivate.mjs');
const look = await import('../website/api/license/lookup.mjs');

const post = (url, body, headers = {}) => new Request('https://x.test' + url, { method: 'POST', body, headers });
const polarHook = (obj, { secretKey, ts = Math.floor(Date.now() / 1000), id = 'msg_' + Math.random() } = {}) => {
  const body = JSON.stringify(obj);
  const key = secretKey ?? Buffer.from('polar-test-secret-bytes');
  const sig = createHmac('sha256', key).update(`${id}.${ts}.${body}`).digest('base64');
  return post('/api/webhook/polar', body, { 'webhook-id': id, 'webhook-timestamp': String(ts), 'webhook-signature': 'v1,' + sig });
};
const creemHook = (obj, secret = process.env.CREEM_WEBHOOK_SECRET) => {
  const body = JSON.stringify(obj);
  return post('/api/webhook/creem', body, { 'creem-signature': createHmac('sha256', secret).update(body).digest('hex') });
};
const form = o => new URLSearchParams(o).toString();
const activate = async (key, name) => (await act.POST(post('/api/license/activate', form({ license_key: key, instance_name: name }), { 'content-type': 'application/x-www-form-urlencoded' }))).json();
const paid = (id, checkout, extra = {}) => ({ type: 'order.paid', data: { id, checkout_id: checkout, billing_reason: 'purchase', status: 'paid', total_amount: 499, refunded_amount: 0, customer: { email: 'a@b.co' }, ...extra } });
const keyFor = async (provider, checkout) => (await (await look.GET(new Request(`https://x.test/api/license/lookup?provider=${provider}&checkout_id=${checkout}`))).json());

let n = 0; const t = async (name, fn) => { await fn(); n++; console.log('ok -', name); };

await t('key format: 15 safe chars, unique, normalises', async () => {
  const seen = new Set();
  for (let i = 0; i < 2000; i++) { const k = generateKey(); assert.match(k, /^[A-HJKMNP-Z2-9]{5}(-[A-HJKMNP-Z2-9]{5}){2}$/); seen.add(k); }
  assert.equal(seen.size, 2000);
  const k = generateKey();
  assert.equal(normalizeKey(k.toLowerCase().replace(/-/g, ' ')), k);
  assert.equal(normalizeKey('nope'), '');
});

await t('polar: bad/old signatures rejected, both secret styles accepted', async () => {
  assert.equal((await polar.POST(post('/api/webhook/polar', '{}', {}))).status, 403);
  assert.equal((await polar.POST(polarHook(paid('o0', 'ck_polar_0000'), { ts: 1000 }))).status, 403); // stale timestamp
  assert.equal((await polar.POST(polarHook(paid('o0', 'ck_polar_0000'), { secretKey: Buffer.from('wrong') }))).status, 403);
  assert.equal((await polar.POST(polarHook(paid('o1', 'ck_polar_0001')))).status, 200); // standard key (base64 of secret body)
  process.env.POLAR_WEBHOOK_SECRET = 'whsec_legacyplainsecret'; // older Polar secrets: key = UTF-8 bytes of whole string
  assert.equal((await polar.POST(polarHook(paid('o2', 'ck_polar_0002'), { secretKey: Buffer.from('whsec_legacyplainsecret') }))).status, 200);
  process.env.POLAR_WEBHOOK_SECRET = 'whsec_' + Buffer.from('polar-test-secret-bytes').toString('base64');
});

await t('polar: order.paid issues one key; retries do not issue a second', async () => {
  const ev = paid('order_A', 'ck_polar_AAAAAA');
  assert.equal((await polar.POST(polarHook(ev))).status, 200);
  const first = (await keyFor('polar', 'ck_polar_AAAAAA')).key;
  assert.ok(first);
  assert.equal((await polar.POST(polarHook(ev))).status, 200);
  assert.equal((await keyFor('polar', 'ck_polar_AAAAAA')).key, first);
  assert.equal((await keyFor('polar', 'ck_polar_NOPE00')).status, 'pending');
});

await t('activate: works, same device reuses its slot, cap of 5, deactivate frees one', async () => {
  await polar.POST(polarHook(paid('order_B', 'ck_polar_BBBBBB')));
  const key = (await keyFor('polar', 'ck_polar_BBBBBB')).key;
  const a = await activate(key.toLowerCase(), 'Red Carpet Player - AAAA');
  assert.equal(a.activated, true); assert.ok(a.instance.id);
  assert.equal((await activate(key, 'Red Carpet Player - AAAA')).instance.id, a.instance.id); // idempotent
  for (const d of ['BBBB', 'CCCC', 'DDDD', 'EEEE']) assert.equal((await activate(key, 'Red Carpet Player - ' + d)).activated, true);
  const sixth = await activate(key, 'Red Carpet Player - FFFF');
  assert.equal(sixth.activated, false); assert.match(sixth.error, /5 devices/);
  assert.equal((await (await deact.POST(post('/api/license/deactivate', form({ license_key: key, instance_id: a.instance.id })))).json()).deactivated, true);
  assert.equal((await activate(key, 'Red Carpet Player - FFFF')).activated, true);
  assert.equal((await activate('ZZZZZ-ZZZZZ-ZZZZZ', 'x')).activated, false);
  assert.equal((await activate('', 'x')).activated, false);
});

await t('polar: partial refund keeps the key, full refund revokes it', async () => {
  await polar.POST(polarHook(paid('order_C', 'ck_polar_CCCCCC')));
  const key = (await keyFor('polar', 'ck_polar_CCCCCC')).key;
  await polar.POST(polarHook({ ...paid('order_C', 'ck_polar_CCCCCC'), type: 'order.refunded', data: { ...paid('order_C').data, status: 'partially_refunded', refunded_amount: 100 } }));
  assert.equal((await activate(key, 'd1')).activated, true);
  await polar.POST(polarHook({ type: 'order.refunded', data: { ...paid('order_C').data, status: 'refunded', refunded_amount: 499 } }));
  const r = await activate(key, 'd2');
  assert.equal(r.activated, false); assert.match(r.error, /refunded or revoked/);
});

await t('refund arriving before its order: the late order is issued revoked', async () => {
  await polar.POST(polarHook({ type: 'order.refunded', data: { ...paid('order_D').data, status: 'refunded', refunded_amount: 499 } }));
  await polar.POST(polarHook(paid('order_D', 'ck_polar_DDDDDD')));
  const key = (await keyFor('polar', 'ck_polar_DDDDDD')).key;
  assert.equal((await activate(key, 'd')).activated, false);
});

await t('polar: subscriptions / other events are ignored, product filter works', async () => {
  assert.equal((await polar.POST(polarHook(paid('order_E', 'ck_polar_EEEEEE', { billing_reason: 'subscription_cycle' })))).status, 200);
  assert.equal((await keyFor('polar', 'ck_polar_EEEEEE')).status, 'pending');
  process.env.POLAR_PRODUCT_ID = 'prod_pro';
  await polar.POST(polarHook(paid('order_F', 'ck_polar_FFFFFF', { product_id: 'prod_other' })));
  assert.equal((await keyFor('polar', 'ck_polar_FFFFFF')).status, 'pending');
  await polar.POST(polarHook(paid('order_G', 'ck_polar_GGGGGG', { product_id: 'prod_pro' })));
  assert.equal((await keyFor('polar', 'ck_polar_GGGGGG')).status, 'ready');
  delete process.env.POLAR_PRODUCT_ID;
});

await t('creem: signature, checkout.completed issues + dedupes, refund.created revokes', async () => {
  const ev = { id: 'evt_1', eventType: 'checkout.completed', object: { id: 'ch_creem_HHHHHH', order: { id: 'ord_H' }, customer: { email: 'c@d.co' }, product: { id: 'prod_c' } } };
  assert.equal((await creem.POST(creemHook(ev, 'wrong-secret'))).status, 403);
  assert.equal((await creem.POST(post('/api/webhook/creem', JSON.stringify(ev)))).status, 403);
  assert.equal((await creem.POST(creemHook(ev))).status, 200);
  const key = (await keyFor('creem', 'ch_creem_HHHHHH')).key;
  assert.ok(key);
  await creem.POST(creemHook(ev));
  assert.equal((await keyFor('creem', 'ch_creem_HHHHHH')).key, key);
  assert.equal((await activate(key, 'tv')).activated, true);
  await creem.POST(creemHook({ id: 'evt_2', eventType: 'refund.created', object: { id: 'ref_1', order: { id: 'ord_H' } } }));
  assert.equal((await activate(key, 'tv2')).activated, false);
});

await t('lookup rejects junk input; CORS preflight works', async () => {
  assert.equal((await look.GET(new Request('https://x.test/api/license/lookup?provider=evil&checkout_id=abcdefgh'))).status, 400);
  assert.equal((await look.GET(new Request('https://x.test/api/license/lookup?provider=polar&checkout_id=../x'))).status, 400);
  const p = act.OPTIONS(); assert.equal(p.status, 204); assert.equal(p.headers.get('access-control-allow-origin'), '*');
});

console.log(`\nall ${n} test groups passed`);
