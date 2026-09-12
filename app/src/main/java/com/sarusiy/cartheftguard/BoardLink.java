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

import org.json.JSONObject;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

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
        default void onGpsData(String json) {}
        default void onDtcData(String json) {}
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
    private final AtomicBoolean obdFetchInFlight = new AtomicBoolean(false);
    private final AtomicBoolean gpsFetchInFlight = new AtomicBoolean(false);
    private final AtomicBoolean dtcFetchInFlight = new AtomicBoolean(false);
    /* Set right after BLE subscribe completes, cleared once we know the actual
     * Wi-Fi state (either an already-connected IP arrives, or the grace period
     * below elapses with nothing). Lets onWifiConnected cancel the pending
     * "show Wi-Fi setup" prompt below if the board turns out to already be
     * on a known network, instead of always flashing that form on every
     * connect regardless of whether it's actually needed. */
    private Runnable pendingWifiSetupPrompt;

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
                    }
                });
                emitStatus("Checking Wi-Fi status...", COLOR_DEFAULT);
                /* Actively read the response characteristic's current value
                 * right away, rather than relying solely on the firmware's
                 * notify push (which is fire-and-forget and can be dropped,
                 * especially with MTU negotiation/service discovery/this very
                 * CCCD write all still settling around the same moment).
                 * The read is answered in onCharacteristicRead above and
                 * reuses the exact same handleBoardResponseText() parsing. */
                if (hasConnectPermission() && responseCharacteristic != null) {
                    connectedGatt.readCharacteristic(responseCharacteristic);
                }
                /* Don't show the Wi-Fi setup form immediately: the firmware
                 * resends "WiFi connected ip=..." right after this subscribe
                 * completes if it's already on a known network (see
                 * ble_gap_event's BLE_GAP_EVENT_SUBSCRIBE handling on the P4).
                 * Give that a short grace period to arrive -- if it does,
                 * onWifiConnected cancels this runnable below and the user
                 * never sees an unnecessary "enter your Wi-Fi" prompt. Only
                 * show it if nothing arrived, meaning the board genuinely
                 * has no working Wi-Fi yet. */
                pendingWifiSetupPrompt = () -> {
                    pendingWifiSetupPrompt = null;
                    /* Defensive re-check: don't blindly show the Wi-Fi form
                     * just because this timer fired. If Wi-Fi actually became
                     * ready in the meantime through any other path, showing
                     * the form now would be wrong and confusing regardless of
                     * how that happened. */
                    if (isWifiReady()) {
                        return;
                    }
                    for (Listener listener : listeners) {
                        listener.onWifiSetupReady();
                    }
                    emitStatus("Enter Wi-Fi settings", COLOR_SUCCESS);
                };
                mainHandler.postDelayed(pendingWifiSetupPrompt, 1500);
            } else {
                emitStatus("Response subscription failed: " + status, COLOR_ERROR);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt connectedGatt, BluetoothGattCharacteristic characteristic) {
            if (!RESPONSE_UUID.equals(characteristic.getUuid())) {
                return;
            }
            handleBoardResponseText(new String(characteristic.getValue(), StandardCharsets.UTF_8).trim());
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt connectedGatt, BluetoothGattCharacteristic characteristic, int status) {
            /* Explicit read fallback for the same response characteristic
             * handled by onCharacteristicChanged above. BLE notifications are
             * fire-and-forget with no delivery guarantee at the app layer,
             * and can be silently dropped when other GATT operations (MTU
             * negotiation, service discovery, the CCCD write itself) are
             * still settling right around subscribe time -- exactly when the
             * firmware's one-shot "WiFi connected ip=..." push fires. A
             * direct read is a deterministic request/response instead, so it
             * reliably retrieves the board's last published message even if
             * the notification for it never arrived. Triggered right after
             * the CCCD write succeeds, see onDescriptorWrite below. */
            if (!RESPONSE_UUID.equals(characteristic.getUuid()) || status != BluetoothGatt.GATT_SUCCESS) {
                return;
            }
            handleBoardResponseText(new String(characteristic.getValue(), StandardCharsets.UTF_8).trim());
        }
    };

    private void handleBoardResponseText(String response) {
            emitLog("Board: " + response);
            if (response.startsWith("WiFi connected ip=")) {
                boardIp = response.substring("WiFi connected ip=".length()).trim();
                if (pendingWifiSetupPrompt != null) {
                    mainHandler.removeCallbacks(pendingWifiSetupPrompt);
                    pendingWifiSetupPrompt = null;
                }
                networkExecutor.execute(() -> {
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    ensurePassiveModeIfNeeded(boardIp);
                });
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

    public void forcePassiveCanMode() {
        if (!isWifiReady()) {
            return;
        }
        networkExecutor.execute(() -> ensurePassiveModeIfNeeded(boardIp));
    }

    public void fetchCanMode(Consumer<String> callback) {
        if (callback == null) {
            return;
        }
        if (!isWifiReady()) {
            callback.accept("PASSIVE");
            return;
        }
        networkExecutor.execute(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL("http://" + boardIp + "/api/can").openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(3000);
                int code = connection.getResponseCode();
                String response = readResponse(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
                connection.disconnect();
                if (code >= 400) {
                    callback.accept("UNKNOWN");
                    return;
                }
                JSONObject canState = new JSONObject(response);
                callback.accept(canState.optBoolean("passive", true) ? "PASSIVE" : "ACTIVE");
            } catch (Exception exception) {
                callback.accept("UNKNOWN");
            }
        });
    }

    /**
     * Explicitly requests a CAN mode change ("active" or "passive") from the UI.
     * Unlike {@link #ensurePassiveModeIfNeeded}, this is a direct user action and
     * is allowed to switch the board to active mode -- the board still defaults to
     * passive on every boot and Wi-Fi reconnect, so this never changes that safe
     * default, only the current live session.
     */
    public void setCanMode(String mode, Consumer<Boolean> callback) {
        if (!isWifiReady()) {
            emitStatus("Connect the board to Wi-Fi first", COLOR_ERROR);
            if (callback != null) {
                post(() -> callback.accept(false));
            }
            return;
        }
        networkExecutor.execute(() -> {
            boolean success = false;
            try {
                byte[] body = mode.getBytes(StandardCharsets.UTF_8);
                HttpURLConnection connection = (HttpURLConnection) new URL("http://" + boardIp + "/api/can/mode").openConnection();
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
                if (code >= 400) {
                    emitLog("CAN mode set failed: HTTP " + code + " -> " + response);
                } else {
                    emitLog("CAN mode set to " + mode + ": " + response);
                    success = true;
                }
            } catch (Exception exception) {
                emitLog("CAN mode set failed: " + exception.getMessage());
            }
            boolean finalSuccess = success;
            if (callback != null) {
                post(() -> callback.accept(finalSuccess));
            }
        });
    }

    private void ensurePassiveModeIfNeeded(String boardIp) {
        try {
            HttpURLConnection statusConnection = (HttpURLConnection) new URL("http://" + boardIp + "/api/can").openConnection();
            statusConnection.setRequestMethod("GET");
            statusConnection.setConnectTimeout(3000);
            statusConnection.setReadTimeout(3000);
            int code = statusConnection.getResponseCode();
            String response = readResponse(code >= 400 ? statusConnection.getErrorStream() : statusConnection.getInputStream());
            statusConnection.disconnect();
            if (code >= 400) {
                emitLog("Board status check failed during passive sync: HTTP " + code + " -> " + response);
                return;
            }
            JSONObject canState = new JSONObject(response);
            if (canState.optBoolean("passive", true)) {
                emitLog("Board already passive on Wi-Fi connect; no mode change required.");
                return;
            }
        } catch (Exception ignored) {
            emitLog("Board status check timed out during passive sync; skipping active-to-passive correction.");
            return;
        }

        try {
            byte[] body = "passive".getBytes(StandardCharsets.UTF_8);
            HttpURLConnection connection = (HttpURLConnection) new URL("http://" + boardIp + "/api/can/mode").openConnection();
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
            if (code >= 400) {
                emitLog("CAN mode set failed: HTTP " + code + " -> " + response);
                return;
            }
            emitLog("CAN mode corrected to passive: " + response);
        } catch (Exception exception) {
            emitLog("Passive mode correction failed: " + exception.getMessage());
        }
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
        if (!obdFetchInFlight.compareAndSet(false, true)) {
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
            } finally {
                obdFetchInFlight.set(false);
            }
        });
    }

    public void fetchGpsData() {
        if (!isWifiReady()) {
            return;
        }
        if (!gpsFetchInFlight.compareAndSet(false, true)) {
            return;
        }
        networkExecutor.execute(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL("http://" + boardIp + "/api/gps").openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(3000);
                int code = connection.getResponseCode();
                String response = readResponse(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
                connection.disconnect();
                if (code >= 400) {
                    emitLog("GPS monitor request failed: HTTP " + code);
                    return;
                }
                post(() -> {
                    for (Listener listener : listeners) {
                        listener.onGpsData(response);
                    }
                });
            } catch (Exception exception) {
                emitLog("GPS monitor request failed: " + exception.getMessage());
            } finally {
                gpsFetchInFlight.set(false);
            }
        });
    }

    public void fetchDtcs() {
        if (!isWifiReady()) {
            return;
        }
        if (!dtcFetchInFlight.compareAndSet(false, true)) {
            return;
        }
        networkExecutor.execute(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL("http://" + boardIp + "/api/dtc").openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(3000);
                int code = connection.getResponseCode();
                String response = readResponse(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
                connection.disconnect();
                if (code >= 400) {
                    emitLog("Fault list request failed: HTTP " + code);
                    return;
                }
                post(() -> {
                    for (Listener listener : listeners) {
                        listener.onDtcData(response);
                    }
                });
            } catch (Exception exception) {
                emitLog("Fault list request failed: " + exception.getMessage());
            } finally {
                dtcFetchInFlight.set(false);
            }
        });
    }

    public void simulateFault(int faultIndex) {
        if (!isWifiReady()) {
            emitStatus("Connect the board to Wi-Fi first", COLOR_ERROR);
            return;
        }
        networkExecutor.execute(() -> {
            try {
                byte[] body = String.valueOf(faultIndex).getBytes(StandardCharsets.UTF_8);
                HttpURLConnection connection = (HttpURLConnection) new URL("http://" + boardIp + "/api/dtc/simulate").openConnection();
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
                emitLog("Fault simulate: " + response);
                fetchDtcs();
            } catch (Exception exception) {
                emitLog("Fault simulate request failed: " + exception.getMessage());
                emitStatus("Fault simulate request failed", COLOR_ERROR);
            }
        });
    }

    public void clearDtcs() {
        if (!isWifiReady()) {
            emitStatus("Connect the board to Wi-Fi first", COLOR_ERROR);
            return;
        }
        networkExecutor.execute(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL("http://" + boardIp + "/api/dtc/clear").openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setFixedLengthStreamingMode(0);
                connection.setDoOutput(true);
                connection.getOutputStream().close();
                int code = connection.getResponseCode();
                String response = readResponse(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
                connection.disconnect();
                emitLog("Fault clear: " + response);
                fetchDtcs();
            } catch (Exception exception) {
                emitLog("Fault clear request failed: " + exception.getMessage());
                emitStatus("Fault clear request failed", COLOR_ERROR);
            }
        });
    }

    /**
     * Raw CAN frames since {@code after} (0 for "from the start of the ring
     * buffer"), independent of whether CanCaptureService is also recording to
     * a CSV file -- both are just separate consumers of the same
     * GET /api/can endpoint, so watching live doesn't require recording.
     */
    public void fetchCanRaw(long after, Consumer<String> callback) {
        if (callback == null) {
            return;
        }
        if (!isWifiReady()) {
            return;
        }
        networkExecutor.execute(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(
                        "http://" + boardIp + "/api/can?after=" + after).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(3000);
                int code = connection.getResponseCode();
                String response = readResponse(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
                connection.disconnect();
                if (code >= 400) {
                    return;
                }
                post(() -> callback.accept(response));
            } catch (Exception exception) {
                emitLog("Raw CAN fetch failed: " + exception.getMessage());
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
        if (pendingWifiSetupPrompt != null) {
            mainHandler.removeCallbacks(pendingWifiSetupPrompt);
            pendingWifiSetupPrompt = null;
        }
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
        /* Reuse clearConnection() rather than duplicating its cleanup here --
         * this used to be a hand-copied subset that predated (and therefore
         * never cancelled) pendingWifiSetupPrompt, so restarting mid-connection
         * could leave a stale "show Wi-Fi setup" callback armed on the Handler
         * queue, which then fired against the NEW connection and showed the
         * form even when the new one was already fully connected. */
        clearConnection("Restarting connection");
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
