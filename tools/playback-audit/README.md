# PLAYBACK_AUDIT.md Phase B harness

Three pieces, matching the audit's own step numbers:

- **B1/B2/B3** (diagnostics overlay, structured logcat, device-capability dump) live in
  `firestick/app/src/main/java/com/tyre/redcarpetstreams/MainActivity.java`, gated behind
  `BuildConfig.DEBUG` — none of it exists in a release build. Needs `buildFeatures { buildConfig true }`
  in `app/build.gradle` (already added) since AGP 8+ doesn't generate `BuildConfig` by default.
  - Overlay toggle on-device: press **MENU 5 times within 2 seconds**.
  - `pull_logs.py` (this folder) pulls and summarises the `RCS_PBLOG`/`RCS_CAPS` logcat lines:
    `python pull_logs.py [adb-serial]`

- **B4** — `clips/gen_clips.sh`: generates the full synthetic test-media matrix from the audit's
  table (delivery containers, video codecs/resolutions/frame rates, audio codecs, subtitles, an
  A/V sync clip, stream quirks) via ffmpeg. Requires ffmpeg on PATH — **not installed on this PC
  as of writing**, so this script has been written carefully against documented ffmpeg behaviour
  but not actually run yet. Output goes to `clips/out/`, which is gitignored (never commit real or
  synthetic generated media, only the generator).

  ```
  bash clips/gen_clips.sh          # writes into clips/out/
  ```

  Two rows are flagged as best-effort in the script's own comments: HE-AAC/E-AC3 encoding depends
  on what codecs the local ffmpeg build has (`ffmpeg -encoders | grep -i aac`), and DVB subtitles
  are genuinely fiddly with ffmpeg's native `dvbsub` encoder — both have a `||` fallback that prints
  a skip message rather than failing the whole script.

- **B5** — `fault-server.js`: a zero-dependency Node HTTP server that serves `clips/out/` to the
  Fire TV Stick over LAN and can simulate each fault the audit lists, via query-string flags on the
  request:

  | Flag | Effect |
  |---|---|
  | `?cap=N` | Bandwidth cap, N in Mbps (e.g. `?cap=3`) |
  | `?latency=N` | N ms added before the response starts |
  | `?slowstart=N` | Same as `latency` — a slow first byte, named separately per the audit's own wording |
  | `?stall=N` | N ms stall partway (~40%) through the body, once |
  | `?drop=1` | Connection destroyed partway (~50%) through the body, no clean close |
  | `?missing=<filename>` | 404s only when the request's basename matches (e.g. one HLS segment) |
  | `?redirect=1` | 302 redirect to the same path. Add `&https=1` to redirect into an `https://` URL (cross-protocol redirect test) |
  | `?requireua=<substring>` | 403 unless the `User-Agent` header contains that substring |

  Flags combine freely, e.g. `?cap=5&latency=200`. Run with:

  ```
  node fault-server.js [port]      # default port 8098
  ```

  Then point the Fire TV Stick at `http://<this-PC's-LAN-IP>:<port>/<path-from-clips/out>`, e.g.
  `http://192.168.1.50:8098/hls_ts/stream.m3u8?cap=3`.

  **Verified working** (smoke-tested against a dummy file during development, not yet against real
  ffmpeg-generated clips or the actual Fire TV Stick): basic serve, bandwidth cap, stall, dropped
  connection, missing-segment 404, User-Agent gate, and redirect all behave as described above.
  The implementation deliberately uses `for await...of` over the file stream rather than manual
  `pause()`/`resume()` — an earlier version mixed an artificial throttle delay with Node's own
  backpressure-driven pause/resume and produced truncated transfers under a bandwidth cap; the
  `for await` form lets exactly one thing control flow at a time.
