package com.example.smarthome;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;

/** Persistent BLE central bridge for the ESP32 smart-home controller. */
public final class BLEBridge {
    private static final UUID SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID RX_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID TX_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final long SCAN_TIMEOUT_MS = 10_000L;
    private static final long RECONNECT_BASE_MS = 1_000L;
    private static final long RECONNECT_MAX_MS = 30_000L;
    private static final int MAX_COMMAND_BYTES = 240;

    private final Context context;
    private final WebView webView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rxCharacteristic;
    private BluetoothGattCharacteristic txCharacteristic;
    private BluetoothDevice lastDevice;
    private boolean scanning;
    private boolean connected;
    private boolean manuallyDisconnected;
    private int reconnectAttempt;
    private boolean reconnectScheduled;
    private long lastSuccessfulConnectionMs;

    private final Runnable scanTimeout = new Runnable() {
        @Override public void run() {
            synchronized (BLEBridge.this) {
                stopScanInternal();
                if (!connected && !manuallyDisconnected) scheduleReconnectLocked("Scan Timeout");
            }
        }
    };

    public BLEBridge(Context c, WebView view) {
        context = c.getApplicationContext();
        webView = view;
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (manager != null) adapter = manager.getAdapter();
    }

    @JavascriptInterface public synchronized void startBLE() {
        manuallyDisconnected = false;
        reconnectAttempt = 0;
        connectOrScanLocked();
    }

    @JavascriptInterface public synchronized void connectBLE() {
        manuallyDisconnected = false;
        connectOrScanLocked();
    }

    @JavascriptInterface public synchronized void reconnectBLE() {
        manuallyDisconnected = false;
        reconnectAttempt = 0;
        cancelReconnectLocked();
        stopScanInternal();
        closeGattLocked();
        connectOrScanLocked();
    }

    @JavascriptInterface public synchronized void setupBLE() {
        manuallyDisconnected = false;
        connectOrScanLocked();
    }

    @JavascriptInterface public synchronized void disconnectBLE() {
        manuallyDisconnected = true;
        cancelReconnectLocked();
        stopScanInternal();
        closeGattLocked();
        postStatus("Disconnected");
    }

    @JavascriptInterface public void sendBLE(String cmd) {
        if (cmd == null || cmd.isEmpty()) return;
        final BluetoothGatt localGatt;
        final BluetoothGattCharacteristic localRx;
        synchronized (this) {
            localGatt = gatt;
            localRx = rxCharacteristic;
            if (!connected || localGatt == null || localRx == null) {
                postStatus("Disconnected");
                if (!manuallyDisconnected) connectOrScanLocked();
                return;
            }
        }
        byte[] data = cmd.getBytes(StandardCharsets.UTF_8);
        if (data.length == 0 || data.length > MAX_COMMAND_BYTES) {
            postStatus("Command Too Long");
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                int result = localGatt.writeCharacteristic(localRx, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                if (result != BluetoothGatt.GATT_SUCCESS) postStatus("Command Failed");
            } else {
                localRx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                localRx.setValue(data);
                if (!localGatt.writeCharacteristic(localRx)) postStatus("Command Failed");
            }
        } catch (SecurityException e) {
            postStatus("Bluetooth Permission Required");
        }
    }

    private synchronized void connectOrScanLocked() {
        if (adapter == null) {
            postStatus("Bluetooth Unavailable");
            return;
        }
        if (!adapter.isEnabled()) {
            postStatus("Bluetooth Off");
            scheduleReconnectLocked("Bluetooth Off");
            return;
        }
        if (!hasBlePermissions()) {
            postStatus("Bluetooth Permission Required");
            return;
        }
        if (connected && gatt != null && rxCharacteristic != null && txCharacteristic != null) {
            postStatus("Connected");
            return;
        }
        if (lastDevice != null) {
            try {
                connectDevice(lastDevice);
                return;
            } catch (Exception ignored) {
            }
        }
        startScanLocked();
    }

    private synchronized void startScanLocked() {
        if (scanning || manuallyDisconnected || connected) return;
        if (adapter == null || !adapter.isEnabled() || !hasBlePermissions()) return;
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            scheduleReconnectLocked("Bluetooth Unavailable");
            return;
        }
        stopScanInternal();
        postStatus("Scanning...");
        ScanFilter filter = new ScanFilter.Builder().setServiceUuid(new android.os.ParcelUuid(SERVICE_UUID)).build();
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        try {
            scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
            scanning = true;
            mainHandler.removeCallbacks(scanTimeout);
            mainHandler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS);
        } catch (SecurityException e) {
            postStatus("Bluetooth Permission Required");
        }
    }

    private synchronized void stopScanInternal() {
        mainHandler.removeCallbacks(scanTimeout);
        if (scanner != null && scanning && hasBlePermissions()) {
            try { scanner.stopScan(scanCallback); } catch (SecurityException ignored) { }
        }
        scanning = false;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (device == null) return;
            synchronized (BLEBridge.this) {
                if (!scanning || manuallyDisconnected) return;
                lastDevice = device;
                stopScanInternal();
                connectDevice(device);
            }
        }
        @Override public void onScanFailed(int errorCode) {
            synchronized (BLEBridge.this) {
                stopScanInternal();
                postStatus("Scan Failed");
                scheduleReconnectLocked("Scan Failed");
            }
        }
    };

    private synchronized void connectDevice(BluetoothDevice device) {
        if (manuallyDisconnected || !hasBlePermissions()) return;
        closeGattLocked();
        postStatus("Connecting...");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            } else {
                gatt = device.connectGatt(context, false, gattCallback);
            }
        } catch (SecurityException e) {
            postStatus("Bluetooth Permission Required");
            scheduleReconnectLocked("Permission");
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                synchronized (BLEBridge.this) {
                    if (gatt != g || manuallyDisconnected) return;
                    postStatus("Connected, discovering...");
                }
                try {
                    if (!g.discoverServices()) handleGattFailure(g, "Service Discovery Failed");
                } catch (SecurityException e) {
                    handleGattFailure(g, "Bluetooth Permission Required");
                }
            } else {
                handleGattFailure(g, manuallyDisconnected ? "Disconnected" : "Connection Lost");
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handleGattFailure(g, "Service Discovery Failed");
                return;
            }
            BluetoothGattService service = g.getService(SERVICE_UUID);
            BluetoothGattCharacteristic rx = service == null ? null : service.getCharacteristic(RX_UUID);
            BluetoothGattCharacteristic tx = service == null ? null : service.getCharacteristic(TX_UUID);
            if (rx == null || tx == null) {
                handleGattFailure(g, "BLE Characteristics Missing");
                return;
            }
            synchronized (BLEBridge.this) {
                if (gatt != g || manuallyDisconnected) return;
                rxCharacteristic = rx;
                txCharacteristic = tx;
                postStatus("Connected, enabling notifications...");
            }
            enableNotifications(g, tx);
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            if (!CCCD_UUID.equals(descriptor.getUuid())) return;
            if (status == BluetoothGatt.GATT_SUCCESS) {
                synchronized (BLEBridge.this) {
                    if (gatt != g || manuallyDisconnected) return;
                    connected = true;
                    reconnectAttempt = 0;
                    reconnectScheduled = false;
                    lastSuccessfulConnectionMs = android.os.SystemClock.elapsedRealtime();
                    postStatus("Ready");
                }
                requestStatus();
            } else {
                handleGattFailure(g, "Notification Setup Failed");
            }
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
            if (!TX_UUID.equals(characteristic.getUuid())) return;
            byte[] value = characteristic.getValue();
            if (value != null && value.length > 0) postData(new String(value, StandardCharsets.UTF_8));
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int status) {
            if (RX_UUID.equals(characteristic.getUuid()) && status != BluetoothGatt.GATT_SUCCESS) postStatus("Command Failed");
        }
    };

    private void enableNotifications(BluetoothGatt g, BluetoothGattCharacteristic tx) {
        try {
            if (!g.setCharacteristicNotification(tx, true)) {
                handleGattFailure(g, "Notification Setup Failed");
                return;
            }
            BluetoothGattDescriptor cccd = tx.getDescriptor(CCCD_UUID);
            if (cccd == null) {
                handleGattFailure(g, "Notification Descriptor Missing");
                return;
            }
            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if (!g.writeDescriptor(cccd)) handleGattFailure(g, "Notification Setup Failed");
        } catch (SecurityException e) {
            handleGattFailure(g, "Bluetooth Permission Required");
        }
    }

    private void requestStatus() {
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() { sendBLE("status"); }
        }, 200);
    }

    private synchronized void handleGattFailure(BluetoothGatt g, String status) {
        if (gatt != g) return;
        connected = false;
        rxCharacteristic = null;
        txCharacteristic = null;
        try { g.close(); } catch (Exception ignored) { }
        gatt = null;
        postStatus(status);
        if (!manuallyDisconnected) scheduleReconnectLocked(status);
    }

    private synchronized void closeGattLocked() {
        BluetoothGatt old = gatt;
        gatt = null;
        connected = false;
        rxCharacteristic = null;
        txCharacteristic = null;
        if (old != null) {
            try { old.disconnect(); } catch (Exception ignored) { }
            try { old.close(); } catch (Exception ignored) { }
        }
    }

    private synchronized void scheduleReconnectLocked(String reason) {
        if (manuallyDisconnected || reconnectScheduled || connected) return;
        reconnectScheduled = true;
        long delay = Math.min(RECONNECT_MAX_MS, RECONNECT_BASE_MS << Math.min(reconnectAttempt, 5));
        reconnectAttempt = Math.min(reconnectAttempt + 1, 6);
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                synchronized (BLEBridge.this) {
                    reconnectScheduled = false;
                    if (!manuallyDisconnected && !connected) connectOrScanLocked();
                }
            }
        }, delay);
        postStatus("Reconnecting in " + Math.max(1L, delay / 1000L) + "s...");
    }

    private synchronized void cancelReconnectLocked() {
        reconnectScheduled = false;
        // Individual reconnect runnables self-check reconnectScheduled and state.
    }

    private boolean hasBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        return true;
    }

    private void postStatus(final String status) {
        webView.post(new Runnable() {
            @Override public void run() {
                webView.evaluateJavascript("if(typeof onBLEStatus==='function'){onBLEStatus(" + jsQuote(status) + ");}", null);
            }
        });
    }

    private void postData(final String data) {
        webView.post(new Runnable() {
            @Override public void run() {
                webView.evaluateJavascript("if(typeof onBLEData==='function'){onBLEData(" + jsQuote(data) + ");}", null);
            }
        });
    }

    private static String jsQuote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('\'');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '\'') out.append('\\');
            if (c == '\n') out.append("\\n");
            else if (c == '\r') out.append("\\r");
            else if (c == '\u2028') out.append("\\u2028");
            else if (c == '\u2029') out.append("\\u2029");
            else out.append(c);
        }
        out.append('\'');
        return out.toString();
    }
}
