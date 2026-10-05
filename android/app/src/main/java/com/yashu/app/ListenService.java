package com.yashu.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Yashu's ears. Runs as a foreground service (with a notification), so it keeps listening
 * for the wake phrase even when the app is closed and the screen is off.
 * The brain, memory, voice and search all live on your Render server.
 */
public class ListenService extends Service {

    static final String ACTION_STOP = "com.yashu.app.STOP";

    private enum St { SLEEP, AWAKE, BUSY, SPEAKING }

    private static final int FL = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    // "yashu", "yashu aik na", "ayy bala aik na", "hello shhona"
    private static final Pattern WAKE = Pattern.compile(
            "(यश\\S*|yash\\S*|(अय्य|ए|ayy?|ae)\\s*(बाळा|बाला|bala)\\s*(ऐक\\S*|एक\\S*|aik\\S*|aek\\S*)(\\s*(ना|na))?"
                    + "|(hello|hallo|हॅलो|हेलो|हलो)\\s*(शोना|सोना|sh?h?ona))", FL);
    private static final Pattern LEAD = Pattern.compile(
            "^[\\s,.!?]*(ऐक\\S*|एक\\S*|aik\\S*|aek\\S*)\\s*(ना|na)?", FL);
    // "bas zal bala aata"
    private static final Pattern STOP = Pattern.compile(
            "(बस|बास|bas|baas)\\s*(झाल\\S*|zal\\S*|zhal\\S*|jhal\\S*)", FL);

    private static final Map<String, String> LANGS = new HashMap<>();
    private static final Map<String, String> GREET = new HashMap<>();
    private static final Map<String, String> STOP_SAY = new HashMap<>();

    static {
        LANGS.put("mr", "mr-IN");
        LANGS.put("hi", "hi-IN");
        LANGS.put("en", "en-IN");
        LANGS.put("gu", "gu-IN");
        LANGS.put("ta", "ta-IN");
        LANGS.put("te", "te-IN");
        LANGS.put("kn", "kn-IN");
        LANGS.put("bn", "bn-IN");
        GREET.put("mr", "हो, बोल ना");
        GREET.put("hi", "हाँ, बोलो ना");
        GREET.put("en", "Yes, tell me");
        STOP_SAY.put("mr", "ठीक आहे बाळा, थांबवलं");
        STOP_SAY.put("hi", "ठीक है बाबू, रोक दिया");
        STOP_SAY.put("en", "Okay baby, stopped");
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final Map<String, String> ttsCache = new HashMap<>();

    private SharedPreferences prefs;
    private PowerManager.WakeLock wl;
    private SpeechRecognizer rec;
    private WebView songView;
    private St state = St.SLEEP;
    private String lang = "mr";
    private boolean started = false, listening = false, songOn = false;

    private final Runnable restartR = this::startListening;
    private final Runnable idleR = () -> {
        if (state == St.AWAKE) {
            state = St.SLEEP;
            updateNotif("“यशु ऐक ना” म्हण");
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (started) return START_NOT_STICKY;
        started = true;
        prefs = getSharedPreferences("yashu", MODE_PRIVATE);

        NotificationChannel ch = new NotificationChannel("yashu", "Yashu", NotificationManager.IMPORTANCE_LOW);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        Notification n = buildNotif("“यशु ऐक ना” म्हण");
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(1, n);
        }

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yashu:listen");
        wl.acquire();

        beepMute(true);
        state = St.SLEEP;
        startListening();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        destroyRec();
        if (songView != null) {
            songView.destroy();
            songView = null;
        }
        if (wl != null && wl.isHeld()) wl.release();
        beepMute(false);
        net.shutdownNow();
        super.onDestroy();
    }

    /* ---------------- listening ---------------- */

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle p) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float v) { }
        @Override public void onBufferReceived(byte[] b) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onPartialResults(Bundle b) { }
        @Override public void onEvent(int t, Bundle b) { }

        @Override
        public void onResults(Bundle b) {
            listening = false;
            ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            String text = (r == null || r.isEmpty()) ? "" : r.get(0).trim();
            if (!text.isEmpty()) onHeard(text);
            if (state == St.SLEEP || state == St.AWAKE) restart(250);
        }

        @Override
        public void onError(int err) {
            listening = false;
            if (err == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                updateNotif("मायक्रोफोनची परवानगी नाही");
                stopSelf();
                return;
            }
            if (err == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || err == SpeechRecognizer.ERROR_CLIENT) {
                destroyRec();
                restart(1200);
            } else if (err == SpeechRecognizer.ERROR_NETWORK || err == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) {
                restart(2500);
            } else {
                restart(300);   // no match / silence: just listen again
            }
        }
    };

    private void restart(long delayMs) {
        main.removeCallbacks(restartR);
        main.postDelayed(restartR, delayMs);
    }

    private void startListening() {
        if ((state != St.SLEEP && state != St.AWAKE) || listening) return;
        try {
            if (rec == null) {
                rec = SpeechRecognizer.createSpeechRecognizer(this);
                rec.setRecognitionListener(listener);
            }
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGS.containsKey(lang) ? LANGS.get(lang) : "mr-IN");
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            listening = true;
            rec.startListening(i);
        } catch (Exception e) {
            listening = false;
            destroyRec();
            restart(2000);
        }
    }

    private void stopListening() {
        main.removeCallbacks(restartR);
        listening = false;
        if (rec != null) {
            try { rec.cancel(); } catch (Exception ignored) { }
        }
    }

    private void destroyRec() {
        listening = false;
        if (rec != null) {
            try { rec.destroy(); } catch (Exception ignored) { }
            rec = null;
        }
    }

    /* ---------------- conversation ---------------- */

    private void onHeard(String text) {
        if (songOn && STOP.matcher(text).find()) {
            killSong();
            String s = STOP_SAY.get(lang);
            say(s != null ? s : STOP_SAY.get("mr"));
            return;
        }
        if (state == St.SLEEP) {
            Matcher m = WAKE.matcher(text);
            if (!m.find()) return;
            String rest = m.replaceFirst("");
            rest = LEAD.matcher(rest).replaceFirst("").replaceFirst("^[\\s,.!?]+", "").trim();
            if (rest.length() > 3) {
                send(rest);
            } else {
                String g = GREET.get(lang);
                say(g != null ? g : GREET.get("en"));
            }
        } else if (state == St.AWAKE) {
            send(text);
        }
    }

    private void resetIdle() {
        main.removeCallbacks(idleR);
        main.postDelayed(idleR, 30000);
    }

    private void afterSpeak() {
        state = St.AWAKE;
        updateNotif("ऐकतेय…");
        resetIdle();
        startListening();
    }

    private void send(String text) {
        main.removeCallbacks(idleR);
        state = St.BUSY;
        stopListening();
        updateNotif("विचार करतेय…");
        net.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("text", text);
                JSONObject d = api("/api/chat", body);
                main.post(() -> handleReply(d));
            } catch (Exception e) {
                main.post(() -> {
                    updateNotif("समस्या: " + e.getMessage());
                    afterSpeak();
                });
            }
        });
    }

    private void handleReply(JSONObject d) {
        String l = d.optString("language", lang);
        if (LANGS.containsKey(l)) lang = l;
        JSONObject act = d.optJSONObject("action");
        String audio = d.isNull("audio") ? "" : d.optString("audio", "");
        state = St.SPEAKING;
        updateNotif("बोलतेय…");
        duck(true);
        Runnable after = () -> {
            duck(false);
            if (act != null && "play_song".equals(act.optString("type"))) {
                playSong(act.optString("video_id"));
            }
            afterSpeak();
        };
        if (audio.isEmpty()) after.run();
        else playB64(audio, after);
    }

    private void say(String text) {
        state = St.SPEAKING;
        stopListening();
        duck(true);
        final String lg = lang;
        final String key = lg + "|" + text;
        final Runnable after = () -> {
            duck(false);
            afterSpeak();
        };
        String cached = ttsCache.get(key);
        if (cached != null) {
            playB64(cached, after);
            return;
        }
        net.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("text", text);
                body.put("language", lg);
                body.put("emotion", "soft");
                String a = api("/api/tts", body).optString("audio", "");
                ttsCache.put(key, a);
                main.post(() -> playB64(a, after));
            } catch (Exception e) {
                main.post(after);
            }
        });
    }

    /* ---------------- audio ---------------- */

    private void playB64(String b64, Runnable done) {
        final boolean[] once = {false};
        try {
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            final File f = File.createTempFile("y", ".mp3", getCacheDir());
            try (FileOutputStream o = new FileOutputStream(f)) {
                o.write(bytes);
            }
            final MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            mp.setDataSource(f.getAbsolutePath());
            final Runnable fin = () -> {
                if (once[0]) return;
                once[0] = true;
                try { mp.release(); } catch (Exception ignored) { }
                f.delete();
                done.run();
            };
            mp.setOnCompletionListener(m -> fin.run());
            mp.setOnErrorListener((m, what, extra) -> {
                fin.run();
                return true;
            });
            mp.setOnPreparedListener(MediaPlayer::start);
            mp.prepareAsync();
        } catch (Exception e) {
            if (!once[0]) {
                once[0] = true;
                done.run();
            }
        }
    }

    /* ---------------- songs (hidden WebView playing YouTube) ---------------- */

    public class Bridge {
        @JavascriptInterface
        public void ended() {
            main.post(ListenService.this::killSong);
        }
    }

    private void playSong(String id) {
        if (id == null || id.isEmpty()) return;
        if (songView == null) {
            songView = new WebView(getApplicationContext());
            WebSettings s = songView.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            songView.setWebViewClient(new WebViewClient());
            songView.addJavascriptInterface(new Bridge(), "Yashu");
        }
        songView.onResume();
        songView.resumeTimers();
        songView.loadUrl(baseUrl() + "/player?id=" + Uri.encode(id));
        songOn = true;
    }

    private void killSong() {
        songOn = false;
        if (songView != null) {
            songView.loadUrl("about:blank");
            songView.onPause();
        }
    }

    private void duck(boolean on) {
        if (songOn && songView != null) {
            songView.evaluateJavascript("setVol(" + (on ? 15 : 100) + ")", null);
        }
    }

    /* ---------------- helpers ---------------- */

    private void beepMute(boolean mute) {
        if (prefs == null || !prefs.getBoolean("mute", false)) return;
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            int dir = mute ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE;
            am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, dir, 0);
            am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, dir, 0);
        } catch (Exception ignored) { }
    }

    private String baseUrl() {
        String base = prefs.getString("url", "").trim();
        if (!base.startsWith("http")) base = "https://" + base;
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base;
    }

    private JSONObject api(String path, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(baseUrl() + path).openConnection();
        c.setConnectTimeout(40000);
        c.setReadTimeout(90000);
        c.setRequestProperty("X-App-Password", prefs.getString("pw", ""));
        if (body != null) {
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setDoOutput(true);
            try (OutputStream o = c.getOutputStream()) {
                o.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = c.getResponseCode();
        if (code == 401) throw new Exception("पासवर्ड चुकला");
        if (code >= 400) throw new Exception("server " + code);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new JSONObject(bo.toString("UTF-8"));
        }
    }

    private Notification buildNotif(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, ListenService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "yashu")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("यशु")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "थांबव", stop)
                .build();
    }

    private void updateNotif(String text) {
        try {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(1, buildNotif(text));
        } catch (Exception ignored) { }
    }
}
