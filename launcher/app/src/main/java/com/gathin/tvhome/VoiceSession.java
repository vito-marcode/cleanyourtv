package com.gathin.tvhome;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The mic key's voice command (app_keys target "voice", e.g. "KEYCODE_F10:voice"), in whatever app is in front.
 * A card drawn by the accessibility service (no activity switch: the app underneath keeps playing) listens with the
 * TV's speech recogniser in Italian, then {@link VoiceCommands} decides: a channel (asked to Canali TV's provider,
 * tuned with its PLAY action), a YouTube search (opened in SmartTube), an app by name, or an action (Home, Back,
 * mute, volume, settings). Press (tap or hold) and speak: the recogniser ends the phrase on silence, a second press
 * ends it at once, Back cancels. The release never ends it: the TIM v2 remote reports the mic key's release about a
 * second after the press even while it is still held. A press before the recogniser is ready is ignored (the Google
 * app may have been killed for memory and take a moment to start again).
 * Needs RECORD_AUDIO, granted with pm grant. Nothing is kept between commands.
 */
final class VoiceSession implements RecognitionListener {
    static final String TAG = "TvHomeVoice";
    static final String TARGET = "voice";

    /** Canali TV: resolves a phrase to a channel (?q=) and tunes it (PLAY, extra number). */
    private static final String CHANNELS_PKG = "it.mdv.dttchannels";
    private static final Uri CHANNELS_URI = Uri.parse("content://it.mdv.dttchannels/channels");
    /** Where a video search goes: the first installed of these. */
    private static final String[] VIDEO_APPS = {"org.smarttube.stable", "org.smarttube.beta",
            "com.teamsmart.videomanager.tv", "com.liskovsoft.smarttubetv.beta", "com.liskovsoft.smarttubetv",
            "com.google.android.youtube.tv"};

    /** Where a music search goes (spotify:search:). */
    private static final String[] MUSIC_APPS = {"com.spotify.tv.android", "com.spotify.music"};

    private static VoiceSession current;
    private static long downAt;
    /** The last key event handled: HomeKeyService and InputKeyService both get every key when both are enabled. */
    private static long lastEventTime = -1;
    private static int lastAction = -1;

    private final AccessibilityService svc;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WindowManager wm;
    private final float dp;
    private View card;
    private TextView title, hint;
    private MicView mic;
    private SpeechRecognizer recognizer;
    private boolean listening;
    /** onReadyForSpeech came: stopListening() before it cancels the request with nothing heard. */
    private boolean ready;
    /** Volume lowered while listening (-1: untouched), and the level it was lowered to. */
    private int duckedFrom = -1, duckedTo = -1;
    private final Runnable watchdog = this::close;

    private VoiceSession(AccessibilityService svc) {
        this.svc = svc;
        this.wm = (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE);
        this.dp = svc.getResources().getDisplayMetrics().density;
    }

    static boolean active() {
        return current != null;
    }

    /** The mic key from InputKeyService: a press starts a session, or ends the phrase of the one listening. */
    static boolean onKey(AccessibilityService svc, KeyEvent event) {
        if (event.getEventTime() == lastEventTime && event.getAction() == lastAction) return true;   // the other service's copy
        lastEventTime = event.getEventTime();
        lastAction = event.getAction();
        Log.i(TAG, "key " + KeyEvent.keyCodeToString(event.getKeyCode()) + (event.getAction() == KeyEvent.ACTION_DOWN
                ? " down repeat=" + event.getRepeatCount() : " up after " + (event.getEventTime() - downAt) + " ms"));
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            downAt = event.getEventTime();
            toggle(svc);
        }
        return true;
    }

    /** Starts a session, or ends the phrase of the one listening. Also the "voice" action word (RemoteKeys). */
    static void toggle(AccessibilityService svc) {
        if (current != null) {
            if (current.listening && current.ready) current.recognizer.stopListening();
            return;
        }
        current = new VoiceSession(svc);
        current.start();
    }

    /** Back while the card is up. */
    static void cancel() {
        if (current != null) current.close();
    }

    // ---------------------------------------------------------------- session

    /** While listening, the TV's sound down to a quarter, so it doesn't cover the voice. */
    private void duck() {
        AudioManager audio = svc.getSystemService(AudioManager.class);
        if (audio == null) return;
        int vol = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (vol <= 2) return;
        duckedFrom = vol;
        duckedTo = Math.max(1, vol / 4);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, duckedTo, 0);
    }

    /** Back to the volume before listening, unless the user changed it meanwhile. */
    private void unduck() {
        if (duckedFrom < 0) return;
        AudioManager audio = svc.getSystemService(AudioManager.class);
        if (audio != null && audio.getStreamVolume(AudioManager.STREAM_MUSIC) == duckedTo) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, duckedFrom, 0);
        }
        duckedFrom = -1;
    }

    private void start() {
        show();
        main.postDelayed(watchdog, 20_000);
        if (svc.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            done("Microfono non autorizzato", "pm grant com.gathin.tvhome android.permission.RECORD_AUDIO", 4000);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(svc)) {
            done("Riconoscimento vocale non disponibile", null, 3000);
            return;
        }
        ComponentName google = googleRecognizer(svc);
        recognizer = google != null ? SpeechRecognizer.createSpeechRecognizer(svc, google)
                : SpeechRecognizer.createSpeechRecognizer(svc);
        recognizer.setRecognitionListener(this);
        duck();
        recognizer.startListening(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, svc.getPackageName()));
        listening = true;
    }

    /**
     * The Google app's online recogniser (com.google.android.katniss), whose class name differs between versions,
     * when installed. Some firmware makes Google TTS's on-device recogniser the default, which may lack Italian.
     */
    private static ComponentName googleRecognizer(Context ctx) {
        for (ResolveInfo ri : ctx.getPackageManager().queryIntentServices(
                new Intent(RecognitionService.SERVICE_INTERFACE), 0)) {
            if (ri.serviceInfo != null && "com.google.android.katniss".equals(ri.serviceInfo.packageName)) {
                return new ComponentName(ri.serviceInfo.packageName, ri.serviceInfo.name);
            }
        }
        return null;
    }

    private void stopRecognizer() {
        listening = false;
        unduck();                                       // the sound comes back as soon as listening ends
        if (recognizer != null) {
            recognizer.setRecognitionListener(null);
            recognizer.destroy();
            recognizer = null;
        }
    }

    /** Shows a last line, then takes the card down. */
    private void done(String line, String sub, long ms) {
        stopRecognizer();
        if (title != null) title.setText(line);
        if (hint != null) {
            hint.setText(sub == null ? "" : sub);
            hint.setVisibility(sub == null ? View.GONE : View.VISIBLE);
        }
        if (mic != null) mic.setLevel(0f);
        main.removeCallbacks(watchdog);
        main.postDelayed(this::close, ms);
    }

    private void close() {
        main.removeCallbacksAndMessages(null);
        stopRecognizer();
        if (card != null) {
            try {
                wm.removeView(card);
            } catch (IllegalArgumentException ignored) {
            }
            card = null;
        }
        if (current == this) current = null;
    }

    // ---------------------------------------------------------------- recogniser

    @Override public void onPartialResults(Bundle b) {
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list != null && !list.isEmpty() && !list.get(0).isEmpty()) {
            title.setText("«" + list.get(0) + "»");
            hint.setText("Indietro per annullare");
        }
    }

    @Override public void onResults(Bundle b) {
        ArrayList<String> heard = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        stopRecognizer();
        if (heard == null || heard.isEmpty()) {
            done("Non ho sentito niente", null, 2000);
            return;
        }
        Log.i(TAG, "heard " + heard);
        title.setText("«" + heard.get(0) + "»");
        hint.setText("…");
        // the provider and the app list can take a moment: off the main thread
        new Thread(() -> {
            Result r = decide(heard);
            main.post(() -> {
                if (current != this) return;
                Log.i(TAG, "-> " + r.line);
                if (r.run != null) r.run.run();
                done(r.line, r.sub, r.ok ? 1500 : 2500);
            });
        }, "voice-command").start();
    }

    @Override public void onError(int error) {
        Log.i(TAG, "error " + error);
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH: case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                done("Non ho sentito niente", null, 2000); break;
            case SpeechRecognizer.ERROR_NETWORK: case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: case SpeechRecognizer.ERROR_SERVER:
                done("Riconoscimento vocale non raggiungibile", null, 2500); break;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                done("Microfono non autorizzato", null, 3000); break;
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: case SpeechRecognizer.ERROR_AUDIO:
                done("Microfono occupato, riprova", null, 2000); break;
            default:
                done("Riconoscimento vocale non riuscito", null, 2000);
        }
    }

    @Override public void onRmsChanged(float rmsdB) {
        if (mic != null) mic.setLevel(Math.max(0f, Math.min(1f, (rmsdB + 2f) / 12f)));
    }

    @Override public void onReadyForSpeech(Bundle params) {
        ready = true;
        if (title != null && !title.getText().toString().startsWith("«")) title.setText("Ti ascolto…");
    }
    @Override public void onBeginningOfSpeech() { }
    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEndOfSpeech() { listening = false; }
    @Override public void onEvent(int eventType, Bundle params) { }

    // ---------------------------------------------------------------- commands

    private static final class Result {
        final String line, sub;
        final Runnable run;
        final boolean ok;

        Result(String line, String sub, Runnable run, boolean ok) {
            this.line = line;
            this.sub = sub;
            this.run = run;
            this.ok = ok;
        }
    }

    /** Worker thread: what the phrase asks for, and the main-thread step that does it. */
    private Result decide(List<String> heard) {
        String first = VoiceCommands.bestPhrase(heard);
        VoiceCommands.Command cmd = VoiceCommands.parse(first);
        // "<something> su <app>": that app, searching for it when the app takes a search (before any channel lookup)
        // ("cerca doc su raiplay" too: a "cerca" phrase is a YouTube search only when it names YouTube or no app)
        boolean namesYoutube = VoiceCommands.normalize(first).matches(".*\\b(?:youtube|you tube|smarttube|smart tube)\\b.*");
        if (cmd.kind == VoiceCommands.Kind.NAME || cmd.kind == VoiceCommands.Kind.WATCH || cmd.kind == VoiceCommands.Kind.OPEN
                || cmd.kind == VoiceCommands.Kind.WEB_SEARCH || (cmd.kind == VoiceCommands.Kind.VIDEO_SEARCH && !namesYoutube)) {
            String[] in = VoiceCommands.inApp(first);
            VoiceCommands.App app = in == null ? null : VoiceCommands.findApp(in[1], apps());
            if (app != null) return searchIn(app, in[0]);
        }
        switch (cmd.kind) {
            case ACTION:
                return action(cmd.arg);
            case VIDEO_SEARCH: {
                String pkg = firstInstalled(VIDEO_APPS);
                if (pkg == null) return fail("Nessuna app YouTube installata");
                Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(VoiceCommands.youtubeSearchUrl(cmd.arg)))
                        .setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                return new Result("Cerco «" + cmd.arg + "»", "su YouTube", () -> start(view), true);
            }
            case WEB_SEARCH: {
                // the TV's Google search (Katniss), results from every app, as the Google key gives
                Intent search = new Intent("android.search.action.GLOBAL_SEARCH")
                        .putExtra(android.app.SearchManager.QUERY, cmd.arg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (svc.getPackageManager().resolveActivity(search, 0) == null) return fail("Ricerca non disponibile");
                return new Result("Cerco «" + cmd.arg + "»", "con Google", () -> start(search), true);
            }
            case MUSIC_SEARCH: {
                String pkg = firstInstalled(MUSIC_APPS);
                if (pkg == null) return fail("Spotify non è installato");
                Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(cmd.arg)))
                        .setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                return new Result("Cerco «" + cmd.arg + "»", "su Spotify", () -> start(view), true);
            }
            case OPEN: {
                VoiceCommands.App app = VoiceCommands.findApp(cmd.arg, apps());
                if (app != null) return openApp(app);
                List<String> asChannel = new ArrayList<>();
                for (String h : heard) asChannel.add(h);
                Result ch = channel(asChannel);
                return ch != null ? ch : fail("Non trovo «" + cmd.arg + "»");
            }
            case NAME: {
                VoiceCommands.App app = VoiceCommands.findApp(cmd.arg, apps());
                if (app != null) return openApp(app);
                Result ch = channel(heard);
                return ch != null ? ch : fail("Non ho capito «" + first + "»");
            }
            case WATCH: {
                Result ch = channel(heard);
                if (ch != null) return ch;
                VoiceCommands.App app = VoiceCommands.findApp(VoiceCommands.withoutVerb(first), apps());
                if (app != null) return openApp(app);
                return fail("Non ho capito «" + first + "»");
            }
            default:
                return fail("Non ho capito");
        }
    }

    private static Result fail(String line) {
        return new Result(line, null, null, false);
    }

    private Result action(String word) {
        if (word.startsWith("volume_set:")) {
            int pct = Integer.parseInt(word.substring("volume_set:".length()));
            AudioManager audio = svc.getSystemService(AudioManager.class);
            return new Result("Volume al " + pct + "%", null, () -> {
                if (audio == null) return;
                int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.max(1, Math.round(max * pct / 100f)),
                        AudioManager.FLAG_SHOW_UI);
            }, true);
        }
        switch (word) {
            case "volume_up": case "volume_down": {
                int dir = word.equals("volume_up") ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER;
                AudioManager audio = svc.getSystemService(AudioManager.class);
                return new Result(word.equals("volume_up") ? "Alzo il volume" : "Abbasso il volume", null, () -> {
                    if (audio == null) return;
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI);
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI);
                }, true);
            }
            case "home": return new Result("Home", null, () -> RemoteKeys.launch(svc, "home"), true);
            case "back": return new Result("Indietro", null, () -> RemoteKeys.launch(svc, "back"), true);
            case "mute": return new Result("Audio", "muto / riattivato", () -> RemoteKeys.launch(svc, "mute"), true);
            case "settings": return new Result("Impostazioni", null, () -> RemoteKeys.launch(svc, "settings"), true);
            case "wifi": return new Result("Impostazioni Wi-Fi", null, () -> RemoteKeys.launch(svc, "wifi"), true);
            case "screensaver": return new Result("Salvaschermo", null, () -> RemoteKeys.launch(svc, "screensaver"), true);
            case "recents": return new Result("App recenti", null, () -> RemoteKeys.launch(svc, "recents"), true);
            default: return fail("Non ho capito");
        }
    }

    /**
     * Search links for apps without Android's standard search, "%s" the encoded text. Netflix: nflx:// takes any path
     * (its https filters do not take /search); not checked against a subscription yet.
     */
    private static final String[][] APP_SEARCH_LINKS = {
            {"com.netflix.ninja", "nflx://www.netflix.com/search?q=%s"},
    };

    /** Opens the app on a search for the text when it takes Android's standard search (RaiPlay does) or has a known
     *  search link (Netflix); otherwise opens it. */
    private Result searchIn(VoiceCommands.App app, String query) {
        for (String[] link : APP_SEARCH_LINKS) {
            if (!link[0].equals(app.pkg)) continue;
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(String.format(link[1], Uri.encode(query))))
                    .setPackage(app.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (svc.getPackageManager().resolveActivity(view, 0) != null)
                return new Result("Cerco «" + query + "»", "su " + app.label, () -> start(view), true);
        }
        Intent search = new Intent(Intent.ACTION_SEARCH).setPackage(app.pkg)
                .putExtra(android.app.SearchManager.QUERY, query).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (svc.getPackageManager().queryIntentActivities(search, 0).isEmpty()) return openApp(app);
        return new Result("Cerco «" + query + "»", "su " + app.label, () -> start(search), true);
    }

    private Result openApp(VoiceCommands.App app) {
        return new Result("Apro " + app.label, null, () -> RemoteKeys.launch(svc, app.pkg), true);
    }

    /** Asks Canali TV which channel the phrase names (every hypothesis, first match wins); null when none or no app. */
    private Result channel(List<String> heard) {
        Uri.Builder uri = CHANNELS_URI.buildUpon();
        for (String h : heard) uri.appendQueryParameter("q", h);
        try (Cursor c = svc.getContentResolver().query(uri.build(), new String[]{"number", "name"}, null, null, null)) {
            if (c == null || !c.moveToFirst()) return null;
            String number = c.getString(c.getColumnIndexOrThrow("number"));
            String name = c.getString(c.getColumnIndexOrThrow("name"));
            Intent play = new Intent(CHANNELS_PKG + ".PLAY").setPackage(CHANNELS_PKG)
                    .putExtra("number", number).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return new Result(name, "canale " + number, () -> start(play), true);
        } catch (RuntimeException e) {       // Canali TV not installed, or its provider refused
            Log.w(TAG, "channel lookup", e);
            return null;
        }
    }

    private void start(Intent intent) {
        try {
            svc.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "start", e);
        }
    }

    private String firstInstalled(String[] pkgs) {
        PackageManager pm = svc.getPackageManager();
        for (String p : pkgs) {
            try {
                if (pm.getApplicationInfo(p, 0).enabled) return p;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return null;
    }

    /** The apps with a TV or phone launcher entry, as tiles show them. */
    private List<VoiceCommands.App> apps() {
        PackageManager pm = svc.getPackageManager();
        List<VoiceCommands.App> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(svc.getPackageName()) || !seen.add(pkg)) continue;
                out.add(new VoiceCommands.App(pkg, String.valueOf(ri.loadLabel(pm))));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- card

    private int px(float v) {
        return Math.round(v * dp);
    }

    private void show() {
        LinearLayout row = new LinearLayout(svc);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(10), px(10), px(28), px(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEB15181E);
        bg.setCornerRadius(px(60));
        bg.setStroke(px(1), 0x33FFFFFF);
        row.setBackground(bg);
        row.setMinimumWidth(px(420));

        mic = new MicView(svc);
        row.addView(mic, new LinearLayout.LayoutParams(px(56), px(56)));

        LinearLayout texts = new LinearLayout(svc);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tl.leftMargin = px(16);
        title = new TextView(svc);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(UiFont.get(svc, R.string.font_medium));
        title.setMaxLines(2);
        title.setMaxWidth(px(760));
        title.setText("Un attimo…");
        hint = new TextView(svc);
        hint.setTextColor(0xB3FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        hint.setTypeface(UiFont.get(svc, R.string.font_regular));
        hint.setText("Parla ora: «Metti Rai 2» · «Cerca gatti su YouTube» · «Apri Netflix»");
        texts.addView(title);
        texts.addView(hint);
        row.addView(texts, tl);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = px(56);
        try {
            wm.addView(row, lp);
            card = row;
        } catch (RuntimeException e) {
            Log.w(TAG, "card", e);
        }
    }

    /** White disc with a mic glyph and a halo that follows the input level. */
    private static final class MicView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float level;

        MicView(Context ctx) {
            super(ctx);
        }

        void setLevel(float v) {
            level = v;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2, rad = Math.min(w, h) / 2;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb((int) (255 * (.16f + .24f * level)), 255, 255, 255));
            c.drawCircle(cx, cy, rad * (.80f + .20f * level), paint);
            paint.setColor(Color.WHITE);
            c.drawCircle(cx, cy, rad * .62f, paint);
            float g = rad * .62f;                       // glyph box: the inner disc
            paint.setColor(0xFF0D0F13);
            r.set(cx - g * .22f, cy - g * .58f, cx + g * .22f, cy + g * .12f);
            c.drawRoundRect(r, g * .22f, g * .22f, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(g * .1f);
            r.set(cx - g * .38f, cy - g * .34f, cx + g * .38f, cy + g * .3f);
            c.drawArc(r, 0, 180, false, paint);
            c.drawLine(cx, cy + g * .3f, cx, cy + g * .5f, paint);
        }
    }
}
