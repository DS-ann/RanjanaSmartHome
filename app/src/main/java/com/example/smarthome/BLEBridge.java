package com.example.smarthome;

import android.content.Context;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

public class BLEBridge {

    Context context;

    public BLEBridge(Context c){
        context = c;
    }

    @JavascriptInterface
    public void startBLE(){
    }

    @JavascriptInterface
    public void connectBLE(){
    }

    @JavascriptInterface
    public void reconnectBLE(){
    }

    @JavascriptInterface
    public void setupBLE(){
    }

    @JavascriptInterface
    public void disconnectBLE(){
    }

    @JavascriptInterface
    public void sendBLE(String cmd){

        Toast.makeText(
            context,
            "BLE: " + cmd,
            Toast.LENGTH_SHORT
        ).show();
    }
}
