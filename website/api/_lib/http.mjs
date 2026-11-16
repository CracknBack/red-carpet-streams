// Shared HTTP helpers for the licence endpoints.

// The app calls these from a WebView / Electron / plain browsers, so allow any origin.
// Nothing here relies on cookies; every call is authorised by the licence key itself.
export const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type, Accept',
  'Access-Control-Max-Age': '86400',
};

export function json(body, status = 200, extra = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', ...CORS, ...extra },
  });
}

export function preflight() {
  return new Response(null, { status: 204, headers: CORS });
}

// The app sends application/x-www-form-urlencoded (same as it did for Lemon Squeezy); accept JSON too.
export async function readParams(request) {
  const text = await request.text();
  const type = request.headers.get('content-type') || '';
  if (type.includes('json')) {
    try { const o = JSON.parse(text); return o && typeof o === 'object' ? o : {}; } catch { return {}; }
  }
  return Object.fromEntries(new URLSearchParams(text));
}
