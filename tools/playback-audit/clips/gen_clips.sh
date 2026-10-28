#!/usr/bin/env bash
# PLAYBACK_AUDIT.md Step B4: generates the synthetic test-media matrix from the audit's table.
# Every clip is synthetic (lavfi test sources, no real broadcast content) so there's nothing here
# that shouldn't be committed -- only the *output* (out/) must stay out of git, see ../.gitignore.
#
# NOT YET RUN: ffmpeg isn't installed on the machine this was written on (owner prerequisite #3 in
# PLAYBACK_AUDIT.md, not yet answered). Every command below is written against documented ffmpeg
# filter/muxer behaviour but hasn't actually been executed -- treat the first real run as a dry run,
# not a known-good script. Run with: bash gen_clips.sh [out-dir]
set -e
command -v ffmpeg >/dev/null || { echo "ffmpeg not found on PATH -- install it first (ask the owner, see PLAYBACK_AUDIT.md prerequisite #3)"; exit 1; }

OUT="${1:-$(dirname "$0")/out}"
mkdir -p "$OUT"
cd "$OUT"

# Burnt-in on-screen clock + frame counter, required on every clip by the audit text. %{n} is the
# output frame index, %{pts\:hms} is the running timecode -- together they let a human (or a
# screen-recorded diff) confirm exactly which frame/second is on screen at any point in a capture.
# fontfile= is required, not optional, on this machine: this ffmpeg build was compiled with
# fontconfig, and without a fontconfig.conf/cache present (fresh Windows install, no fontconfig
# setup) drawtext's font lookup segfaults instead of erroring cleanly. Pointing it straight at a
# real font file skips fontconfig's own lookup path entirely.
FONTFILE=$(echo "${WINDIR:-C:/Windows}/Fonts/consola.ttf" | tr '\\' '/')
BS=$'\x5c'   # bash's own backslash-escaping rules inside ${var//pat/repl} don't survive a literal
             # "\\" in the replacement text (it silently no-ops instead of erroring) -- routing the
             # backslash through a variable sidesteps that rather than fighting bash's parser further.
FONTFILE_ESC="${FONTFILE//:/${BS}:}"
CLOCK="drawtext=fontfile='${FONTFILE_ESC}':text='%{pts\\:hms}  frame %{n}':fontsize=54:fontcolor=white:box=1:boxcolor=black@0.6:x=20:y=20"

# ---------------------------------------------------------------------------
# Video rows (§B4 "Video"). Each is a 20s clip, synthetic testsrc2 pattern + clock, H.264 unless noted.
# ---------------------------------------------------------------------------
gen_video() {
  local name="$1" size="$2" rate="$3" vcodec="$4" extra="$5"
  echo "== video: $name =="
  ffmpeg -y -f lavfi -i "testsrc2=size=${size}:rate=${rate}" -t 20 \
    -vf "$CLOCK" -c:v "$vcodec" $extra -pix_fmt yuv420p "${name}.mp4"
}
gen_video h264_720p50   1280x720  50 libx264 "-preset veryfast -crf 20"
gen_video h264_1080p50  1920x1080 50 libx264 "-preset veryfast -crf 20"
gen_video h264_1080p60  1920x1080 60 libx264 "-preset veryfast -crf 20"
# 1080i50: encode progressive then interlace the frames (top-field-first, the EU broadcast norm) --
# verify field order visually once generated; ffmpeg's interlace handling is easy to get subtly wrong.
echo "== video: h264_1080i50 (interlaced) =="
ffmpeg -y -f lavfi -i "testsrc2=size=1920x1080:rate=50" -t 20 \
  -vf "$CLOCK,tinterlace=interleave_top,fieldorder=tff" -flags +ildct+ilme -c:v libx264 -preset veryfast -crf 20 -pix_fmt yuv420p h264_1080i50.mp4
# HEVC: gate on what B3's device-capability dump actually shows as decodable before relying on these
# in Phase C -- the owner's Fire TV Stick HD has no confirmed HEVC 2160p/10-bit hardware path (see
# Checkpoint A's facts table), so these two may only be useful for confirming the app falls back
# sanely, not for measuring smooth playback.
gen_video hevc_1080p     1920x1080 50 libx265 "-preset veryfast -crf 22"
echo "== video: hevc_2160p10 (10-bit, gate on B3 findings) =="
ffmpeg -y -f lavfi -i "testsrc2=size=3840x2160:rate=50" -t 20 \
  -vf "$CLOCK,format=yuv420p10le" -c:v libx265 -preset veryfast -crf 24 -pix_fmt yuv420p10le hevc_2160p10.mp4
# MPEG-2: only meaningful if B3's MediaCodecList dump actually lists an mpeg2 decoder on the stick.
gen_video mpeg2_1080p50  1920x1080 50 mpeg2video "-b:v 8M"

# ---------------------------------------------------------------------------
# Audio rows (§B4 "Audio"). Muxed onto the 1080p50 H.264 clip above, 20s, one file per codec.
# ---------------------------------------------------------------------------
gen_audio() {
  local name="$1" acodec="$2" extra="$3"
  echo "== audio: $name =="
  ffmpeg -y -i h264_1080p50.mp4 -f lavfi -i "sine=frequency=1000:sample_rate=48000" -t 20 \
    -map 0:v -map 1:a -c:v copy -c:a "$acodec" $extra "audio_${name}.mp4"
}
gen_audio aac_lc aac "-b:a 192k -profile:a aac_low"
# HE-AAC needs an encoder most stock ffmpeg builds don't ship (libfdk_aac is the common one, it's
# non-free so a lot of distro builds omit it). Check `ffmpeg -encoders | grep -i aac` first --
# this line is a best effort and may need swapping for whatever HE-AAC encoder the local build has.
gen_audio he_aac libfdk_aac "-b:a 64k -profile:a aac_he" || echo "  (skipped -- no HE-AAC encoder in this ffmpeg build)"
gen_audio mp2 mp2 "-b:a 192k"
gen_audio ac3 ac3 "-b:a 384k"
gen_audio eac3 eac3 "-b:a 384k" || echo "  (skipped -- no E-AC3 encoder in this ffmpeg build)"
# Dual-language: two synthetic tones (1000Hz / 600Hz) standing in for two languages, tagged eng/spa
# so a track-switch test has something audibly different to confirm against.
echo "== audio: dual_language =="
ffmpeg -y -i h264_1080p50.mp4 \
  -f lavfi -i "sine=frequency=1000:sample_rate=48000" -f lavfi -i "sine=frequency=600:sample_rate=48000" -t 20 \
  -map 0:v -map 1:a -map 2:a -c:v copy -c:a aac -b:a 192k \
  -metadata:s:a:0 language=eng -metadata:s:a:1 language=spa audio_dual_language.mp4

# ---------------------------------------------------------------------------
# Delivery rows (§B4 "Delivery"). Repackage the 1080p50/AAC clip into each container/segmenting style.
# ---------------------------------------------------------------------------
echo "== delivery: progressive mp4/mkv =="
ffmpeg -y -i h264_1080p50.mp4 -c copy delivery_progressive.mp4
ffmpeg -y -i h264_1080p50.mp4 -c copy delivery_progressive.mkv

echo "== delivery: HLS, TS segments =="
mkdir -p hls_ts
ffmpeg -y -i h264_1080p50.mp4 -c copy -f hls -hls_time 4 -hls_segment_type mpegts -hls_list_size 0 hls_ts/stream.m3u8

echo "== delivery: HLS, fMP4 segments =="
mkdir -p hls_fmp4
ffmpeg -y -i h264_1080p50.mp4 -c copy -f hls -hls_time 4 -hls_segment_type fmp4 -hls_list_size 0 hls_fmp4/stream.m3u8

echo "== delivery: continuous raw MPEG-TS (Xtream-style) =="
ffmpeg -y -i h264_1080p50.mp4 -c copy -f mpegts delivery_continuous.ts

# ---------------------------------------------------------------------------
# Subtitle rows (§B4 "Subtitles").
# ---------------------------------------------------------------------------
echo "== subtitles: WebVTT in HLS =="
mkdir -p hls_vtt
cat > captions.vtt <<'EOF'
WEBVTT

00:00:02.000 --> 00:00:05.000
Synthetic WebVTT caption, line one

00:00:08.000 --> 00:00:11.000
Synthetic WebVTT caption, line two
EOF
ffmpeg -y -i h264_1080p50.mp4 -i captions.vtt -c:v copy -c:a aac -c:s webvtt \
  -f hls -hls_time 4 -hls_segment_type mpegts -hls_list_size 0 hls_vtt/stream.m3u8

echo "== subtitles: CEA-608 (muxed as closed captions in the video stream itself) =="
ffmpeg -y -f lavfi -i "testsrc2=size=1920x1080:rate=50" -t 20 -vf "$CLOCK" \
  -c:v libx264 -preset veryfast -crf 20 -a53cc 1 subs_cea608.mp4

# DVB subtitles: ffmpeg does have a native `dvbsub` encoder, but region placement/timing with it is
# genuinely fiddly and this has NOT been visually verified (no ffmpeg install to test against yet) --
# treat this one line as the most likely of the whole script to need hand-fixing, not as done.
echo "== subtitles: DVB (best-effort, unverified -- flagged per the audit's own 'if feasible') =="
ffmpeg -y -i h264_1080p50.mp4 -i captions.vtt -c:v copy -c:a aac -c:s dvbsub -f mpegts subs_dvb.ts || echo "  (failed -- this ffmpeg build may not have dvbsub; see comment above)"

# ---------------------------------------------------------------------------
# Sync row (§B4 "Sync"): a flash + beep once a second, for the owner's manual by-eye/by-ear A/V sync
# check in Phase C. Flash = white for the first 50ms of each second; beep = 1kHz tone for the same
# 50ms window, so the two should land on the same frame if sync is correct.
# ---------------------------------------------------------------------------
echo "== sync: flash+beep clip =="
# Both -i's first, then -t/-vf as output options -- an earlier version interleaved -t/-vf between
# the two -i's, which ffmpeg's option parser silently misattached to the second input instead of
# the output, producing an "Error parsing options for input file aevalsrc=...:s=48000" that had
# nothing to do with aevalsrc's own (valid) syntax.
ffmpeg -y \
  -f lavfi -i "color=size=1920x1080:rate=50:color=black" \
  -f lavfi -i "aevalsrc=exprs='if(lt(mod(t\,1)\,0.05),sin(2*PI*1000*t),0)':s=48000" \
  -t 30 -vf "geq=lum='if(lt(mod(T\,1)\,0.05),255,16)':cb=128:cr=128,${CLOCK}" \
  -map 0:v -map 1:a -c:v libx264 -preset veryfast -crf 18 -c:a aac -shortest sync_flash_beep.mp4

# ---------------------------------------------------------------------------
# Stream-quirk rows (§B4 "Stream quirks"). These rely on binary-concatenating independently-encoded
# .ts segments -- each segment's PTS/PMT starts fresh, so splicing the raw transport streams together
# naturally reproduces the discontinuity/PMT-change the audit asks for, without needing to hand-craft
# TS packets. All built from delivery_continuous.ts above (encode it first if running rows out of order).
# ---------------------------------------------------------------------------
echo "== quirk: non-keyframe start (cut mid-GOP, not aligned to a keyframe) =="
ffmpeg -y -i delivery_continuous.ts -ss 2.3 -c copy -avoid_negative_ts make_zero quirk_nonkeyframe_start.ts

echo "== quirk: timestamp discontinuity mid-stream =="
ffmpeg -y -f lavfi -i "testsrc2=size=1920x1080:rate=50" -t 8 -vf "$CLOCK" -c:v libx264 -preset veryfast -crf 20 -c:a aac -f mpegts _quirk_part_a.ts
ffmpeg -y -f lavfi -i "testsrc2=size=1920x1080:rate=50" -t 8 -vf "$CLOCK" -c:v libx264 -preset veryfast -crf 20 -c:a aac -f mpegts _quirk_part_b.ts
cat _quirk_part_a.ts _quirk_part_b.ts > quirk_timestamp_discontinuity.ts
rm -f _quirk_part_a.ts _quirk_part_b.ts

echo "== quirk: PAT/PMT change mid-stream (audio track count changes across the splice) =="
ffmpeg -y -f lavfi -i "testsrc2=size=1920x1080:rate=50" -f lavfi -i "sine=frequency=1000" -t 8 \
  -vf "$CLOCK" -map 0:v -map 1:a -c:v libx264 -preset veryfast -crf 20 -c:a aac -f mpegts _quirk_pmt_a.ts
ffmpeg -y -f lavfi -i "testsrc2=size=1920x1080:rate=50" -f lavfi -i "sine=frequency=1000" -f lavfi -i "sine=frequency=600" -t 8 \
  -vf "$CLOCK" -map 0:v -map 1:a -map 2:a -c:v libx264 -preset veryfast -crf 20 -c:a aac -f mpegts _quirk_pmt_b.ts
cat _quirk_pmt_a.ts _quirk_pmt_b.ts > quirk_pmt_change.ts
rm -f _quirk_pmt_a.ts _quirk_pmt_b.ts

echo "== quirk: wide bitrate swings (alternating low/high bitrate segments) =="
ffmpeg -y -f lavfi -i "testsrc2=size=1920x1080:rate=50" -t 8 -vf "$CLOCK" -c:v libx264 -preset veryfast -b:v 500k -maxrate 500k -bufsize 500k -c:a aac -f mpegts _quirk_bitrate_lo.ts
ffmpeg -y -f lavfi -i "testsrc2=size=1920x1080:rate=50" -t 8 -vf "$CLOCK" -c:v libx264 -preset veryfast -b:v 12M -maxrate 12M -bufsize 12M -c:a aac -f mpegts _quirk_bitrate_hi.ts
cat _quirk_bitrate_lo.ts _quirk_bitrate_hi.ts _quirk_bitrate_lo.ts > quirk_bitrate_swings.ts
rm -f _quirk_bitrate_lo.ts _quirk_bitrate_hi.ts

echo "All clips written to $OUT"
