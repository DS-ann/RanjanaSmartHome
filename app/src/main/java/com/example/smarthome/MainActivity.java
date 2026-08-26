package com.example.smarthome;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private static final int BLE_PERMISSION_REQUEST = 1001;
    private static final String EXTRA_COMMAND = "command";
    private WebView webView;
    private BLEBridge bleBridge;
    private String pendingWidgetCommand;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        pendingWidgetCommand = getIntent().getStringExtra(EXTRA_COMMAND);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);

        bleBridge = new BLEBridge(this, webView);
        webView.addJavascriptInterface(bleBridge, "Android");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (pendingWidgetCommand != null) {
                    final String command = pendingWidgetCommand;
                    pendingWidgetCommand = null;
                    view.postDelayed(new Runnable() {
                        @Override public void run() {
                            bleBridge.connectBLE();
                            view.postDelayed(new Runnable() {
                                @Override public void run() {
                                    bleBridge.sendBLE(command);
                                }
                            }, 700);
                        }
                    }, 250);
                }
            }
        });

        webView.loadUrl("file:///android_asset/index.html");
        requestBlePermissionsIfNeeded();
    }

    private void requestBlePermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT
                }, BLE_PERMISSION_REQUEST);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, BLE_PERMISSION_REQUEST);
            }
        }
    }
}
