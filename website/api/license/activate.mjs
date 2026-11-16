// POST /api/license/activate   (form or JSON: license_key, instance_name)
// Same response shape the app already used with Lemon Squeezy: {activated, instance:{id}, error}.
import { json, preflight, readParams } from '../_lib/http.mjs';
import { activate } from '../_lib/license.mjs';

export function OPTIONS() { return preflight(); }

export async function POST(request) {
  try {
    const p = await readParams(request);
    const r = await activate(p.license_key, p.instance_name);
    return r.ok
      ? json({ activated: true, instance: { id: r.instanceId } })
      : json({ activated: false, error: r.error });
  } catch (e) {
    console.error('activate failed', e && e.message);
    return json({ activated: false, error: 'The licensing server had a problem. Please try again in a minute.' }, 500);
  }
}
