package com.example.smarthome;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import java.util.ArrayList;

/** Native Android speech bridge with serialized restart/recovery. */
public final class VoiceBridge implements RecognitionListener {
    private final Context context;
    private final WebView webView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private boolean running;
    private boolean stopping;
    private boolean listening;
    private int restartCount;

    private final Runnable restartRunnable = new Runnable() {
        @Override public void run() {
            if (running && !stopping && !listening) startListeningInternal();
        }
    };

    public VoiceBridge(Context context, WebView webView) {
        this.context = context.getApplicationContext();
        this.webView = webView;
    }

    @JavascriptInterface public void startVoice() {
        handler.post(() -> {
            if (!hasPermission()) { postStatus("Microphone Permission Required"); return; }
            if (!SpeechRecognizer.isRecognitionAvailable(context)) { postStatus("Speech Recognition Unavailable"); return; }
            stopping = false;
            running = true;
            listening = false;
            restartCount = 0;
            ensureRecognizer();
            startListeningInternal();
        });
    }

    @JavascriptInterface public void stopVoice() {
        handler.post(() -> {
            stopping = true;
            running = false;
            listening = false;
            handler.removeCallbacks(restartRunnable);
            if (recognizer != null) {
                try { recognizer.stopListening(); } catch (Exception ignored) { }
                try { recognizer.cancel(); } catch (Exception ignored) { }
            }
            postStatus("Stopped");
        });
    }

    public void destroy() {
        handler.post(() -> {
            stopping = true;
            running = false;
            listening = false;
            handler.removeCallbacks(restartRunnable);
            if (recognizer != null) {
                try { recognizer.destroy(); } catch (Exception ignored) { }
                recognizer = null;
            }
        });
    }

    private boolean hasPermission() {
        return Build.VERSION.SDK_INT < 23 || context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void ensureRecognizer() {
        if (recognizer != null) return;
        recognizer = SpeechRecognizer.createSpeechRecognizer(context);
        recognizer.setRecognitionListener(this);
    }

    private void recreateRecognizer() {
        listening = false;
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) { }
            try { recognizer.destroy(); } catch (Exception ignored) { }
        }
        recognizer = null;
        ensureRecognizer();
    }

    private void startListeningInternal() {
        if (!running || stopping || listening) return;
        ensureRecognizer();
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        try {
            listening = true;
            recognizer.startListening(intent);
            postStatus("Listening...");
        } catch (Exception e) {
            listening = false;
            recreateRecognizer();
            scheduleRestart(1000L);
        }
    }

    private void scheduleRestart(long delay) {
        if (!running || stopping) return;
        handler.removeCallbacks(restartRunnable);
        long d = Math.min(delay + restartCount * 200L, 5000L);
        restartCount = Math.min(restartCount + 1, 12);
        handler.postDelayed(restartRunnable, d);
    }

    @Override public void onReadyForSpeech(android.os.Bundle params) { postStatus("Listening..."); }
    @Override public void onBeginningOfSpeech() { }
    @Override public void onRmsChanged(float rmsdB) { }
    @Override public void onBufferReceived(byte[] buffer) { }

    // Do not restart here: Android normally follows this with onResults/onError.
    // Restarting from both callbacks caused overlapping recognizers and the visible on/off loop.
    @Override public void onEndOfSpeech() { listening = false; }

    @Override public void onError(int error) {
        listening = false;
        if (!running || stopping) return;
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            running = false;
            postStatus("Microphone Permission Required");
            return;
        }
        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) recreateRecognizer();
        postStatus("Listening...");
        scheduleRestart(error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 1200L : 300L);
    }

    @Override public void onResults(android.os.Bundle results) {
        listening = false;
        deliver(results, true);
        if (running && !stopping) scheduleRestart(150L);
    }

    @Override public void onPartialResults(android.os.Bundle partialResults) { deliver(partialResults, false); }
    @Override public void onEvent(int eventType, android.os.Bundle params) { }

    private void deliver(android.os.Bundle results, boolean isFinal) {
        if (results == null) return;
        ArrayList<String> values = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (values == null || values.isEmpty()) return;
        String text = values.get(0);
        if (text == null || text.trim().isEmpty()) return;
        final String safeText = text;
        final boolean safeFinal = isFinal;
        webView.post(() -> webView.evaluateJavascript("if(window.onNativeVoiceResult){window.onNativeVoiceResult(" + quote(safeText) + "," + safeFinal + ");}", null));
    }

    private void postStatus(final String status) {
        webView.post(() -> webView.evaluateJavascript("if(window.onNativeVoiceStatus){window.onNativeVoiceStatus(" + quote(status) + ");}", null));
    }

    private static String quote(String value) {
        StringBuilder b = new StringBuilder(value.length() + 2);
        b.append('\'');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '\'') b.append('\\');
            if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else if (c == '\u2028') b.append("\\u2028");
            else if (c == '\u2029') b.append("\\u2029");
            else b.append(c);
        }
        b.append('\'');
        return b.toString();
    }
}
