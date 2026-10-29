#!/usr/bin/env node
// PLAYBACK_AUDIT.md Step B5: local HTTP server, reachable from the Fire TV Stick over LAN, that
// serves the clips from clips/out/ and can simulate each fault the audit lists on demand. Zero
// dependencies on purpose -- built-in `http`/`fs` only, so there's no npm install step between the
// owner installing Node and running this.
//
// Usage:  node fault-server.js [port]            (default port 8098)
// Every fault is triggered by a query-string flag on the request, e.g.:
//   http://<pc-ip>:8098/hls_ts/stream.m3u8?cap=3                 -- 3 Mbps bandwidth cap
//   http://<pc-ip>:8098/hls_ts/stream.m3u8?latency=800           -- 800ms added latency before first byte
//   http://<pc-ip>:8098/hls_ts/stream.m3u8?slowstart=3000        -- slow first byte (3000ms before headers)
//   http://<pc-ip>:8098/delivery_continuous.ts?stall=10000       -- 10s stall partway through the body
//   http://<pc-ip>:8098/delivery_continuous.ts?drop=1            -- connection destroyed partway through
//   http://<pc-ip>:8098/hls_ts/stream.m3u8?missing=stream2.ts    -- that one segment 404s, everything else is fine
//   http://<pc-ip>:8098/delivery_continuous.ts?redirect=1        -- 302 to the same path (add &https=1 for a fake http->https hop)
//   http://<pc-ip>:8098/delivery_continuous.ts?requireua=VLC     -- 403 unless User-Agent contains "VLC"
// Flags combine freely (e.g. ?cap=5&latency=200). See README.md for the full list and rationale.
'use strict';
const http = require('http');
const fs = require('fs');
const path = require('path');
const url = require('url');

const PORT = Number(process.argv[2]) || 8098;
const ROOT = path.join(__dirname, 'clips', 'out');

const MIME = {
  '.m3u8': 'application/vnd.apple.mpegurl', '.ts': 'video/mp2t', '.mp4': 'video/mp4',
  '.m4s': 'video/iso.segment', '.mkv': 'video/x-matroska', '.vtt': 'text/vtt',
};
function mimeFor(p) { return MIME[path.extname(p).toLowerCase()] || 'application/octet-stream'; }

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

const server = http.createServer(async (req, res) => {
  const parsed = url.parse(req.url, true);
  const q = parsed.query;
  let reqPath = decodeURIComponent(parsed.pathname);
  if (reqPath === '/') { res.writeHead(200, { 'Content-Type': 'text/plain' }); res.end('fault-server up. See README.md for the query-flag list.'); return; }

  // requireua: reject unless the User-Agent contains the given substring -- tests the app's own UA.
  if (q.requireua && !(req.headers['user-agent'] || '').includes(String(q.requireua))) {
    res.writeHead(403, { 'Content-Type': 'text/plain' }); res.end('expected User-Agent containing: ' + q.requireua); return;
  }

  // redirect: 302 once (drop the flag so the redirected request falls through to normal serving).
  if (q.redirect) {
    const dest = parsed.pathname + (q.https ? '' : '') ;
    const scheme = q.https ? 'https' : 'http';
    res.writeHead(302, { Location: `${scheme}://${req.headers.host}${parsed.pathname}` });
    res.end();
    return;
  }

  // missing: this request's path 404s if its basename matches the given filename (e.g. one HLS
  // segment named in the flag goes missing while the rest of the playlist/segments serve fine).
  if (q.missing && path.basename(reqPath) === String(q.missing)) {
    res.writeHead(404, { 'Content-Type': 'text/plain' }); res.end('simulated 404: ' + q.missing); return;
  }

  const filePath = path.join(ROOT, reqPath);
  if (!filePath.startsWith(ROOT)) { res.writeHead(400); res.end('bad path'); return; }
  let stat;
  try { stat = fs.statSync(filePath); } catch (e) { res.writeHead(404, { 'Content-Type': 'text/plain' }); res.end('not found: ' + reqPath + ' (run clips/gen_clips.sh first)'); return; }
  if (stat.isDirectory()) { res.writeHead(404); res.end('is a directory'); return; }

  // slowstart / latency: both delay before the response starts; slowstart is the literal "slow
  // first byte" case (headers+body both wait), latency is a general added-RTT simulation (same
  // effect here since this server doesn't model TCP handshake timing separately).
  const preDelay = Number(q.slowstart || q.latency || 0);
  if (preDelay > 0) await sleep(preDelay);

  // Range support is not optional for a progressive-MP4 test: a moov atom muxed at the end of the
  // file (ffmpeg's default without -movflags +faststart, which gen_clips.sh doesn't set) means
  // Media3's HTTP data source opens with a Range request for the file's tail before it can read
  // the metadata needed to start playback at all. Without this, every progressive-MP4 clip just
  // sits there -- discovered exactly that way, testing h264_1080p50.mp4 for real.
  let start = 0, end = stat.size - 1, status = 200;
  const range = req.headers.range;
  if (range) {
    const m = /bytes=(\d*)-(\d*)/.exec(range);
    if (m) {
      if (m[1]) start = Number(m[1]);
      if (m[2]) end = Number(m[2]);
      else if (!m[1]) { start = stat.size - Number(m[2] || 0); end = stat.size - 1; } // bytes=-N (suffix range)
      status = 206;
    }
  }
  const length = end - start + 1;
  const headers = { 'Content-Type': mimeFor(filePath), 'Content-Length': length, 'Accept-Ranges': 'bytes', 'Cache-Control': 'no-store' };
  if (status === 206) headers['Content-Range'] = `bytes ${start}-${end}/${stat.size}`;
  res.writeHead(status, headers);

  const stream = fs.createReadStream(filePath, { start, end, highWaterMark: 64 * 1024 });
  let sent = 0;
  const capMbps = Number(q.cap || 0);           // bandwidth cap in Mbps
  const stallMs = Number(q.stall || 0);          // one-time stall partway through the body
  const stallAt = Math.floor(length * 0.4);      // ~40% through this response, so it's a genuine mid-stream stall
  let stalled = false;
  const dropAt = q.drop ? Math.floor(length * 0.5) : -1;
  const bytesPerMs = capMbps > 0 ? (capMbps * 1_000_000 / 8) / 1000 : 0;

  req.on('close', () => stream.destroy());

  // `for await` over the stream instead of a 'data' listener: Node's async-iterator protocol
  // pauses the underlying stream between iterations on its own, so there's exactly one thing
  // controlling flow at a time -- no race between an artificial throttle delay and res.write's
  // own backpressure (an earlier pause()/resume()-based version of this had exactly that race
  // and produced truncated transfers under a bandwidth cap).
  (async () => {
    try {
      for await (const chunk of stream) {
        if (dropAt >= 0 && sent + chunk.length >= dropAt) { res.destroy(); return; } // simulated dropped connection, no FIN
        if (stallMs > 0 && !stalled && sent + chunk.length >= stallAt) { stalled = true; await sleep(stallMs); }
        if (bytesPerMs > 0) await sleep(chunk.length / bytesPerMs);   // crude token-bucket: hold each chunk back to average out to the cap
        sent += chunk.length;
        if (!res.write(chunk)) await new Promise(resolve => res.once('drain', resolve));
      }
      res.end();
    } catch (e) { try { res.destroy(); } catch (_) {} }
  })();
});

server.listen(PORT, () => {
  console.log(`fault-server listening on http://0.0.0.0:${PORT}`);
  console.log(`serving ${ROOT} (run clips/gen_clips.sh first if that's empty)`);
  console.log('See README.md for the fault query-flag list.');
});
