package com.tyre.redcarpetstreams;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

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
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

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
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        pv = new PlayerView(this);
        pv.setUseController(false);
        pv.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        pv.setVisibility(View.GONE);
        root.addView(pv, new FrameLayout.LayoutParams(-1, -1));

        web = makeWebView();
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        web.requestFocus();

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setUserAgent("Mozilla/5.0 (Linux; Android) RedCarpetStreams")
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000);
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(this, http)))
                .build();
        pv.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int st) {
                if (st == Player.STATE_BUFFERING) send("waiting");
                else if (st == Player.STATE_READY && !metaSent) { metaSent = true; send("loadedmetadata"); }
                else if (st == Player.STATE_ENDED) send("ended");
            }
            @Override public void onIsPlayingChanged(boolean playing) {
                send(playing ? "playing" : "pause");
                keepScreenOn(playing);
            }
            @Override public void onPlayerError(PlaybackException e) { send("error"); }
        });
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
        w.setWebChromeClient(new WebChromeClient());
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
        int code = map(e.getKeyCode());
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

    /* Methods the web app can call as window.RCS.*  */
    class Bridge {
        @JavascriptInterface public void play(final String url) {
            ui.post(() -> {
                metaSent = false;
                player.setPlaybackSpeed(1f);
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .clearOverrides().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
                pv.setVisibility(View.VISIBLE);
                player.setMediaItem(MediaItem.fromUri(url));
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
    }
}
