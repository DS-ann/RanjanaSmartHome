package com.example.smarthome;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST = 1001;
    private WebView webView;
    private BLEBridge bleBridge;
    private VoiceBridge voiceBridge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);

        // Required for JavaScript alert()/prompt(), which the timer and usage controls use.
        webView.setWebChromeClient(new WebChromeClient());

        bleBridge = new BLEBridge(this, webView);
        voiceBridge = new VoiceBridge(this, webView);
        webView.addJavascriptInterface(bleBridge, "Android");
        webView.addJavascriptInterface(voiceBridge, "NativeVoice");

        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                installNativeVoiceAdapter(view);
                bleBridge.startBLE();
            }
        });

        webView.loadUrl("file:///android_asset/index.html");
        requestPermissionsIfNeeded();
    }

    private void installNativeVoiceAdapter(WebView view) {
        String js = "javascript:(function(){" +
                "window.__nativeVoiceRunning=false;" +
                "window.__nativeVoiceAwake=false;" +
                "window.onNativeVoiceStatus=function(s){" +
                "var p=document.getElementById('voicePopup');" +
                "var f=document.getElementById('voiceFinal');" +
                "if(s==='Listening...'){if(p)p.style.display='block';if(f)f.innerHTML=\"Listening <span class='listening-dot'></span>\";}" +
                "if(s==='Stopped'){window.__nativeVoiceRunning=false;window.__nativeVoiceAwake=false;}" +
                "console.log('Native voice:',s);" +
                "};" +
                "window.onNativeVoiceResult=function(text,isFinal){" +
                "if(!text)return;" +
                "var popup=document.getElementById('voicePopup');if(popup)popup.style.display='block';" +
                "var fin=document.getElementById('voiceFinal');if(fin)fin.innerText=text;" +
                "var interim=document.getElementById('voiceInterim');if(interim)interim.innerText=isFinal?'':'Listening...';" +
                "if(!isFinal)return;" +
                "var raw=text.toLowerCase().replace(/[.,!?]/g,' ').replace(/\\s+/g,' ').trim();" +
                "if(!raw)return;" +
                // Wake words are optional: if present, strip them; otherwise process the command directly.
                "raw=raw.replace(/^\\s*(?:hey\\s+|ok\\s+)?jarvis\\s*/i,'').trim();" +
                "if(!raw)return;" +
                "window.__nativeVoiceAwake=false;" +
                "if(typeof voiceCommand==='function')voiceCommand(raw);" +
                "setTimeout(function(){if(window.__nativeVoiceRunning===false&&typeof hideVoicePopup==='function')hideVoicePopup();},800);" +
                "};" +
                "window.startVoiceEngine=function(){" +
                "if(!window.NativeVoice){console.log('Native voice bridge unavailable');return;}" +
                "if(window.__nativeVoiceRunning){NativeVoice.stopVoice();window.__nativeVoiceRunning=false;window.__nativeVoiceAwake=false;if(typeof hideVoicePopup==='function')hideVoicePopup();return;}" +
                "window.__nativeVoiceRunning=true;window.__nativeVoiceAwake=false;" +
                "var w=document.querySelectorAll('.wave');w.forEach(function(x){x.style.display='block';});" +
                "var mic=document.querySelector('.mic');if(mic)mic.classList.add('active');" +
                "if(typeof showVoicePopup==='function')showVoicePopup();" +
                "NativeVoice.startVoice();" +
                "};" +
                "})();";
        view.evaluateJavascript(js, null);
    }

    private void requestPermissionsIfNeeded() {
        java.util.ArrayList<String> permissions = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.RECORD_AUDIO);
        if (!permissions.isEmpty()) requestPermissions(permissions.toArray(new String[0]), PERMISSION_REQUEST);
    }

    @Override protected void onDestroy() {
        if (voiceBridge != null) voiceBridge.destroy();
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
