# Red Carpet Streams — Playback Audit Report

**Status:** Phases A and B complete. Phase C covers clean-network key cases only — the fault-injection
matrix (B5 faults × B4 clips) and the 60-minute soak / 200-channel zap endurance tests were not
attempted. Phase D (this report) covers only the areas with real evidence gathered this session; other
`D1`–`D18` areas from `PLAYBACK_AUDIT.md` are marked **NOT ASSESSED** rather than guessed at.

**A correction, stated up front:** an earlier version of one finding in this session ("a ~3-4.5s
rebuffer happens almost immediately after first frame, reproduced 5+ times") was wrong — it was a bug
in the measurement harness itself (it counted a stream's normal first-load buffering as a "rebuffer"
unconditionally, with no check for whether playback had actually started yet). That harness bug is
fixed. After the fix, a clean retest showed **zero rebuffers** on the same clip. This report reflects
that correction; see D4 below for what's left of this finding.

---

## Findings

| ID | Area | Current behaviour (evidence) | Problem | Recommendation | Expected gain | Risk | Effort | Depends on | Owner decision? |
|---|---|---|---|---|---|---|---|---|---|
| D1 | Engine | Stock `androidx.media3` 1.4.1 (`firestick/app/build.gradle`); latest stable is 1.11.1. Amazon maintains its own fork (`amzn/exoplayer-amazon-port`, `amzn/media3-external-port`) with Fire-TV-specific fixes (Dolby on Gen1 Fire TV, audio-latency fixes, VSYNC frame-release handling) not in stock Media3. | App is ~7 minor versions behind, and isn't using the vendor's own patched fork despite running on the vendor's own hardware. | Evaluate upgrading to current stable Media3 first (lower risk, standard path); evaluate Amazon's fork separately, specifically for the Dolby/audio fixes it claims, given D10's AC3/E-AC3 findings below. | Fewer upstream bugs; possibly fixes D10 directly | Medium — any engine bump needs a full regression pass | M | — | Yes — which path (stock upgrade vs. Amazon fork) is a real product decision |
| D3 | HTTP | `DefaultHttpDataSource.Factory` with `setAllowCrossProtocolRedirects(true)`, fixed UA string, 15s/20s connect/read timeouts, no per-playlist headers. (`MainActivity.java`) | Single global UA isn't configurable per provider; no custom headers support. | Low priority unless a specific provider needs it — flag only. | — | Low | S | — | No |
| D4 | Buffering | **Fixed this session.** Was: no custom `LoadControl` at all (Media3 defaults: 50s/50s min/max buffer). Now: explicit `DefaultLoadControl.Builder().setBufferDurationsMs(15000, 30000, 4000, 6000)`. | Original problem ("rebuffer right after start") turned out to be a measurement-harness bug, not a real stall — see correction above. The buffer-size tuning itself (sizing for this device's ~1GB RAM rather than untuned 50s/50s defaults) is still defensible on its own merits, but the specific `bufferForPlaybackMs` bump from ~2.5s to 4s was motivated by the now-retracted finding and was **not re-verified** after the fix for impact on time-to-first-frame / channel-zap speed. | Re-test actual TTFF impact of the 4s start threshold against the stock ~2.5s default before considering it final; dial back if it doesn't show a measurable benefit, since it directly costs zap speed. | Faster channel changes if reverted/tuned down | Low (one-line revert if needed) | S | Re-running B4 clean-case tests | No — but owner should know this number is a guess that wants re-checking |
| D5 | Live behaviour | **Fixed this session, not yet re-verified.** `RCS.play()` now takes an `isLive` flag; when true, sets `MediaItem.LiveConfiguration` with a 10s target offset. Previously: live and VOD built the exact same `MediaItem`, so `getCurrentLiveOffset()` always returned "not live" even on a genuinely live channel. | The 10s target offset is a guess, not a measured/tuned value, and the fix overall was not confirmed working after implementation (ran out of clean test time this session — see memory for why). | Confirm fix works (play a real live channel, check the debug overlay's "behind live" field), then tune the target offset against real catch-up/latency behaviour. | Enables real "distance behind live," rejoin-live-after-stall logic later | Low | S (to verify) | On-device test with real account | No |
| D7 | Errors & retries | `NativeStreamer.next()` (tv-app/index.html) collapses every error into "try the next candidate URL," capped (no infinite loop) but no backoff and no per-error-class user messaging. Native `onPlayerError` now logs `PlaybackException.getErrorCodeName()` in debug builds (Phase B harness) but this isn't surfaced to the release UI at all. | Confirmed concretely via D10: an E-AC3 failure produces **no error and no message**, AC3 produces a generic native error the JS layer doesn't distinguish from "offline." | Classify errors (network / HTTP / decoder / unsupported-codec / behind-live-window) and show a distinct message per class, per the audit's own target table ("never a black screen or silent audio"). | Directly fixes the E-AC3 silent-failure case | Medium — touches user-facing error UI | M | D10's codec findings | Yes — exact wording/UX of error messages |
| D10 | Audio | **Confirmed via real on-device testing, not guesses.** AC3: `ERROR_CODE_AUDIO_TRACK_INIT_FAILED`, reproduced twice. E-AC3: fails completely silently — no error, no `audio_format_change`, no audio, nothing. MP2: decodes briefly via software (`OMX.google.mp3.decoder`) then `ERROR_CODE_DECODING_FAILED`. AAC: works fine, confirming this is codec-specific. | A real, meaningful slice of broadcast content (AC3/E-AC3/MP2 are common on IPTV/sports) either errors out or plays with silently broken audio. | **Needs the Media3 FFmpeg audio decoder extension** for software AC3/E-AC3/MP2 decoding — not attempted this session: requires an Android NDK build toolchain not present on this machine, and has real licensing questions (the audit's own ground rules say these must go to the owner, not be resolved unilaterally). Short-term: at minimum, fix D7 so a codec failure here shows a clear message instead of silence. | Large — unlocks broadcast audio that currently fails | Licensing risk (owner decision), build-pipeline effort | L (full fix), S (just the error message) | A dedicated session with NDK set up; owner's licensing call | **Yes — this needs the owner's explicit go-ahead before any FFmpeg extension work starts, including the licensing question itself** |
| D13 | Channel zapping | One `ExoPlayer` reused for the app's lifetime (confirmed in Phase A); `setMediaItem`+`prepare` on each change, no rebuild. | Already good practice — no change recommended. The D4 buffer-size question above is the only open variable affecting zap speed. | — | — | — | — | — | No |
| D14 | Lifecycle | `onPause` pauses (not releases) the player; screen-awake tied to `isPlaying`; `onRenderProcessGone` rebuilds the WebView (not the player) on a crash. (Phase A finding, unchanged this session) | None found — confirmed sound. | — | — | — | — | — | No |

**NOT ASSESSED this session** (no evidence gathered, do not assume pass or fail): D2 (stream-type
detection), D6 (TS robustness / discontinuities — the B4 quirk clips exist but were never actually
tested against the app), D8 (decoder fallback / track-selection constraints — beyond the HEVC
capability-probe caveat noted in Phase B), D9 (surface/aspect), D11 (subtitles), D12
(smoothness/refresh-rate — needs the owner's own eyes per C3), D15 (multi-view — not implemented at
all per Phase A), D16 (adaptive bitrate), D17 (diagnostics export), D18 (remote control mapping in the
player).

---

## Top 5 viewer-impact fixes, in priority order

1. **D10 — AC3/E-AC3/MP2 audio.** The single biggest real, confirmed gap. E-AC3's total silence is the
   worst failure mode in the whole audit (no error, no sound, no explanation).
2. **D7 — Error classification and messaging.** Directly fixes how D10 presents to a real viewer, even
   before the underlying codec gap is solved.
3. **D5 — Live-offset configuration.** Already coded this session; just needs verification and tuning.
   Low effort, unlocks "distance behind live" and future rejoin-live logic.
4. **D4 — Buffering.** Needs re-verification of whether the shipped tuning is actually beneficial or
   should be dialed back; currently a guess, not a measured improvement.
5. **D1 — Engine version / Amazon's fork.** Worth a dedicated evaluation session, specifically checking
   whether Amazon's fork's Dolby/audio fixes address D10 without needing the full FFmpeg extension.

## Owner decisions needed

- **D1**: stock Media3 upgrade vs. evaluating Amazon's Fire-TV-specific fork — which to pursue, or both.
- **D7**: exact wording/UX for per-error-class messages shown to viewers.
- **D10**: whether to pursue the FFmpeg software-decoder extension at all, and — if yes — the licensing
  question the audit's own ground rules say must come to the owner before any code is written. This is
  the single biggest decision in this report; it's a multi-session effort, not a quick patch.

## Additional findings (not in the original PLAYBACK_AUDIT.md checklist)

- **Digit-typing bug, already fixed and verified**: `dispatchKeyEvent` swallowed every digit keypress
  globally for the remote's channel-number-jump feature, so no text field anywhere in the app (Xtream
  login, search, PIN, M3U/EPG links) could ever receive a digit typed via the physical remote. This
  would have blocked real users from logging in via remote at all, for any provider whose credentials
  contain a digit (i.e. almost all of them). Fixed via a focus-aware bridge call; verified end-to-end
  with the owner's real account.
- **Phase B's own B5 test server needed HTTP Range support** to test progressive MP4/MKV at all — a
  genuinely useful harness-correctness finding, not an app bug, but worth recording since it initially
  produced a misleading "61-second time to first frame" result.
- **B3's device-capability dump can under-report real decoder support** — its `MediaCodecInfo` size
  probe failed for this device's HEVC decoder even though HEVC played back fine on real content. Anyone
  reading future B3 dumps should trust what's listed, not silence on a codec.
- **CLAUDE.md has a stale claim** (audio/subtitle switching "only work where `!FIRE`") that's
  contradicted by the current code. Low priority, but worth a one-line correction next time that file is
  touched.
- **Continue Watching's completion detection is a flat 95%-of-duration cutoff** with no awareness of
  long trailing credits — flagged by the owner directly, not part of this audit's original scope, noted
  here for completeness since it's genuinely a playback-adjacent UX issue. Not actioned.

## Proposed fix batches

1. **D7 error messaging** (small, self-contained; re-test: play each of the three broken audio codecs
   from B4's synthetic clips, confirm a specific, distinct message appears for each — especially the
   currently-silent E-AC3 case).
2. **D5 live-offset verification + tuning** (re-test: play a real live channel, confirm the debug
   overlay's "behind live" shows a real number; experiment with the target-offset value against
   real-world stall/catch-up behaviour).
3. **D4 buffering re-check** (re-test: replay the B4 clean-case clips with both the current 4s start
   threshold and the stock ~2.5s default, compare actual time-to-first-frame; keep whichever genuinely
   performs better, not just whichever was first implemented).
4. **D1 engine evaluation** (research-only batch: no code changes, just determine whether Amazon's fork
   is worth adopting, and whether a stock Media3 version bump is low-risk enough to do on its own first).
5. **D10 FFmpeg extension** (its own dedicated effort — needs NDK toolchain setup and the owner's
   licensing decision before any code is written; not bundled with the above).
