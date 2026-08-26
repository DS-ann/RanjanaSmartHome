package com.example.smarthome;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelUuid;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;

/** Handles a widget command without starting MainActivity or the WebView. */
public final class WidgetBleService extends Service {
    public static final String ACTION_SEND = "com.example.smarthome.WIDGET_SEND";
    public static final String EXTRA_COMMAND = "command";

    private static final int NOTIFICATION_ID = 4101;
    private static final String CHANNEL_ID = "widget_ble";
    private static final UUID SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID RX_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final long SCAN_TIMEOUT_MS = 7000L;
    private static final long CONNECT_TIMEOUT_MS = 9000L;
    private static final int MAX_ATTEMPTS = 2;

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothDevice device;
    private BluetoothGattCharacteristic rx;
    private String command;
    private int attempt;
    private boolean finished;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    private final Runnable timeout = () -> retryOrFinish();

    @Override public void onCreate() {
        super.onCreate();
        android.bluetooth.BluetoothManager manager = (android.bluetooth.BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        if (manager != null) adapter = manager.getAdapter();
        createChannel();
        if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("Ranjana Smart Home")
                    .setContentText("Sending widget command…")
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setOngoing(true);
            if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
            startForeground(NOTIFICATION_ID, b.build());
        } else {
            startForeground(NOTIFICATION_ID, new Notification.Builder(this)
                    .setContentTitle("Ranjana Smart Home")
                    .setContentText("Sending widget command…")
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setOngoing(true).build());
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_SEND.equals(intent.getAction())) { stopSelf(startId); return START_NOT_STICKY; }
        String value = intent.getStringExtra(EXTRA_COMMAND);
        if (value == null || value.isEmpty()) { stopSelf(startId); return START_NOT_STICKY; }
        command = value;
        attempt = 0;
        finished = false;
        startAttempt();
        return START_NOT_STICKY;
    }

    private void startAttempt() {
        if (finished) return;
        cleanupGatt();
        if (!hasPermission() || adapter == null || !adapter.isEnabled()) { finish(); return; }
        attempt++;
        BluetoothLeScanner s;
        try { s = adapter.getBluetoothLeScanner(); } catch (SecurityException e) { finish(); return; }
        scanner = s;
        if (scanner == null) { retryOrFinish(); return; }
        try {
            ScanFilter filter = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE_UUID)).build();
            ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
            handler.removeCallbacks(timeout);
            handler.postDelayed(timeout, SCAN_TIMEOUT_MS);
        } catch (SecurityException e) { finish(); }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice d = result.getDevice();
            if (d == null || finished) return;
            stopScan();
            device = d;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) gatt = d.connectGatt(WidgetBleService.this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
                else gatt = d.connectGatt(WidgetBleService.this, false, gattCallback);
                handler.removeCallbacks(timeout);
                handler.postDelayed(timeout, CONNECT_TIMEOUT_MS);
            } catch (SecurityException e) { retryOrFinish(); }
        }
        @Override public void onScanFailed(int errorCode) { stopScan(); retryOrFinish(); }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState != BluetoothGatt.STATE_CONNECTED || status != BluetoothGatt.GATT_SUCCESS) { retryOrFinish(); return; }
            try {
                if (!g.discoverServices()) retryOrFinish();
            } catch (SecurityException e) { retryOrFinish(); }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) { retryOrFinish(); return; }
            BluetoothGattService service = g.getService(SERVICE_UUID);
            rx = service == null ? null : service.getCharacteristic(RX_UUID);
            if (rx == null) { retryOrFinish(); return; }
            byte[] data = command.getBytes(StandardCharsets.UTF_8);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    int r = g.writeCharacteristic(rx, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                    if (r != BluetoothGatt.GATT_SUCCESS) retryOrFinish();
                } else {
                    rx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                    rx.setValue(data);
                    if (!g.writeCharacteristic(rx)) retryOrFinish();
                }
            } catch (SecurityException e) { retryOrFinish(); }
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (RX_UUID.equals(c.getUuid())) {
                if (status == BluetoothGatt.GATT_SUCCESS) finish();
                else retryOrFinish();
            }
        }
    };

    private void retryOrFinish() {
        if (finished) return;
        handler.removeCallbacks(timeout);
        stopScan();
        cleanupGatt();
        if (attempt < MAX_ATTEMPTS) handler.postDelayed(this::startAttempt, 500L * attempt);
        else finish();
    }

    private void stopScan() {
        if (scanner != null && hasPermission()) {
            try { scanner.stopScan(scanCallback); } catch (SecurityException ignored) { }
        }
        scanner = null;
    }

    private void cleanupGatt() {
        BluetoothGatt old = gatt;
        gatt = null;
        rx = null;
        if (old != null) {
            try { old.disconnect(); } catch (Exception ignored) { }
            try { old.close(); } catch (Exception ignored) { }
        }
    }

    private boolean hasPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        return true;
    }

    private void finish() {
        if (finished) return;
        finished = true;
        handler.removeCallbacks(timeout);
        stopScan();
        cleanupGatt();
        stopForeground(true);
        stopSelf();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Smart Home BLE", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Temporary connection used by home-screen controls");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    @Override public void onDestroy() {
        finished = true;
        handler.removeCallbacksAndMessages(null);
        stopScan();
        cleanupGatt();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
