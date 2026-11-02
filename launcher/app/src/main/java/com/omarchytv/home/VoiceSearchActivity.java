package com.omarchytv.home;

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * Mic button → search in the app you're in. The Google TV Streamer remote's mic key arrives as KEYCODE_ASSIST, which
 * Android only ever hands to the Assistant; KeyMapService catches it (assets/remotes.json, action "voice-search"),
 * notes the app in front (usage access) and opens this small panel: it listens with the system speech recognizer (the
 * same one YouTube's own voice search uses, fed by the remote's mic), then opens that app's search for the words:
 * YouTube results, Stremio search, Twitch search, Netflix search; anything else (OmarchyTV's home) searches YouTube.
 * Nothing is kept: the words go only to the recognizer and to the target app.
 */
public class VoiceSearchActivity extends Activity {

    static final String YOUTUBE = "com.google.android.youtube.tv";
    static final String EXTRA_TARGET = "com.omarchytv.home.extra.SEARCH_TARGET";

    /** {package, label, search URI prefix (the query is appended URI-encoded)}; verified on a Google TV Streamer. */
    private static final String[][] TARGETS = {
            {YOUTUBE, "YouTube", "https://www.youtube.com/results?search_query="},
            {"com.stremio.one", "Stremio", "stremio:///search?search="},
            {"tv.twitch.android.app", "Twitch", "twitch://search?"},
            {"com.netflix.ninja", "Netflix", "nflx://www.netflix.com/search?q="},
    };
    private String[] target = TARGETS[0];
    private static final int REQ_MIC = 1;
    private static final long FAIL_SHOW_MS = 1_800L;

    private SpeechRecognizer recognizer;
    private TextView status, heard;
    private boolean done;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        int pad = dp(28);
        box.setPadding(pad, pad, pad, pad);
        box.setMinimumWidth(dp(520));
        box.setBackgroundColor(0xF01A1B26);
        status = text(26, 0xFFC0CAF5);
        heard = text(22, 0xFF7AA2F7);
        String fg = getIntent().getStringExtra(EXTRA_TARGET);
        if (fg == null) fg = foregroundApp(this);   // opened by the mic key as the Assistant: the app we came from
        for (String[] t : TARGETS) if (t[0].equals(fg)) target = t;
        status.setText("🎤  " + target[1] + " search: listening…");
        box.addView(status);
        box.addView(heard);
        setContentView(box);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        } else {
            listen();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_MIC) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) listen();
        else fail("Microphone permission is needed for voice search");
    }

    private void listen() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            fail("No speech recognizer on this TV");
            return;
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { if (!done) status.setText("🔎  Searching " + target[1] + "…"); }
            @Override public void onEvent(int eventType, Bundle params) { }

            @Override
            public void onError(int error) {
                fail(error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        ? "Didn't catch that" : "Voice search failed (" + error + ")");
            }

            @Override
            public void onPartialResults(Bundle partial) {
                String t = best(partial);
                if (t != null && !done) heard.setText(t);
            }

            @Override
            public void onResults(Bundle results) {
                String t = best(results);
                if (t == null) fail("Didn't catch that");
                else search(t);
            }
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        recognizer.startListening(i);
    }

    private static String best(Bundle b) {
        ArrayList<String> l = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (l == null || l.isEmpty() || l.get(0) == null || l.get(0).trim().isEmpty()) return null;
        return l.get(0).trim();
    }

    /** The target app's search for the words (YouTube: the same screen, box filled in, as its own voice search). */
    private void search(String query) {
        if (done) return;
        done = true;
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(target[2] + Uri.encode(query)))
                .setPackage(target[0])
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            done = false;
            fail(target[1] + " is not available");
            return;
        }
        finish();
    }

    /**
     * The app in front, from usage events (needs the GET_USAGE_STATS app-op: appops set com.omarchytv.home
     * GET_USAGE_STATS allow). Called by KeyMapService before this panel opens, since the panel then is in front.
     * Null without usage access or when nothing resumed in the last hour.
     */
    static String foregroundApp(Context c) {
        try {
            AppOpsManager ops = c.getSystemService(AppOpsManager.class);
            @SuppressWarnings("deprecation")
            int mode = ops == null ? AppOpsManager.MODE_IGNORED
                    : ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
            if (mode != AppOpsManager.MODE_ALLOWED) return null;
            UsageStatsManager usm = c.getSystemService(UsageStatsManager.class);
            if (usm == null) return null;
            long now = System.currentTimeMillis();
            UsageEvents ev = usm.queryEvents(now - 3600_000L, now);
            UsageEvents.Event e = new UsageEvents.Event();
            String fg = null;
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                // Skip ourselves: as the Assistant this panel may already be the newest foreground event.
                if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND && !c.getPackageName().equals(e.getPackageName()))
                    fg = e.getPackageName();
            }
            return fg;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void fail(String message) {
        if (done) return;
        done = true;
        status.setText(message);
        heard.setText("");
        status.postDelayed(this::finish, FAIL_SHOW_MS);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isFinishing()) finish();   // left (Home, another app): stop listening
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) {
            recognizer.cancel();
            recognizer.destroy();
            recognizer = null;
        }
        super.onDestroy();
    }

    private TextView text(int sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(6), 0, dp(6));
        return t;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
