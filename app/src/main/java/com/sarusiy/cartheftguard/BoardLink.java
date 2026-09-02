package com.sarusiy.cartheftguard;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns the board's BLE connection, Wi-Fi provisioning, and frequency-over-Wi-Fi
 * calls, independent of any single Activity/Fragment, so the Connect, Monitor
 * and Control screens can all observe the same live connection state.
 */
public final class BoardLink {
    public static final String TARGET_NAME = "JC-P4-C6";
    public static final UUID SERVICE_UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    public static final UUID RESPONSE_UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb");
    public static final UUID WIFI_CONFIG_UUID = UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    public static final int MIN_FREQ_MS = 10;
    public static final int MAX_FREQ_MS = 60000;
    public static final int COLOR_DEFAULT = 0xff1f2933;
    public static final int COLOR_PROGRESS = 0xffb98900;
    public static final int COLOR_SUCCESS = 0xff1b8a5a;
    public static final int COLOR_ERROR = 0xffb00020;

    /** Observer for connection/status events; all callbacks arrive on the main thread. */
    public interface Listener {
        default void onStatus(String status, int color) {}
        default void onLog(String line) {}
        default void onScanningChanged(boolean scanning) {}
        default void onDeviceFound(BluetoothDevice device, String name, int rssi) {}
        default void onBoardConnectionChanged(boolean connected) {}
        default void onWifiSetupReady() {}
        default void onWifiConnected(String boardIp) {}
        default void onWifiNetworksUpdated(List<android.net.wifi.ScanResult> results) {}
        default void onObdData(String json) {}
    }

    private static volatile BoardLink instance;

    public static BoardLink getInstance(Context context) {
        if (instance == null) {
            synchronized (BoardLink.class) {
                if (instance == null) {
                    instance = new BoardLink(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private final BluetoothAdapter bluetoothAdapter;
    private final WifiManager wifiManager;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic responseCharacteristic;
    private BluetoothGattCharacteristic wifiConfigCharacteristic;
    private boolean scanning;
    private boolean wifiReceiverRegistered;
    private volatile String boardIp;

    private final BroadcastReceiver wifiScanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(intent.getAction())) {
                notifyWifiNetworksUpdated();
            }
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
            post(() -> {
                for (Listener listener : listeners) {
                    listener.onDeviceFound(device, name, result.getRssi());
                }
            });
            if (TARGET_NAME.equals(name)) {
                stopScan();
                connect(device);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            emitStatus("Scan failed: " + errorCode, COLOR_ERROR);
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt connectedGatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                emitStatus("Connected; discovering services", COLOR_PROGRESS);
                // Default ATT MTU (23) truncates longer board responses; negotiate a larger one first.
                if (hasConnectPermission() && !connectedGatt.requestMtu(247)) {
                    connectedGatt.discoverServices();
                }
                return;
            }
            clearConnection("Disconnected");
        }

        @Override
        public void onMtuChanged(BluetoothGatt connectedGatt, int mtu, int status) {
            emitLog("BLE MTU negotiated: " + mtu);
            if (hasConnectPermission()) {
                connectedGatt.discoverServices();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt connectedGatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emitStatus("Service discovery failed: " + status, COLOR_ERROR);
                return;
            }
            BluetoothGattService service = connectedGatt.getService(SERVICE_UUID);
            if (service == null) {
                emitStatus("Board service 0xFFF0 not found", COLOR_ERROR);
                return;
            }
            responseCharacteristic = service.getCharacteristic(RESPONSE_UUID);
            wifiConfigCharacteristic = service.getCharacteristic(WIFI_CONFIG_UUID);
            if (responseCharacteristic == null || wifiConfigCharacteristic == null) {
                emitStatus("Install the current board firmware first", COLOR_ERROR);
                return;
            }
            subscribeToBoardResponses(connectedGatt);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt connectedGatt, BluetoothGattDescriptor descriptor, int status) {
            if (CCCD_UUID.equals(descriptor.getUuid()) && status == BluetoothGatt.GATT_SUCCESS) {
                emitLog("Board responses enabled");
                post(() -> {
                    for (Listener listener : listeners) {
                        listener.onBoardConnectionChanged(true);
                        listener.onWifiSetupReady();
                    }
                });
                emitStatus("Enter Wi-Fi settings", COLOR_SUCCESS);
            } else {
                emitStatus("Response subscription failed: " + status, COLOR_ERROR);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt connectedGatt, BluetoothGattCharacteristic characteristic) {
            if (!RESPONSE_UUID.equals(characteristic.getUuid())) {
                return;
            }
            String response = new String(characteristic.getValue(), StandardCharsets.UTF_8).trim();
            emitLog("Board: " + response);
            if (response.startsWith("WiFi connected ip=")) {
                boardIp = response.substring("WiFi connected ip=".length()).trim();
                post(() -> {
                    for (Listener listener : listeners) {
                        listener.onWifiConnected(boardIp);
                    }
                });
                emitStatus("Wi-Fi ready: " + boardIp, COLOR_SUCCESS);
            } else {
                emitStatus(response, response.startsWith("ERR") ? COLOR_ERROR : COLOR_DEFAULT);
            }
        }
    };

    private BoardLink(Context appContext) {
        this.appContext = appContext;
        BluetoothManager manager = (BluetoothManager) appContext.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = manager == null ? null : manager.getAdapter();
        scanner = bluetoothAdapter == null ? null : bluetoothAdapter.getBluetoothLeScanner();
        wifiManager = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
        IntentFilter filter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(wifiScanReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            appContext.registerReceiver(wifiScanReceiver, filter);
        }
        wifiReceiverRegistered = true;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public boolean isBleReady() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    public boolean isScanning() {
        return scanning;
    }

    public boolean isBoardConnected() {
        return gatt != null && responseCharacteristic != null;
    }

    public boolean isWifiReady() {
        return boardIp != null && !boardIp.isEmpty();
    }

    public String getBoardIp() {
        return boardIp;
    }

    public boolean hasBlePermissions() {
        for (String permission : requiredBlePermissions()) {
            if (ContextCompat.checkSelfPermission(appContext, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    public String[] requiredBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION};
            }
            return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    public String[] requiredWifiPermissions() {
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    public boolean hasWifiPermissions() {
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    public boolean isLocationEnabled() {
        android.location.LocationManager locationManager =
                (android.location.LocationManager) appContext.getSystemService(Context.LOCATION_SERVICE);
        return locationManager != null && locationManager.isLocationEnabled();
    }

    private boolean hasScanPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    public void startScan() {
        if (!hasBlePermissions()) {
            emitStatus("Bluetooth permission missing", COLOR_ERROR);
            return;
        }
        if (scanning) {
            stopScan();
            return;
        }
        if (scanner == null) {
            scanner = bluetoothAdapter == null ? null : bluetoothAdapter.getBluetoothLeScanner();
        }
        if (scanner == null) {
            emitStatus("BLE scanner unavailable", COLOR_ERROR);
            return;
        }
        closeGatt();
        boardIp = null;
        responseCharacteristic = null;
        wifiConfigCharacteristic = null;
        scanning = true;
        post(() -> {
            for (Listener listener : listeners) {
                listener.onScanningChanged(true);
                listener.onBoardConnectionChanged(false);
            }
        });
        emitStatus("Scanning for " + TARGET_NAME, COLOR_DEFAULT);
        scanner.startScan(scanCallback);
        mainHandler.postDelayed(() -> {
            if (scanning) {
                stopScan();
                emitStatus("Scan timed out", COLOR_DEFAULT);
            }
        }, 12000);
    }

    @SuppressLint("MissingPermission")
    public void stopScan() {
        if (scanning && scanner != null && hasScanPermission()) {
            scanner.stopScan(scanCallback);
        }
        scanning = false;
        post(() -> {
            for (Listener listener : listeners) {
                listener.onScanningChanged(false);
            }
        });
    }

    @SuppressLint("MissingPermission")
    public void connect(BluetoothDevice device) {
        if (!hasConnectPermission()) {
            emitStatus("Bluetooth connect permission missing", COLOR_ERROR);
            return;
        }
        emitStatus("Connecting", COLOR_PROGRESS);
        gatt = device.connectGatt(appContext, false, gattCallback);
    }

    @SuppressLint("MissingPermission")
    private void subscribeToBoardResponses(BluetoothGatt connectedGatt) {
        if (!connectedGatt.setCharacteristicNotification(responseCharacteristic, true)) {
            emitStatus("Cannot enable board responses", COLOR_ERROR);
            return;
        }
        BluetoothGattDescriptor descriptor = responseCharacteristic.getDescriptor(CCCD_UUID);
        if (descriptor == null) {
            emitStatus("Response descriptor missing", COLOR_ERROR);
            return;
        }
        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        if (!connectedGatt.writeDescriptor(descriptor)) {
            emitStatus("Cannot subscribe to board responses", COLOR_ERROR);
        }
    }

    @SuppressLint("MissingPermission")
    public void provisionWifi(String ssid, String password) {
        if (gatt == null || wifiConfigCharacteristic == null || !hasConnectPermission()) {
            emitStatus("Connect to the board first", COLOR_ERROR);
            return;
        }
        if (ssid.isEmpty() || password.length() < 8) {
            emitStatus("Enter Wi-Fi name and password", COLOR_ERROR);
            return;
        }
        wifiConfigCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        wifiConfigCharacteristic.setValue((ssid + "\n" + password).getBytes(StandardCharsets.UTF_8));
        if (gatt.writeCharacteristic(wifiConfigCharacteristic)) {
            emitStatus("Connecting board to Wi-Fi", COLOR_PROGRESS);
            emitLog("Wi-Fi credentials sent; waiting for board IP");
        } else {
            emitStatus("Wi-Fi configuration was not accepted", COLOR_ERROR);
        }
    }

    public void sendFrequency(int value) {
        if (!isWifiReady()) {
            emitStatus("Connect the board to Wi-Fi first", COLOR_ERROR);
            return;
        }
        if (value < MIN_FREQ_MS || value > MAX_FREQ_MS) {
            emitStatus("Use " + MIN_FREQ_MS + " to " + MAX_FREQ_MS + " ms", COLOR_ERROR);
            return;
        }
        String command = "freq " + value;
        emitStatus("Sending over Wi-Fi", COLOR_PROGRESS);
        networkExecutor.execute(() -> {
            try {
                byte[] body = command.getBytes(StandardCharsets.UTF_8);
                HttpURLConnection connection = (HttpURLConnection) new URL("http://" + boardIp + "/api/frequency").openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setDoOutput(true);
                connection.setUseCaches(false);
                connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
                connection.setFixedLengthStreamingMode(body.length);
                try (java.io.OutputStream output = connection.getOutputStream()) {
                    output.write(body);
                }
                int code = connection.getResponseCode();
                String response = readResponse(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
                connection.disconnect();
                emitLog("Wi-Fi: " + response);
                emitStatus(response, response.startsWith("ERR") ? COLOR_ERROR : COLOR_SUCCESS);
            } catch (Exception exception) {
                emitLog("Wi-Fi request failed: " + exception.getMessage());
                emitStatus("Wi-Fi request failed", COLOR_ERROR);
            }
        });
    }

    public void fetchObdData() {
        if (!isWifiReady()) {
            return;
        }
        networkExecutor.execute(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL("http://" + boardIp + "/api/obd").openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(3000);
                int code = connection.getResponseCode();
                String response = readResponse(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
                connection.disconnect();
                if (code >= 400) {
                    emitLog("OBD monitor request failed: HTTP " + code);
                    return;
                }
                post(() -> {
                    for (Listener listener : listeners) {
                        listener.onObdData(response);
                    }
                });
            } catch (Exception exception) {
                emitLog("OBD monitor request failed: " + exception.getMessage());
            }
        });
    }

    private String readResponse(InputStream stream) throws Exception {
        if (stream == null) {
            return "No response";
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        return response.toString();
    }

    @SuppressLint("MissingPermission")
    public void refreshWifiNetworks() {
        if (!hasWifiPermissions()) {
            emitStatus("Allow Location to list Wi-Fi networks", COLOR_ERROR);
            return;
        }
        if (!isLocationEnabled()) {
            emitStatus("Turn on phone Location to list Wi-Fi networks", COLOR_ERROR);
            return;
        }
        if (wifiManager == null || !wifiManager.isWifiEnabled()) {
            emitStatus("Enable Wi-Fi on this phone", COLOR_ERROR);
            return;
        }
        try {
            boolean scanStarted = wifiManager.startScan();
            notifyWifiNetworksUpdated();
            if (!scanStarted) {
                emitStatus("Wi-Fi scan is throttled; showing the latest available networks", COLOR_DEFAULT);
            }
        } catch (SecurityException exception) {
            emitStatus("Wi-Fi scan needs Location permission and Location service", COLOR_ERROR);
            emitLog("Wi-Fi scan blocked: " + exception.getMessage());
        }
    }

    @SuppressLint("MissingPermission")
    private void notifyWifiNetworksUpdated() {
        if (wifiManager == null || !hasWifiPermissions() || !isLocationEnabled()) {
            return;
        }
        List<android.net.wifi.ScanResult> results;
        try {
            results = new ArrayList<>(wifiManager.getScanResults());
        } catch (SecurityException exception) {
            emitStatus("Wi-Fi list needs Location permission and Location service", COLOR_ERROR);
            emitLog("Wi-Fi list blocked: " + exception.getMessage());
            return;
        }
        post(() -> {
            for (Listener listener : listeners) {
                listener.onWifiNetworksUpdated(results);
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void closeGatt() {
        if (gatt != null && hasConnectPermission()) {
            gatt.close();
        }
        gatt = null;
    }

    private void clearConnection(String status) {
        boardIp = null;
        responseCharacteristic = null;
        wifiConfigCharacteristic = null;
        post(() -> {
            for (Listener listener : listeners) {
                listener.onBoardConnectionChanged(false);
            }
        });
        emitStatus(status, COLOR_DEFAULT);
    }

    @SuppressLint("MissingPermission")
    public void restartConnection() {
        stopScan();
        closeGatt();
        boardIp = null;
        responseCharacteristic = null;
        wifiConfigCharacteristic = null;
        post(() -> {
            for (Listener listener : listeners) {
                listener.onBoardConnectionChanged(false);
            }
        });
        emitStatus("Restarting connection", COLOR_DEFAULT);
        startScan();
    }

    private void emitStatus(String status, int color) {
        post(() -> {
            for (Listener listener : listeners) {
                listener.onStatus(status, color);
            }
        });
    }

    private void emitLog(String line) {
        post(() -> {
            for (Listener listener : listeners) {
                listener.onLog(line);
            }
        });
    }

    private void post(Runnable runnable) {
        mainHandler.post(runnable);
    }
}
