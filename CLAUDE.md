# Red Carpet Streams

Personal IPTV app (owner: CracknBack / Chillie). One shared codebase (`tv-app/index.html`, vanilla JS,
no framework, ~7000 lines) wrapped for three platforms:

- **Fire TV / Android** (`firestick/`) — WebView + native player bridge (`window.RCS`). The owner's
  primary, actively-used device is a **Fire TV Stick HD** — weak CPU, limited memory. Most real bugs in
  this project have been memory/performance problems that only show up at this device's scale.
- **Windows** (`windows/`) — Electron (`windows/main.js`/`preload.js`), native `<video>` + hls.js, with
  mpv bundled as a fallback for formats Chromium can't decode (MKV, Dolby/DTS/TrueHD, HEVC). Not the
  owner's main device — **don't trigger a Windows CI build unless explicitly asked**, even though code
  changes to the shared `tv-app/index.html` do affect it.
- **LG webOS** — same native `<video>` + hls.js path as Windows, no dedicated project folder.

## Build/deploy

- `.github/workflows/firestick.yml` and `windows.yml` are **`workflow_dispatch`-only** (no `on: push`),
  deliberately, to avoid burning CI minutes on every commit. Trigger with
  `gh workflow run firestick.yml --ref main`, watch with `gh run watch <id> --exit-status`, download the
  release APK with `gh release download firestick-<N> --dir <dir>`.
- After any `tv-app/index.html` edit, run `python tools/gen_light.py` to regenerate the light theme and
  propagate it to `tv-app/desktop.html` / `tv-app/quick.html`. **Those two are generated files — never
  hand-edit them directly**, edits belong in `index.html` only.
- **The owner prefers batching fixes before building.** Don't trigger a CI build after every single
  change — stack fixes up in code (running `gen_light.py` after each) and build once when asked to ship.
- Only build `firestick.yml` unless told otherwise.

## Critical: working-directory layout gotcha

In this session's setup, the repo is checked out at `_repo_check/red-carpet-streams/`, and a separate
scratch copy historically used for edits lives at `_extracted/android/` (a sibling directory, not part
of the git repo). **Before ever copying a file from one into the other, diff first**
(`diff --strip-trailing-cr <(git show HEAD:path) local_copy`). A past session silently clobbered ~15
commits of real fixes by copying a stale local file over a freshly `git pull`ed one without checking —
every single push for a while was quietly reverting its own prior work. Always `git pull --ff-only` the
repo copy before touching it, and never bulk-copy a whole file without confirming the diff is *exactly*
the intended change (not "roughly" — exactly, line by line).

## Testing on-device (Fire TV Stick)

- ADB over WiFi, typically `192.168.11.121:5555` — reconnect if stale (`adb disconnect <ip>` then
  `adb connect <ip>`; a long-idle connection can silently hang on the next command).
- **Disable the screensaver before any unattended test**:
  `adb shell settings put secure screensaver_enabled 0` and `adb shell svc power stayon true`. The Fire
  TV's idle screensaver pauses the WebView's JS execution entirely, which looks *exactly* like a frozen
  app if you don't know to check (`adb shell dumpsys window windows | grep mCurrentFocus` — if it shows
  `Sys2023:dream` instead of the app, that's the screensaver, not a bug). Restore both settings
  afterward (`screensaver_enabled 1`, `stayon false`).
- Install directly with `adb install -r <apk>` rather than a GitHub release link + Downloader app on the
  device — much faster feedback loop, and avoids version-downgrade install failures from stale links.
- The app logs rich diagnostics to logcat as `MEMPROBE [stage] heapUsedMB=... liveCh=... events=...`
  (tag `RCS_Console`) via a `memLog()` call — added during a long debugging session and load-bearing for
  diagnosing memory/performance regressions. Don't remove it. `MainActivity.java`'s `onConsoleMessage`
  override is what makes `console.*` calls reach logcat at all; without it, nothing shows.

## Hard constraint: this provider's catalog is huge

The owner's IPTV account has **~49,000 live channels, ~50,000+ grouped movie titles, ~49,000+ series** —
far larger than typical. Any code path that fetches/groups/holds the **full** movies+series catalog in
memory at once **will OOM-crash a Fire TV Stick HD** (confirmed repeatedly via
`V8 javascript OOM (Reached heap limit)` in logcat). This has cost real time twice already:

- Movies/Series browsing used to fetch+group the whole catalog on first open — fixed by fetching one
  provider category at a time (`ensureCategoryItems`/`ensureCategoryReady`), cached per-category in
  IndexedDB. Favourites/Recently-added/All-A-Z tiles were deliberately **removed** from Movies/Series
  (not a bug — a real scope cut), since those inherently need the whole catalog.
- Search used to do the same for its match pool — fixed with a **lean search index**
  (`procSearchIndexG`/`ensureSearchIndex`/`SRCHIDX`): just `{id, name, cat}` per stream, no title/version
  grouping, a small fraction of a full grouped catalog's memory. Selecting a result hydrates the full
  item on demand via the same per-category fetch Movies/Series browsing already uses
  (`DATA[t].verToTitle`).

**Rule of thumb:** any new feature that needs to "look at everything" in vod/series must go through a
lean index + lazy hydration, never a full fetch+group pass. If in doubt, check the MEMPROBE heap numbers
on-device before considering something fixed — don't trust that a fix "should" work.

## Known architectural facts worth remembering

- `buildEventsG()` (sports-event matching, home screen's "Live Sports" banner) has real O(n²) risk —
  several rounds of fixes replaced linear `.find()` scans over growing arrays with indexed lookups after
  being measured at 100+ seconds a pass. If it's slow again, **bisect stage-by-stage with temporary
  `memLog()` checkpoints before guessing** — that's what found the real cost each time (most recently:
  `fixtureEvents()` re-parsing every channel name per fixture, completely unmemoized).
- Live fixture status changes (a match going SCHEDULED → IN_PLAY) used to null out the *entire* `EVENTS`
  cache and force a full rebuild on every change — which during an active match weekend meant Home's
  banner visibly "reloaded" almost every time it was opened. Fixed in `loadFixtures()`: only a genuinely
  *structural* change (match added/removed, kickoff time corrected) triggers a full rebuild; a pure
  status change patches the existing `EVENTS` entries' `.status` field in place.
- `FIRE` / `PC` / `PHONE` are the platform flags (`window.RCS` present / `window.RCSPC` or
  `window.RCS_DESKTOP` present / Android touch + small screen). Volume, aspect/zoom, and audio/subtitle
  track switching only work where `video` is a real HTML5 element (`!FIRE`) — Fire TV's native player
  (`NV` stand-in object) has no web-layer hook for any of these; those controls simply don't appear there.
- `ui` (persisted to `localStorage` as `sp_ui`) is the per-device settings object — add new persisted
  preferences there with a default in the `Object.assign({...defaults...}, load('sp_ui', {}))` call near
  the top of the script.
- The pause-screen button row (`osdButtons()`) and the ≡ options menu (`optItems()`) share the same
  underlying item-builders (`audioMenuItems`, `subtitleMenuItems`, `versionItems`) so the two surfaces
  can't drift apart — extend those shared functions, don't duplicate logic into just one surface.
