# Red Carpet Streams — Store-Readiness & Premium Audit Report

**Status:** Phase 0 (Inventory) only. §1–§9 not started. No code changed.

---

## §0 Inventory

### Platforms and builds

- **Fire TV / Android** — `firestick/` (Gradle, single module `app`). Native wrapper
  (`MainActivity.java`) hosts a WebView and exposes `window.RCS` to the shared JS. `applicationId
  com.tyre.redcarpetstreams`, `minSdk 25`, `targetSdk 34`, `compileSdk 34` (`firestick/app/build.gradle`).
  One release build type + one debug build type (`applicationIdSuffix '.debug'`, installs side by side).
- **Windows** — `windows/` (Electron, `windows/package.json`). `main.js`/`preload.js` host a `<video>` +
  hls.js + mpegts.js player, with `mpv` bundled as a fallback (`extraResources` → `mpv/` → `mpv`) for
  formats Chromium can't decode. No separate flavours.
- **LG webOS** — no dedicated project folder. Shares the Windows playback path (`<video>` + hls.js), per
  `CLAUDE.md`. Not independently verified in this pass — **UNVERIFIED** how/where it's packaged for the
  LG Content Store.
- **Shared core** — `tv-app/index.html` (~7,000+ lines, vanilla JS/HTML/CSS, no framework) is the one
  real source file for all UI/logic. `tv-app/desktop.html` and `tv-app/quick.html` are **generated**
  from it by `tools/gen_light.py` (light theme + propagation) — not hand-written duplicates; never audit
  or edit them directly as if they were independent code.

### Dependencies

- **firestick/app/build.gradle:68-71** — `androidx.media3:media3-exoplayer:1.4.1`,
  `media3-exoplayer-hls:1.4.1`, `media3-ui:1.4.1`, `androidx.annotation:annotation-experimental:1.4.1`.
  Already flagged in `docs/PLAYBACK_AUDIT_REPORT.md` (D1): 7 minor versions behind current stable
  (1.11.1) — not re-researched here, see that report.
- **windows/package.json** — devDependencies only: `electron ^31.0.0`, `electron-builder ^24.13.3`. No
  separate runtime dependency list; the shipped app is the Electron runtime + bundled `mpv` binary +
  the shared `tv-app` assets.
- **Unpinned CDN libraries** — `tv-app/index.html:1114-1115` loads `hls.js` and `mpegts.js` from
  `cdn.jsdelivr.net/npm/` with **no version pinned in the URL**. This means the exact version running in
  production can change without a code change or review — worth pinning a version for a store-shipped
  app. Flag for §1/§4.
- **rcs-guide** (`resolve_v2.py`) — Python, no `requirements.txt`/`pyproject.toml` found in the repo;
  dependency list is **UNVERIFIED** (likely stdlib + `requests` or similar, not confirmed by reading the
  full script in this pass). This repo doesn't ship inside the app binary, so lower priority for store
  compliance, but still relevant to C-9/C-10.

### Players and parsers

- **Fire TV**: native Media3 `ExoPlayer` (Java, `MainActivity.java`), driven from JS via the
  `window.RCS` bridge / `NativeStreamer` object in `tv-app/index.html`.
- **Windows / LG webOS**: `<video>` element + `hls.js` (HLS) + `mpegts.js` (MPEG-TS), with `mpv`
  (bundled, Windows only) as the fallback for MKV/Dolby/DTS/TrueHD/HEVC that Chromium can't decode
  (per `CLAUDE.md`).
- **M3U parsing**: `tv-app/index.html` around line 1665 — playlist fetched via XHR
  (`x.onload = () => (x.status >= 200 && x.status < 300) ? res(x.responseText) : rej(...)`), parsed
  client-side. Exact parse-loop line range not fully traced in this pass — **UNVERIFIED** whether it's
  streaming/incremental or a single parse pass (relevant to D-1 in the later checklist).
- **Xtream Codes**: detected around `tv-app/index.html:1690` —
  `{type: 'xtream', server: x.origin + x.pathname.replace(/\/get\.php$/i, ''), user, pass}`.
- **XMLTV**: `xmlDec()` at `tv-app/index.html:1747` handles CDATA/entity decoding for guide XML.

### Network endpoints

All found via a URL-literal grep of `tv-app/index.html` (exhaustive for literals; dynamic/constructed
URLs not separately traced):

| Endpoint | Purpose | Data sent |
|---|---|---|
| `fonts.googleapis.com` (:7) | Web font | None user-specific |
| `cdn.jsdelivr.net/npm/{hls.js,mpegts.js}` (:1114-1115) | Player libraries | None |
| `cracknback.github.io/rcs-guide/{guide-v2.json,sports.xml.gz}` (:1969, :2971, :4431) | Central sports guide data | None (read-only fetch) |
| `api.football-data.org/v4/competitions/...` (:2037) | Fixture data | `ui.fdKey` as `X-Auth-Token` header — **see Secrets below, this key has a hardcoded default** |
| `api.jolpi.ca/ergast/f1/current/races/` (:2079) | F1 race schedule | None |
| `thesportsdb.com/api/v1/json/...` (:2482) | TV-listing/channel data | `ui.tsdbKey`, user-supplied, in the URL path |
| `api.anthropic.com/v1/messages` (:2537) | "Online game lookup" — asks which channels/streaming services show a specific match | `ui.aiKey` (user-supplied, `x-api-key` header) + team names, competition, kickoff time, chosen countries. Uses Anthropic's `web_search` tool. |
| `epgshare01.online/...` (:2973) | Third-party XMLTV EPG | None |
| `fergie77.github.io/listings-epg/guide.xml.gz` (:3004) | Third-party XMLTV EPG | None |
| `ntfy.sh/` (:4430) | QR-login relay — comment at :4428 states payload is encrypted so the relay can't read it | Encrypted blob only, per the code's own comment (not independently verified cryptographically in this pass) |
| `iptv-org.github.io/api/` (:5515) | Public IPTV channel-metadata reference | None |

This list (minus library/font loads) is the raw material for C-7's Data Safety / privacy-policy
disclosure and feeds directly into it.

### Bundled assets

`tv-app/*.png` / `*.jpg` — glyph icons (`glyph-*.png`), rail icons (`rail-*.png`), category icons
(`icon-*.png`), sport-category icons (`lg-*.png`, `sport-football.png`), hero/background art
(`hero-banner.jpg`, `home-bg-*.jpg`, `sport-bg-*.jpg`, `sportshub-bg-*.jpg`), a logo (`logo.png`), and
a Sports Hub badge (`sh-badge.png`). `firestick/app/src/main/res/` has the Android launcher icons
(`mipmap-*/ic_launcher.png`) and TV banner (`drawable/banner.png`). `windows/icon.png` is the Windows
app icon.

**No LICENSE file or asset-provenance documentation found anywhere in either repo.** Origin/licence of
every image above is **UNVERIFIED** from the repo alone — this directly feeds C-3 (no league/team/
broadcaster logos) and needs the owner to confirm which assets are their own original artwork versus
anything sourced elsewhere, before any store submission.

### Trademark-term strings (representative, not exhaustive — full table is a §1/C-4 deliverable)

- `tv-app/index.html:1273-1276` — a ~90-entry broadcaster/league abbreviation set (`ACR`) used for
  internal channel-name recognition: `BBC, ITV, ESPN, CNN, HBO, NBC, CBS, NFL, NBA, NHL, MLB, UFC, WWE,
  EPL, UEFA, FIFA, DAZN, BEIN, TNT, …` — not directly user-facing, but these strings exist in the
  shipped binary.
- `tv-app/index.html:1498` — Sports search-category keyword list: `"sport,sports,football,soccer,epl,
  premier league,nfl,nba,nhl,mlb,ufc,boxing,wwe,racing,f1,golf,tennis,cricket,rugby,afl,nrl,espn,bein,
  dazn,tsn,motorsport,ppv,fight,fighting,olympic,olympics,darts"`.
- `tv-app/index.html:1919-1923` — per-sport category keyword lists: Football (`premier league, epl, la
  liga, bundesliga, serie a, ligue 1, champions league, europa league, uefa, mutv, lfc tv, a league, mls,
  fa cup, efl, spfl`), Motorsport (`f1, formula 1, formula one, motogp, nascar, indycar, supercars, wrc,
  rally, dtm`), Fight sports (`ufc, boxing, wwe, mma`), US sports (`nfl, nba, nhl, mlb`).
- `tv-app/index.html:2018` — a code comment: *"With a free key, Premier League and UEFA Champions League
  fixtures come from football-data.org."*

A full line-by-line table covering every occurrence (including any in `firestick`/`windows` native code,
UI labels specifically, and the Sports Hub's sport-selector labels) is **not yet produced** — flagged as
the first concrete task for §1/C-4.

### Secrets — flagged prominently, see summary below

Two real, currently-live exposures found in the **public** `red-carpet-streams` repo
(`github.com/CracknBack/red-carpet-streams`, confirmed `PUBLIC` via `gh repo view`), both introduced in
commit `0f47c0c` ("Replace stale v2.8 build pipeline with v3.19.0 project"):

1. **The Android release-signing keystore itself is committed and tracked**:
   `firestick/app/rcs-release.p12` (confirmed via `git ls-files`). `firestick/app/build.gradle:33-39`
   hardcodes its password in plaintext: `storePassword 'REDACTED-OLD-KEYSTORE-PASSWORD'`,
   `keyPassword 'REDACTED-OLD-KEYSTORE-PASSWORD'`, `keyAlias 'rcs'`. Anyone who clones this public repo has the full
   private signing key and its password — enough to sign a malicious APK that Android would accept as a
   legitimate update to the real app on any device that already has it installed. This is not a
   historical/removed-since finding — it is sitting in the current working tree and `HEAD`, public, right
   now.
2. **A hardcoded football-data.org API key**: `tv-app/index.html:1221` —
   `ui = Object.assign({fdKey: 'REDACTED-OLD-FOOTBALLDATA-KEY', ...}`. This is a real key format
   (32 hex chars) shipped as the *default* value in every install of the app and visible in the public
   repo/git history, not a placeholder. Anyone can extract and use it under the owner's account.

By contrast, the **rcs-guide repo does this correctly** — `resolve_v2.py:37-38` reads `FD_KEY` and
`TSDB_KEY` only via `os.environ.get(...)`, populated from GitHub Actions secrets
(`.github/workflows/guide-v2.yml:46-47`, `secrets.FD_KEY` / `secrets.TSDB_KEY`). No hardcoded key found
in that repo in this pass.

`ui.tsdbKey` (TheSportsDB) and `ui.aiKey` (Anthropic) are both user-supplied via a Settings prompt
(`askText(...)`, e.g. `tv-app/index.html:6829-6833`) — empty by default, not baked in. These are **not**
leaks.

**Scope note on this check:** the above was found via targeted `git log -S<string>` searches once the
two secrets were spotted in the working tree, not an exhaustive line-by-line replay of every commit in
both repos' full history. It is possible other now-removed secrets exist deeper in history that this
pass didn't surface — **UNVERIFIED** that these are the only two, recommend a dedicated secret-scanning
pass (e.g. `gitleaks` or `trufflehog` over full history of both repos) before relying on this being a
complete list.

---

*(§1–§9 not yet attempted — this file currently contains Phase 0 only, per
`STORE_READINESS_AUDIT.md`'s own phase gate: "Stop and wait for the owner to approve" applies at the end
of Phase 1, but Phase 0's inventory should be reviewed first given the severity of the Secrets findings
above.)*
