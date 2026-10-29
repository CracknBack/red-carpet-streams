package com.tyre.redcarpetstreams;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.regex.Pattern;

/*
 * Red Carpet Streams for Fire TV.
 * The app's screens are the same web app as the LG version, shown in a WebView.
 * Video plays in Android's own player (Media3/ExoPlayer), which sits behind the WebView,
 * so the channel banner and controls still draw on top of the picture.
 */
@OptIn(markerClass = UnstableApi.class)
public class MainActivity extends Activity {
    private FrameLayout root;
    private WebView web;
    private PlayerView pv;
    private ExoPlayer player;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean metaSent = false;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (player != null && player.isPlaying()) send("timeupdate");
            if (BuildConfig.DEBUG) updateDebugOverlay();
            ui.postDelayed(this, 500);
        }
    };

    /* ---- PLAYBACK_AUDIT.md Phase B: debug-only measurement harness (overlay + structured
       logcat). Gated on BuildConfig.DEBUG throughout per the audit's ground rule 6 ("harness code
       must be debug-only and must not be in release builds") -- none of this runs in a release
       APK, a debug build is required to see any of it. */
    private TextView debugOverlay;
    private long playRequestedAtMs = 0;
    private boolean sawFirstFrame = false;
    private int rebufferCount = 0;
    private long rebufferStartMs = 0;
    private long lastRebufferMs = -1;
    private String lastVideoDecoder = "-", lastAudioDecoder = "-";
    private String lastVideoFormat = "-", lastAudioFormat = "-";
    private int droppedFrames = 0;
    private String lastError = "-";
    /* Media3's AnalyticsListener never directly reports decoded-vs-passthrough; the only signal
       available without relying on an API we can't verify compiles against 1.4.1
       (onAudioTrackInitialized's AudioTrackConfig) is indirect: a software/hardware *decoder* only
       gets instantiated (onAudioDecoderInitialized fires) when Media3 is decoding to PCM itself --
       passthrough skips that step and writes the compressed bytes straight to the AudioTrack. So
       "no decoder initialized for the current audio format" is read as passthrough. Best-effort;
       confirm against the real decoder name in Checkpoint B rather than trusting this alone. */
    private boolean audioDecoderSeenForCurrentTrack = false;
    private long keySeqLastMs = 0;
    private int keySeqCount = 0;
    /* Set by the page's Bridge.setTyping() call (see index.html's focusin/focusout listener) --
       dispatchKeyEvent's digit-key branch must not swallow number keys for the remote's "jump to
       channel number" feature while a text <input> has real focus, or nothing with a digit in it
       (an Xtream server URL, username, password, search term, PIN) can ever be typed via the
       physical remote. Confirmed on-device before this existed: typing the owner's real account
       username in produced a string with every digit silently stripped -- eaten here, every time,
       not dropped by chance. */
    private volatile boolean typingActive = false;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        /* Debug-only: lets chrome://inspect attach over the existing ADB connection, so Phase B's
           Checkpoint B test case (and anything later) can drive window.RCS.play(<test-clip-url>)
           directly from the DevTools console -- without touching the owner's actual playlist/login
           just to get a clip playing. */
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        pv = new PlayerView(this);
        pv.setUseController(false);
        pv.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        pv.setVisibility(View.GONE);
        root.addView(pv, new FrameLayout.LayoutParams(-1, -1));

        web = makeWebView();
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        if (BuildConfig.DEBUG) {
            debugOverlay = new TextView(this);
            debugOverlay.setTypeface(Typeface.MONOSPACE);
            debugOverlay.setTextColor(Color.GREEN);
            debugOverlay.setTextSize(11);
            debugOverlay.setBackgroundColor(0xA0000000);
            debugOverlay.setPadding(16, 16, 16, 16);
            debugOverlay.setGravity(Gravity.TOP | Gravity.START);
            debugOverlay.setVisibility(View.GONE);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            root.addView(debugOverlay, lp);
        }

        setContentView(root);
        web.requestFocus();

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setUserAgent("Mozilla/5.0 (Linux; Android) RedCarpetStreams")
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000);
        /* PLAYBACK_AUDIT.md Phase C finding: a ~3-4.5s rebuffer was observed landing almost immediately
           after first frame, reproduced 5+ times across synthetic clips AND a real live HLS channel --
           not one bad test file. Media3's own default start threshold (bufferForPlaybackMs ~2.5s) lets
           playback begin before much of a cushion exists; on this device's weak CPU (Fire TV Stick HD,
           1GB RAM, confirmed via B3's capability dump) that cushion drains almost immediately, into a
           stall. Raising the start thresholds trades a little more time-to-first-frame for not stalling
           right after it. Min/max buffer bounds are also set explicitly (Media3's own defaults of
           50s/50s were never tuned for this device's actual RAM) rather than left as a guess. This is a
           single profile, not separate live/VOD ones -- D4's full "separate profiles" recommendation is
           a bigger follow-up, not attempted here. */
        LoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 30_000, 4_000, 6_000)
                .build();
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(this, http)))
                .setLoadControl(loadControl)
                .build();
        pv.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int st) {
                if (st == Player.STATE_BUFFERING) {
                    send("waiting");
                    if (BuildConfig.DEBUG) rebufferStartMs = System.currentTimeMillis();
                } else if (st == Player.STATE_READY) {
                    if (!metaSent) { metaSent = true; send("loadedmetadata"); }
                    if (BuildConfig.DEBUG && rebufferStartMs != 0) {
                        lastRebufferMs = System.currentTimeMillis() - rebufferStartMs;
                        rebufferCount++;
                        Log.i("RCS_PBLOG", "event=rebuffer durationMs=" + lastRebufferMs + " count=" + rebufferCount);
                        rebufferStartMs = 0;
                    }
                } else if (st == Player.STATE_ENDED) send("ended");
            }
            @Override public void onIsPlayingChanged(boolean playing) {
                /* B2 asks for "recoveries" alongside errors/rebuffers -- a recovery here means
                   something started playing again after the last thing we logged was an error
                   (the JS NativeStreamer did the actual retry, by moving to the next candidate
                   URL; this just confirms from the native side that it worked). */
                if (BuildConfig.DEBUG && playing && !"-".equals(lastError)) {
                    Log.i("RCS_PBLOG", "event=recovered previousError=" + lastError);
                    lastError = "-";
                }
                send(playing ? "playing" : "pause");
                keepScreenOn(playing);
            }
            @Override public void onPlayerError(PlaybackException e) {
                if (BuildConfig.DEBUG) {
                    /* PlaybackException has no errorCodeName field -- the name lookup is the static
                       PlaybackException.getErrorCodeName(int), keyed off the numeric errorCode. */
                    String codeName = PlaybackException.getErrorCodeName(e.errorCode);
                    lastError = codeName + " (" + e.errorCode + ")";
                    Log.w("RCS_PBLOG", "event=error code=" + codeName + " url=" + redact(String.valueOf(e.getMessage())));
                }
                send("error");
            }
        });
        if (BuildConfig.DEBUG) {
            player.addAnalyticsListener(new DebugAnalytics());
            dumpDeviceCapabilities();
        }
        ui.post(ticker);

        web.loadUrl("file:///android_asset/www/index.html");
    }

    /*
     * A WebView whose render process is killed (commonly out-of-memory while a big provider's
     * channel/VOD lists load) takes the whole app down with it unless onRenderProcessGone is
     * overridden: that is Android's documented default. Here we rebuild the WebView and reload
     * the page instead of letting the app die silently back to the Fire TV home screen.
     */
    private WebView makeWebView() {
        WebView w = new WebView(this);
        w.setBackgroundColor(Color.TRANSPARENT);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                if (view != web) return false;
                ui.post(MainActivity.this::recreateWebView);
                return true;
            }
        });
        /* The default WebChromeClient's onConsoleMessage does nothing -- page console.log/warn/error
           calls never reach logcat at all, at any level (confirmed: a diagnostic console.error() still
           produced zero output in two separate builds). Overriding it to forward everything to Log is
           what actually makes adb logcat able to see JS-side diagnostics, not just Chromium's own
           internal engine logging (which is separate and was already visible). */
        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                int level = cm.messageLevel() == ConsoleMessage.MessageLevel.ERROR ? Log.ERROR
                        : cm.messageLevel() == ConsoleMessage.MessageLevel.WARNING ? Log.WARN : Log.INFO;
                Log.println(level, "RCS_Console", cm.message() + " (" + cm.sourceId() + ":" + cm.lineNumber() + ")");
                return true;
            }
        });
        w.addJavascriptInterface(new Bridge(), "RCS");
        return w;
    }

    private void recreateWebView() {
        if (player != null) { player.stop(); player.clearMediaItems(); }
        if (pv != null) pv.setVisibility(View.GONE);
        metaSent = false;
        root.removeView(web);
        web.destroy();
        web = makeWebView();
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        web.requestFocus();
        web.loadUrl("file:///android_asset/www/index.html");
    }

    /* Tell the web app what the player is doing. */
    void send(String ev) {
        if (player == null) return;
        long pos = player.getCurrentPosition(), dur = player.getDuration();
        js("window.__rcsVideo&&__rcsVideo('" + ev + "'," + pos + "," + (dur > 0 ? dur : -1) + "," + player.isPlaying() + ")");
    }

    void js(final String code) { ui.post(() -> { if (web != null) web.evaluateJavascript(code, null); }); }

    void keepScreenOn(boolean on) {
        if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /* Remote buttons the WebView doesn't pass on as web key presses (Back, Menu, media keys). */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        /* PLAYBACK_AUDIT.md B1: the overlay's remote toggle -- 5x MENU within 2s. Counts alongside
           (not instead of) MENU's normal single-press forward below, since 5 presses in under 2s
           doesn't happen during ordinary single use. Debug builds only. */
        if (BuildConfig.DEBUG && e.getAction() == KeyEvent.ACTION_DOWN && e.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            long now = System.currentTimeMillis();
            keySeqCount = (now - keySeqLastMs <= 2000) ? keySeqCount + 1 : 1;
            keySeqLastMs = now;
            if (keySeqCount >= 5) {
                keySeqCount = 0;
                toggleDebugOverlay();
            }
        }
        int rawCode = e.getKeyCode();
        boolean isDigit = rawCode >= KeyEvent.KEYCODE_0 && rawCode <= KeyEvent.KEYCODE_9;
        if (isDigit && typingActive) return super.dispatchKeyEvent(e);   /* let the WebView's own IME handle it */
        int code = map(rawCode);
        if (code != 0) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && (e.getRepeatCount() == 0 || code != 461)) js("window.__rcsKey&&__rcsKey(" + code + ")");
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    static int map(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_BACK: return 461;
            case KeyEvent.KEYCODE_MENU: return 1082;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: return 10252;
            case KeyEvent.KEYCODE_MEDIA_PLAY: return 415;
            case KeyEvent.KEYCODE_MEDIA_PAUSE: return 19;
            case KeyEvent.KEYCODE_MEDIA_REWIND: return 412;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: return 417;
            case KeyEvent.KEYCODE_CHANNEL_UP: return 33;
            case KeyEvent.KEYCODE_CHANNEL_DOWN: return 34;
            case KeyEvent.KEYCODE_PROG_RED: return 403;
            case KeyEvent.KEYCODE_PROG_GREEN: return 404;
            case KeyEvent.KEYCODE_PROG_YELLOW: return 405;
            case KeyEvent.KEYCODE_PROG_BLUE: return 406;
            default:
                if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) return 48 + (k - KeyEvent.KEYCODE_0);
                return 0;
        }
    }

    @Override protected void onPause() {
        super.onPause();
        if (player != null) player.pause();
        if (web != null) web.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override protected void onDestroy() {
        ui.removeCallbacks(ticker);
        if (player != null) player.release();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    /* ---- audio and subtitle tracks (always used on the main thread) ---- */
    static class Pick { Tracks.Group g; int i; }

    /* the n-th usable track of one kind (audio or text) */
    Pick nth(int type, int n) {
        int count = 0;
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != type) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                if (count == n) { Pick p = new Pick(); p.g = g; p.i = i; return p; }
                count++;
            }
        }
        return null;
    }

    static String trackLabel(Format f, int n, int type) {
        String name = null;
        if (f.label != null && !f.label.isEmpty()) {
            name = f.label;
        } else if (f.language != null && !"und".equals(f.language)) {
            String l = new Locale(f.language).getDisplayLanguage();
            name = (l == null || l.isEmpty()) ? f.language : l;
        }
        if (name == null) name = (type == C.TRACK_TYPE_AUDIO ? "Audio " : "Subtitles ") + (n + 1);
        if (type == C.TRACK_TYPE_AUDIO && f.channelCount > 2) name += " " + (f.channelCount - 1) + ".1";
        return name;
    }

    /* {"audio":[{"i":0,"l":"English 5.1","sel":true}],"text":[...],"textOn":false} */
    String tracksJson() {
        try {
            JSONArray audio = new JSONArray(), text = new JSONArray();
            int ai = 0, ti = 0;
            for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
                int type = g.getType();
                if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_TEXT) continue;
                for (int i = 0; i < g.length; i++) {
                    if (!g.isTrackSupported(i)) continue;
                    int n = type == C.TRACK_TYPE_AUDIO ? ai : ti;
                    JSONObject o = new JSONObject();
                    o.put("i", n);
                    o.put("l", trackLabel(g.getTrackFormat(i), n, type));
                    o.put("sel", g.isTrackSelected(i));
                    if (type == C.TRACK_TYPE_AUDIO) { audio.put(o); ai++; } else { text.put(o); ti++; }
                }
            }
            JSONObject out = new JSONObject();
            out.put("audio", audio);
            out.put("text", text);
            out.put("textOn", !player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT));
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /* ---- PLAYBACK_AUDIT.md Phase B harness (debug builds only) ---- */

    /* Xtream URLs embed the username/password as path segments (.../live/USER/PASS/ID.ext), not
       query params, so a plain "strip the query string" redaction isn't enough -- ground rule 5
       ("never commit, log, or print the owner's playlist URLs ... credentials"). Replaces the two
       path segments after /live/, /movie/ or /series/ and drops any query string outright. */
    private static final Pattern XTREAM_CREDS = Pattern.compile("/(live|movie|series)/[^/]+/[^/]+/");
    static String redact(String url) {
        if (url == null) return "-";
        String noQuery = url.replaceAll("\\?.*$", "");
        return XTREAM_CREDS.matcher(noQuery).replaceAll("/$1/***/***/");
    }

    private void toggleDebugOverlay() {
        if (debugOverlay == null) return;
        boolean show = debugOverlay.getVisibility() != View.VISIBLE;
        debugOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) { updateDebugOverlay(); dumpDeviceCapabilities(); }
    }

    /* B1: the on-screen stats panel. Values come from the Player interface directly (buffer level,
       live offset, playback state) plus whatever DebugAnalytics below has captured from the most
       recent AnalyticsListener callbacks (decoder names, dropped frames, last error). */
    private void updateDebugOverlay() {
        if (debugOverlay == null || debugOverlay.getVisibility() != View.VISIBLE || player == null) return;
        long bufferedAheadMs = Math.max(0, player.getBufferedPosition() - player.getCurrentPosition());
        long liveOffsetMs = player.getCurrentLiveOffset();
        String ttff = sawFirstFrame ? "-" : "pending";
        StringBuilder sb = new StringBuilder();
        sb.append("RCS PLAYBACK DEBUG (5x MENU to hide)\n");
        sb.append("video decoder: ").append(lastVideoDecoder).append('\n');
        sb.append("audio decoder: ").append(lastAudioDecoder).append(audioDecoderSeenForCurrentTrack ? " (decoded)" : " (passthrough? no decoder seen)").append('\n');
        sb.append("video format:  ").append(lastVideoFormat).append('\n');
        sb.append("audio format:  ").append(lastAudioFormat).append('\n');
        sb.append("buffer ahead:  ").append(String.format(Locale.US, "%.1fs", bufferedAheadMs / 1000.0)).append('\n');
        sb.append("behind live:   ").append(liveOffsetMs == C.TIME_UNSET ? "n/a (not live)" : String.format(Locale.US, "%.1fs", liveOffsetMs / 1000.0)).append('\n');
        sb.append("rebuffers:     ").append(rebufferCount).append(lastRebufferMs >= 0 ? " (last " + lastRebufferMs + "ms)" : "").append('\n');
        sb.append("dropped frames:").append(droppedFrames).append('\n');
        sb.append("time to first frame: ").append(ttff).append('\n');
        sb.append("last error:    ").append(lastError);
        debugOverlay.setText(sb.toString());
    }

    /* B3: device capability dump -- MediaCodecList (profiles/levels/max res+fps per decoder),
       audio passthrough support via AudioTrack.isDirectPlaybackSupported (the API-29+ official way
       to ask; below that there's no reliable public API, so we say so rather than guess), and the
       display's supported refresh-rate modes. One tag (RCS_CAPS) so `adb logcat | grep RCS_CAPS`
       pulls the whole dump. Runs once at startup and again each time the overlay is opened. */
    @SuppressWarnings("deprecation")
    private void dumpDeviceCapabilities() {
        try {
            Log.i("RCS_CAPS", "=== device capability dump: Build.MODEL=" + Build.MODEL + " Build.VERSION.SDK_INT=" + Build.VERSION.SDK_INT + " Build.DISPLAY=" + Build.DISPLAY + " ===");
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
                if (info.isEncoder()) continue;
                for (String type : info.getSupportedTypes()) {
                    if (!type.startsWith("video/") && !type.startsWith("audio/")) continue;
                    try {
                        MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
                        StringBuilder line = new StringBuilder("event=decoder name=" + info.getName() + " type=" + type);
                        if (Build.VERSION.SDK_INT >= 29) line.append(" hw=").append(info.isHardwareAccelerated());
                        MediaCodecInfo.VideoCapabilities vc = caps.getVideoCapabilities();
                        if (vc != null) line.append(" maxW=").append(vc.getSupportedWidths().getUpper())
                                .append(" maxH=").append(vc.getSupportedHeights().getUpper())
                                .append(" maxFps=").append(vc.getSupportedFrameRatesFor(vc.getSupportedWidths().getUpper(), vc.getSupportedHeights().getUpper()));
                        if (caps.profileLevels != null) for (MediaCodecInfo.CodecProfileLevel pl : caps.profileLevels)
                            line.append(" profile/level=").append(pl.profile).append('/').append(pl.level);
                        Log.i("RCS_CAPS", line.toString());
                    } catch (Exception inner) { Log.w("RCS_CAPS", "event=decoder_error name=" + info.getName() + " type=" + type + " err=" + inner); }
                }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                int[] encodings = {AudioFormat.ENCODING_AC3, AudioFormat.ENCODING_E_AC3, AudioFormat.ENCODING_E_AC3_JOC, AudioFormat.ENCODING_DTS};
                String[] names = {"AC3", "E_AC3", "E_AC3_JOC", "DTS"};
                android.media.AudioAttributes attrs = new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE).build();
                for (int i = 0; i < encodings.length; i++) {
                    AudioFormat af = new AudioFormat.Builder().setEncoding(encodings[i]).setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1).build();
                    boolean ok = AudioTrack.isDirectPlaybackSupported(af, attrs);
                    Log.i("RCS_CAPS", "event=audio_passthrough format=" + names[i] + " supported=" + ok);
                }
            } else {
                Log.i("RCS_CAPS", "event=audio_passthrough note=AudioTrack.isDirectPlaybackSupported needs API 29+, this device is API " + Build.VERSION.SDK_INT + " -- UNVERIFIED by this dump");
            }
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) Log.i("RCS_CAPS", "event=audio_output sampleRate=" + am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) + " framesPerBuffer=" + am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER));
            Display d = getWindowManager().getDefaultDisplay();
            for (Display.Mode m : d.getSupportedModes())
                Log.i("RCS_CAPS", "event=display_mode id=" + m.getModeId() + " " + m.getPhysicalWidth() + "x" + m.getPhysicalHeight() + "@" + m.getRefreshRate());
        } catch (Exception e) { Log.w("RCS_CAPS", "event=dump_error err=" + e); }
    }

    /* B2: structured playback logging, one line per event, tag RCS_PBLOG -- `adb logcat` plus the
       summary script in tools/playback-audit/ parses these. Shares the same callbacks B1's overlay
       reads from, so the two surfaces can't drift apart (same reasoning as the shared menu-item
       builders elsewhere in this app -- see CLAUDE.md). */
    private class DebugAnalytics implements AnalyticsListener {
        @Override public void onRenderedFirstFrame(EventTime et, Object output, long renderTimeMs) {
            if (sawFirstFrame) return;
            sawFirstFrame = true;
            long ttffMs = playRequestedAtMs > 0 ? System.currentTimeMillis() - playRequestedAtMs : -1;
            Log.i("RCS_PBLOG", "event=first_frame ttffMs=" + ttffMs);
        }
        @Override public void onDroppedVideoFrames(EventTime et, int count, long elapsedMs) {
            droppedFrames += count;
            Log.i("RCS_PBLOG", "event=dropped_frames count=" + count + " totalMs=" + elapsedMs);
        }
        @Override public void onVideoDecoderInitialized(EventTime et, String decoderName, long initializedTimestampMs, long initializationDurationMs) {
            lastVideoDecoder = decoderName;
            Log.i("RCS_PBLOG", "event=video_decoder_init name=" + decoderName + " initMs=" + initializationDurationMs);
        }
        @Override public void onAudioDecoderInitialized(EventTime et, String decoderName, long initializedTimestampMs, long initializationDurationMs) {
            lastAudioDecoder = decoderName;
            audioDecoderSeenForCurrentTrack = true;
            Log.i("RCS_PBLOG", "event=audio_decoder_init name=" + decoderName + " initMs=" + initializationDurationMs);
        }
        @Override public void onVideoInputFormatChanged(EventTime et, Format format, androidx.media3.exoplayer.DecoderReuseEvaluation reuse) {
            lastVideoFormat = format.sampleMimeType + " " + format.width + "x" + format.height + "@" + format.frameRate + " " + (format.bitrate > 0 ? format.bitrate / 1000 + "kbps" : "");
            Log.i("RCS_PBLOG", "event=video_format_change format=" + lastVideoFormat);
        }
        @Override public void onAudioInputFormatChanged(EventTime et, Format format, androidx.media3.exoplayer.DecoderReuseEvaluation reuse) {
            audioDecoderSeenForCurrentTrack = false;   /* new track: re-derive decoded-vs-passthrough from here */
            lastAudioFormat = format.sampleMimeType + " " + format.channelCount + "ch " + format.sampleRate + "Hz " + (format.bitrate > 0 ? format.bitrate / 1000 + "kbps" : "");
            Log.i("RCS_PBLOG", "event=audio_format_change format=" + lastAudioFormat);
        }
    }

    /* Methods the web app can call as window.RCS.*  */
    class Bridge {
        /* PLAYBACK_AUDIT.md Phase C finding: getCurrentLiveOffset() returned "not live" (C.TIME_UNSET)
           even while genuinely playing a live HLS channel, because no MediaItem ever got a
           LiveConfiguration -- live and VOD used the exact same MediaItem.fromUri(url) path. isLive
           comes from the page (NativeStreamer.start() already knows it.t === 'live'); without it,
           native code has no way to tell live and VOD apart at all. The 10s target offset is a
           starting point per the audit's own D5 -- not a tuned final value, the owner should confirm.
           A single two-arg method rather than an overload: WebView's JS-bridge reflection resolves
           overloads by argument count/type, which works, but there's only one real caller (JS always
           passes both now) so there's no reason to add that ambiguity for no benefit. */
        @JavascriptInterface public void play(final String url, final boolean isLive) {
            ui.post(() -> {
                metaSent = false;
                if (BuildConfig.DEBUG) {
                    playRequestedAtMs = System.currentTimeMillis();
                    sawFirstFrame = false;
                    rebufferCount = 0; lastRebufferMs = -1; droppedFrames = 0; lastError = "-";
                    audioDecoderSeenForCurrentTrack = false;
                    Log.i("RCS_PBLOG", "event=play url=" + redact(url) + " live=" + isLive);
                }
                player.setPlaybackSpeed(1f);
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .clearOverrides().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
                pv.setVisibility(View.VISIBLE);
                MediaItem.Builder mb = new MediaItem.Builder().setUri(url);
                if (isLive) {
                    mb.setLiveConfiguration(new MediaItem.LiveConfiguration.Builder()
                            .setTargetOffsetMs(10_000)
                            .build());
                }
                player.setMediaItem(mb.build());
                player.prepare();
                player.play();
            });
        }
        @JavascriptInterface public void stop() {
            ui.post(() -> {
                player.stop();
                player.clearMediaItems();
                pv.setVisibility(View.GONE);
                keepScreenOn(false);
            });
        }
        @JavascriptInterface public void pause() { ui.post(() -> player.pause()); }
        @JavascriptInterface public void resume() { ui.post(() -> player.play()); }
        @JavascriptInterface public void seek(final long ms) { ui.post(() -> player.seekTo(Math.max(0, ms))); }
        @JavascriptInterface public void setLang(final String lang) {
            ui.post(() -> player.setTrackSelectionParameters(
                    player.getTrackSelectionParameters().buildUpon().setPreferredAudioLanguage(lang).build()));
        }
        @JavascriptInterface public void setSpeed(final double speed) {
            ui.post(() -> player.setPlaybackSpeed((float) Math.max(0.25, Math.min(4.0, speed))));
        }
        /* The page asks for the tracks; the player is read on the main thread and the answer handed back. */
        @JavascriptInterface public String getTracks() {
            final String[] out = new String[1];
            final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            ui.post(() -> { out[0] = tracksJson(); done.countDown(); });
            try { done.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (InterruptedException e) { return ""; }
            return out[0] == null ? "" : out[0];
        }
        @JavascriptInterface public void setAudio(final int n) {
            ui.post(() -> {
                Pick p = nth(C.TRACK_TYPE_AUDIO, n);
                if (p == null) return;
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .setOverrideForType(new TrackSelectionOverride(p.g.getMediaTrackGroup(), p.i)).build());
            });
        }
        /* n = -1 turns subtitles off; 0, 1, ... picks one */
        @JavascriptInterface public void setText(final int n) {
            ui.post(() -> {
                TrackSelectionParameters.Builder b = player.getTrackSelectionParameters().buildUpon();
                if (n < 0) {
                    b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
                } else {
                    Pick p = nth(C.TRACK_TYPE_TEXT, n);
                    if (p == null) return;
                    b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(new TrackSelectionOverride(p.g.getMediaTrackGroup(), p.i));
                }
                player.setTrackSelectionParameters(b.build());
            });
        }
        @JavascriptInterface public void exit() { ui.post(MainActivity.this::finish); }
        /* Called from index.html's focusin/focusout listener whenever a text <input> gains or
           loses real focus -- see typingActive's own comment for why this needs to exist at all. */
        @JavascriptInterface public void setTyping(final boolean on) { typingActive = on; }
    }
}
