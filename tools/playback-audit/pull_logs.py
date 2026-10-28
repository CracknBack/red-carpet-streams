# PLAYBACK_AUDIT.md Step B2: pulls the RCS_PBLOG/RCS_CAPS lines MainActivity.java's debug-only
# harness writes (see firestick/app/src/main/java/.../MainActivity.java) and summarises them.
# Usage: python tools/playback-audit/pull_logs.py [adb-serial]
# Requires a debug build on the device (BuildConfig.DEBUG gates all of this harness code).
import subprocess, sys, re, statistics as st

serial = sys.argv[1] if len(sys.argv) > 1 else None
cmd = ['adb'] + (['-s', serial] if serial else []) + ['logcat', '-d', '-v', 'time', 'RCS_PBLOG:I', 'RCS_CAPS:I', '*:S']
try:
    out = subprocess.run(cmd, capture_output=True, text=True, timeout=30).stdout
except FileNotFoundError:
    sys.exit('adb not found on PATH')

EVENT = re.compile(r'event=(\S+)')
KV = re.compile(r'(\w+)=("[^"]*"|\S+)')

events = []
for line in out.splitlines():
    if 'RCS_PBLOG' not in line and 'RCS_CAPS' not in line:
        continue
    m = EVENT.search(line)
    if not m:
        continue
    kv = dict(KV.findall(line))
    kv['_event'] = m.group(1)
    events.append(kv)

if not events:
    print('No RCS_PBLOG/RCS_CAPS lines found. Checklist:')
    print('  - is this a DEBUG build? (release builds have none of this code)')
    print('  - did you play anything since the logcat buffer was last cleared? (adb logcat -c)')
    sys.exit(0)

plays = [e for e in events if e['_event'] == 'play']
first_frames = [e for e in events if e['_event'] == 'first_frame']
rebuffers = [e for e in events if e['_event'] == 'rebuffer']
dropped = [e for e in events if e['_event'] == 'dropped_frames']
errors = [e for e in events if e['_event'] == 'error']
vdec = [e for e in events if e['_event'] == 'video_decoder_init']
adec = [e for e in events if e['_event'] == 'audio_decoder_init']
vfmt = [e for e in events if e['_event'] == 'video_format_change']
afmt = [e for e in events if e['_event'] == 'audio_format_change']
caps = [e for e in events if e['_event'] in ('decoder', 'audio_passthrough', 'audio_output', 'display_mode')]

def ints(lst, key):
    out = []
    for e in lst:
        v = e.get(key)
        if v and v not in ('-1', 'null'):
            try: out.append(int(v))
            except ValueError: pass
    return out

print(f'=== Red Carpet Streams playback log summary ({len(events)} harness lines) ===\n')

print(f'Plays started: {len(plays)}')
ttff = ints(first_frames, 'ttffMs')
if ttff:
    print(f'Time to first frame: {len(ttff)} samples, '
          f'min={min(ttff)}ms avg={round(st.mean(ttff))}ms max={max(ttff)}ms median={round(st.median(ttff))}ms')
else:
    print('Time to first frame: no samples (no first_frame events seen)')

rb_durs = ints(rebuffers, 'durationMs')
print(f'\nRebuffers: {len(rebuffers)}' + (f', total={sum(rb_durs)}ms avg={round(st.mean(rb_durs))}ms max={max(rb_durs)}ms' if rb_durs else ''))

df_counts = ints(dropped, 'count')
print(f'Dropped-frame events: {len(dropped)}' + (f', total frames dropped={sum(df_counts)}' if df_counts else ''))

print(f'\nErrors: {len(errors)}')
if errors:
    by_code = {}
    for e in errors:
        c = e.get('code', '?')
        by_code[c] = by_code.get(c, 0) + 1
    for c, n in sorted(by_code.items(), key=lambda x: -x[1]):
        print(f'  {c}: {n}')

print(f'\nVideo decoders seen: {sorted(set(e.get("name", "?") for e in vdec))}')
print(f'Audio decoders seen: {sorted(set(e.get("name", "?") for e in adec))}')
print(f'Video format changes: {len(vfmt)}, last={vfmt[-1].get("format") if vfmt else "-"}')
print(f'Audio format changes: {len(afmt)}, last={afmt[-1].get("format") if afmt else "-"}')

if caps:
    print(f'\nDevice capability dump present ({len(caps)} lines) -- see raw output below for the full MediaCodecList/passthrough/display-mode dump.')

print('\n--- raw matching lines ---')
for line in out.splitlines():
    if 'RCS_PBLOG' in line or 'RCS_CAPS' in line:
        print(line)
