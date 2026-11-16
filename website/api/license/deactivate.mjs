// POST /api/license/deactivate   (form or JSON: license_key, instance_id)  ->  {deactivated}
import { json, preflight, readParams } from '../_lib/http.mjs';
import { deactivate } from '../_lib/license.mjs';

export function OPTIONS() { return preflight(); }

export async function POST(request) {
  try {
    const p = await readParams(request);
    return json({ deactivated: await deactivate(p.license_key, p.instance_id) });
  } catch (e) {
    console.error('deactivate failed', e && e.message);
    return json({ deactivated: false }, 500);
  }
}
