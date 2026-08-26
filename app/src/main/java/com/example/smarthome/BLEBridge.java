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
import java.util.List;
import java.util.UUID;

/**
 * Native BLE central bridge for the ESP32 firmware.
 *
 * Protocol:
 *   Service:  6e400001-b5a3-f393-e0a9-e50e24dcca9e
 *   RX/write: 6e400002-b5a3-f393-e0a9-e50e24dcca9e
 *   TX/notify:6e400003-b5a3-f393-e0a9-e50e24dcca9e
 */
public final class BLEBridge {

    private static final UUID SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID RX_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID TX_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final long SCAN_TIMEOUT_MS = 10_000L;

    private final Context context;
    private final WebView webView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rxCharacteristic;
    private BluetoothGattCharacteristic txCharacteristic;

    private boolean scanning;
    private boolean connected;
    private boolean manuallyDisconnected;

    private final Runnable scanTimeout = new Runnable() {
        @Override public void run() {
            stopScanInternal();
            if (!connected && !manuallyDisconnected) {
                postStatus("Disconnected");
            }
        }
    };

    public BLEBridge(Context c, WebView view) {
        context = c.getApplicationContext();
        webView = view;
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (manager != null) {
            adapter = manager.getAdapter();
        }
    }

    @JavascriptInterface
    public synchronized void startBLE() {
        manuallyDisconnected = false;
        startScan();
    }

    @JavascriptInterface
    public synchronized void connectBLE() {
        manuallyDisconnected = false;
        if (connected && gatt != null) {
            postStatus("Connected");
            return;
        }
        startScan();
    }

    @JavascriptInterface
    public synchronized void reconnectBLE() {
        manuallyDisconnected = false;
        closeGatt();
        startScan();
    }

    @JavascriptInterface
    public synchronized void setupBLE() {
        manuallyDisconnected = false;
        if (connected && txCharacteristic != null && rxCharacteristic != null) {
            postStatus("Connected");
            return;
        }
        startScan();
    }

    @JavascriptInterface
    public synchronized void disconnectBLE() {
        manuallyDisconnected = true;
        stopScanInternal();
        closeGatt();
        postStatus("Disconnected");
    }

    @JavascriptInterface
    public void sendBLE(String cmd) {
        if (cmd == null || cmd.isEmpty()) return;

        final BluetoothGatt localGatt;
        final BluetoothGattCharacteristic localRx;
        synchronized (this) {
            localGatt = gatt;
            localRx = rxCharacteristic;
        }

        if (!connected || localGatt == null || localRx == null) {
            postStatus("Disconnected");
            return;
        }

        byte[] data = cmd.getBytes(StandardCharsets.UTF_8);
        if (data.length == 0 || data.length > 240) {
            return;
        }

        // ESP-IDF/NimBLE accepts ordinary write requests on the RX characteristic.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            localGatt.writeCharacteristic(localRx, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        } else {
            localRx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            localRx.setValue(data);
            localGatt.writeCharacteristic(localRx);
        }
    }

    private synchronized void startScan() {
        if (adapter == null || !adapter.isEnabled()) {
            postStatus("Bluetooth Off");
            return;
        }
        if (!hasBlePermissions()) {
            postStatus("Bluetooth Permission Required");
            return;
        }
        if (connected) {
            postStatus("Connected");
            return;
        }

        stopScanInternal();
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            postStatus("Bluetooth Unavailable");
            return;
        }

        manuallyDisconnected = false;
        postStatus("Scanning...");

        ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(new android.os.ParcelUuid(SERVICE_UUID))
                .build();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

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
            try {
                scanner.stopScan(scanCallback);
            } catch (SecurityException ignored) {
            }
        }
        scanning = false;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (device == null) return;
            synchronized (BLEBridge.this) {
                if (!scanning || manuallyDisconnected) return;
                stopScanInternal();
                connectDevice(device);
            }
        }

        @Override public void onScanFailed(int errorCode) {
            synchronized (BLEBridge.this) {
                scanning = false;
                postStatus("Scan Failed");
                if (!manuallyDisconnected) scheduleReconnect();
            }
        }
    };

    private synchronized void connectDevice(BluetoothDevice device) {
        if (!hasBlePermissions()) {
            postStatus("Bluetooth Permission Required");
            return;
        }

        closeGatt();
        postStatus("Connecting...");

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            } else {
                gatt = device.connectGatt(context, false, gattCallback);
            }
        } catch (SecurityException e) {
            postStatus("Bluetooth Permission Required");
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                synchronized (BLEBridge.this) {
                    if (gatt != g || manuallyDisconnected) return;
                    connected = false;
                    postStatus("Connected");
                }
                try {
                    g.discoverServices();
                } catch (SecurityException e) {
                    handleGattFailure(g, "Bluetooth Permission Required");
                }
            } else {
                handleGattFailure(g, manuallyDisconnected ? "Disconnected" : "Disconnected");
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handleGattFailure(g, "Service Discovery Failed");
                return;
            }

            BluetoothGattService service = g.getService(SERVICE_UUID);
            if (service == null) {
                handleGattFailure(g, "BLE Service Not Found");
                return;
            }

            BluetoothGattCharacteristic rx = service.getCharacteristic(RX_UUID);
            BluetoothGattCharacteristic tx = service.getCharacteristic(TX_UUID);
            if (rx == null || tx == null) {
                handleGattFailure(g, "BLE Characteristics Missing");
                return;
            }

            synchronized (BLEBridge.this) {
                if (gatt != g || manuallyDisconnected) return;
                rxCharacteristic = rx;
                txCharacteristic = tx;
            }

            enableNotifications(g, tx);
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            if (!CCCD_UUID.equals(descriptor.getUuid())) return;
            if (status == BluetoothGatt.GATT_SUCCESS) {
                synchronized (BLEBridge.this) {
                    if (gatt != g || manuallyDisconnected) return;
                    connected = true;
                }
                postStatus("Connected");
                requestStatus();
            } else {
                handleGattFailure(g, "Notification Setup Failed");
            }
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
            if (!TX_UUID.equals(characteristic.getUuid())) return;
            byte[] value = characteristic.getValue();
            if (value == null || value.length == 0) return;
            postData(new String(value, StandardCharsets.UTF_8));
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS && RX_UUID.equals(characteristic.getUuid())) {
                postStatus("Command Failed");
            }
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
            if (!g.writeDescriptor(cccd)) {
                handleGattFailure(g, "Notification Setup Failed");
            }
        } catch (SecurityException e) {
            handleGattFailure(g, "Bluetooth Permission Required");
        }
    }

    private void requestStatus() {
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                sendBLE("status");
            }
        }, 150);
    }

    private synchronized void handleGattFailure(BluetoothGatt g, String status) {
        if (gatt != g) return;
        connected = false;
        rxCharacteristic = null;
        txCharacteristic = null;
        try {
            g.close();
        } catch (Exception ignored) {
        }
        gatt = null;
        postStatus(status);
        if (!manuallyDisconnected) scheduleReconnect();
    }

    private synchronized void closeGatt() {
        BluetoothGatt old = gatt;
        gatt = null;
        connected = false;
        rxCharacteristic = null;
        txCharacteristic = null;
        if (old != null) {
            try {
                old.disconnect();
            } catch (Exception ignored) {
            }
            try {
                old.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void scheduleReconnect() {
        if (manuallyDisconnected) return;
        mainHandler.postDelayed(new Runnable() {
            @Override public void run() {
                synchronized (BLEBridge.this) {
                    if (!manuallyDisconnected && !connected && !scanning) startScan();
                }
            }
        }, 2_000L);
    }

    private boolean hasBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void postStatus(final String status) {
        webView.post(new Runnable() {
            @Override public void run() {
                String js = "if(typeof onBLEStatus==='function'){onBLEStatus(" + jsQuote(status) + ");}";
                webView.evaluateJavascript(js, null);
            }
        });
    }

    private void postData(final String data) {
        webView.post(new Runnable() {
            @Override public void run() {
                String js = "if(typeof onBLEData==='function'){onBLEData(" + jsQuote(data) + ");}";
                webView.evaluateJavascript(js, null);
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
