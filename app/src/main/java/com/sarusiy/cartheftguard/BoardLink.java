package com.sarusiy.cartheftguard;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiNetworkSpecifier;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Owns the board's connection, independent of any single Activity/Fragment, so
 * the Connect, Monitor and Control screens can all observe the same live
 * state. Connection is one path, always: BLE scanning detects the board is
 * physically nearby (its advertised name, "JC-P4-C6"), which automatically
 * triggers joining the board's own always-on Wi-Fi AP in the background via
 * Android's WifiNetworkSpecifier -- no manual Wi-Fi switching, no home
 * network involved, no credentials form. This mirrors how most consumer
 * BLE+Wi-Fi car accessories connect (pair once over BLE, everything else
 * happens automatically), and generalizes to future boards: each just needs
 * its own BLE name and AP credentials.
 */
public final class BoardLink {
    public static final String TARGET_NAME = "JC-P4-C6";
    public static final String AP_SSID = "CarTheftGuard-P4";
    public static final String AP_PASSWORD = "theftguard2026";
    public static final String AP_IP = "192.168.4.1";
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
        default void onWifiConnected(String boardIp) {}
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
    private final ConnectivityManager connectivityManager;
    private BluetoothLeScanner scanner;
    private boolean scanning;
    /* True once BLE scanning has seen the board's advertisement. This is the
     * "device is physically present" signal that triggers joining its Wi-Fi
     * AP -- there is no separate BLE GATT session to hold open afterward. */
    private boolean boardPresent;
    /* The specific network object for the board's AP, obtained from
     * ConnectivityManager once WifiNetworkSpecifier succeeds. All HTTP calls
     * bind to this explicitly (network.openConnection(url)) instead of using
     * the phone's default network, so this works correctly regardless of
     * whatever the phone's normal Wi-Fi/cellular connection is doing. */
    private volatile Network boardNetwork;
    private ConnectivityManager.NetworkCallback networkCallback;
    private Runnable connectTimeoutRunnable;
    private final AtomicBoolean obdFetchInFlight = new AtomicBoolean(false);
    private final AtomicBoolean gpsFetchInFlight = new AtomicBoolean(false);
    private final AtomicBoolean dtcFetchInFlight = new AtomicBoolean(false);

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
            if (TARGET_NAME.equals(name) && !boardPresent) {
                stopScan();
                boardPresent = true;
                post(() -> {
                    for (Listener listener : listeners) {
                        listener.onBoardConnectionChanged(true);
                    }
                });
                emitStatus("Board found; joining its Wi-Fi", COLOR_PROGRESS);
                connectToBoardNetwork();
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            emitStatus("Scan failed: " + errorCode, COLOR_ERROR);
        }
    };

    private BoardLink(Context appContext) {
        this.appContext = appContext;
        BluetoothManager manager = (BluetoothManager) appContext.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = manager == null ? null : manager.getAdapter();
        scanner = bluetoothAdapter == null ? null : bluetoothAdapter.getBluetoothLeScanner();
        connectivityManager = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
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
        return boardPresent;
    }

    public boolean isWifiReady() {
        return boardNetwork != null;
    }

    public void forcePassiveCanMode() {
        if (!isWifiReady()) {
            return;
        }
        networkExecutor.execute(() -> ensurePassiveModeIfNeeded(boardNetwork));
    }

    public void fetchCanMode(Consumer<String> callback) {
        if (callback == null) {
            return;
        }
        Network network = boardNetwork;
        if (network == null) {
            callback.accept("PASSIVE");
            return;
        }
        networkExecutor.execute(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) network.openConnection(new URL("http://" + AP_IP + "/api/can"));
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
        Network network = boardNetwork;
        if (network == null) {
            emitStatus("Connect to the board first", COLOR_ERROR);
            if (callback != null) {
                post(() -> callback.accept(false));
            }
            return;
        }
        networkExecutor.execute(() -> {
            boolean success = false;
            try {
                byte[] body = mode.getBytes(StandardCharsets.UTF_8);
                HttpURLConnection connection = (HttpURLConnection) network.openConnection(new URL("http://" + AP_IP + "/api/can/mode"));
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

    /**
     * Who's actually on the other end of the CAN bus and which OBD-II
     * addressing scheme they use: "SIM_11"/"SIM_29" (the bench Arduino
     * simulator), "CAR_11"/"CAR_29" (a real vehicle), or "UNKNOWN" if
     * neither has been determined yet. Re-checked by the board on every
     * call (not cached), so this is safe to poll periodically.
     */
    public void fetchCanPartner(Consumer<String> callback) {
        if (callback == null) {
            return;
        }
        Network network = boardNetwork;
        if (network == null) {
            callback.accept("UNKNOWN");
            return;
        }
        networkExecutor.execute(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) network.openConnection(new URL("http://" + AP_IP + "/api/can/partner"));
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
                JSONObject partnerState = new JSONObject(response);
                callback.accept(partnerState.optString("partner", "UNKNOWN"));
            } catch (Exception exception) {
                callback.accept("UNKNOWN");
            }
        });
    }

    /**
     * Remote-switches which OBD-II addressing scheme the bench simulator
     * answers on ("11" or "29") -- only meaningful when the simulator is on
     * the bus (a real vehicle just ignores this private command). The
     * Control tab only shows this while fetchCanPartner reports SIM_*.
     */
    public void setSimulatorMode(String mode, Consumer<Boolean> callback) {
        Network network = boardNetwork;
        if (network == null) {
            emitStatus("Connect to the board first", COLOR_ERROR);
            if (callback != null) {
                post(() -> callback.accept(false));
            }
            return;
        }
        networkExecutor.execute(() -> {
            boolean success = false;
            try {
                byte[] body = mode.getBytes(StandardCharsets.UTF_8);
                HttpURLConnection connection = (HttpURLConnection) network.openConnection(new URL("http://" + AP_IP + "/api/can/sim_mode"));
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
                    emitLog("Simulator mode switch failed: HTTP " + code + " -> " + response);
                } else {
                    emitLog("Simulator mode switch to " + mode + "-bit: " + response);
                    success = true;
                }
            } catch (Exception exception) {
                emitLog("Simulator mode switch failed: " + exception.getMessage());
            }
            boolean finalSuccess = success;
            if (callback != null) {
                post(() -> callback.accept(finalSuccess));
            }
        });
    }

    private void ensurePassiveModeIfNeeded(Network network) {
        try {
            HttpURLConnection statusConnection = (HttpURLConnection) network.openConnection(new URL("http://" + AP_IP + "/api/can"));
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
            HttpURLConnection connection = (HttpURLConnection) network.openConnection(new URL("http://" + AP_IP + "/api/can/mode"));
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
        return isWifiReady() ? AP_IP : null;
    }

    /**
     * The specific scoped network for the board's AP, obtained via
     * WifiNetworkSpecifier -- null until connectToBoardNetwork() succeeds.
     * Anything making its own HTTP calls to the board outside this class
     * (e.g. CanCaptureService, which runs as its own component) must bind to
     * this explicitly via {@code network.openConnection(url)} rather than a
     * plain {@code new URL(...).openConnection()}, since this network is not
     * the phone's default route.
     */
    public Network getBoardNetwork() {
        return boardNetwork;
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

    private boolean hasScanPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
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
        scanning = true;
        post(() -> {
            for (Listener listener : listeners) {
                listener.onScanningChanged(true);
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

    /**
     * Joins the board's own Wi-Fi AP automatically in the background, the
     * same way most BLE+Wi-Fi car accessories connect: no manual network
     * switching, and critically, this uses a scoped local network request
     * (WifiNetworkSpecifier) rather than changing the phone's actual Wi-Fi
     * connection -- the phone's normal internet route (cellular/home Wi-Fi)
     * is completely unaffected. All HTTP calls to the board explicitly bind
     * to the returned Network object instead of relying on whatever the
     * phone's default network happens to be.
     */
    private void connectToBoardNetwork() {
        if (connectivityManager == null) {
            emitStatus("Wi-Fi connectivity service unavailable", COLOR_ERROR);
            return;
        }
        if (networkCallback != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
            networkCallback = null;
        }
        WifiNetworkSpecifier specifier = new WifiNetworkSpecifier.Builder()
                .setSsid(AP_SSID)
                .setWpa2Passphrase(AP_PASSWORD)
                .build();
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build();
        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                mainHandler.removeCallbacks(connectTimeoutRunnable);
                boardNetwork = network;
                emitLog("Joined board Wi-Fi: " + AP_SSID);
                networkExecutor.execute(() -> {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    ensurePassiveModeIfNeeded(network);
                });
                post(() -> {
                    for (Listener listener : listeners) {
                        listener.onWifiConnected(AP_IP);
                    }
                });
                emitStatus("Wi-Fi ready: " + AP_IP, COLOR_SUCCESS);
            }

            @Override
            public void onLost(Network network) {
                if (network.equals(boardNetwork)) {
                    boardNetwork = null;
                    emitStatus("Board Wi-Fi lost", COLOR_ERROR);
                }
            }
        };
        networkCallback = callback;
        /* requestNetwork(request, callback) -- the plain two-argument
         * overload -- runs indefinitely with no built-in timeout or
         * onUnavailable() callback (unlike the three-argument overload,
         * which additionally requires the CHANGE_NETWORK_STATE permission
         * this app doesn't hold, purely for its timeout bookkeeping). Time
         * it out manually instead so the UI doesn't wait forever if the
         * board's AP genuinely isn't in range. */
        connectTimeoutRunnable = () -> {
            if (networkCallback == callback && boardNetwork == null) {
                connectivityManager.unregisterNetworkCallback(callback);
                networkCallback = null;
                emitStatus("Could not join board Wi-Fi", COLOR_ERROR);
            }
        };
        emitStatus("Joining board Wi-Fi...", COLOR_PROGRESS);
        connectivityManager.requestNetwork(request, callback);
        mainHandler.postDelayed(connectTimeoutRunnable, 20000);
    }

    public void sendFrequency(int value) {
        Network network = boardNetwork;
        if (network == null) {
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
                HttpURLConnection connection = (HttpURLConnection) network.openConnection(new URL("http://" + AP_IP + "/api/frequency"));
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
        Network network = boardNetwork;
        if (network == null) {
            return;
        }
        if (!obdFetchInFlight.compareAndSet(false, true)) {
            return;
        }
        networkExecutor.execute(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) network.openConnection(new URL("http://" + AP_IP + "/api/obd"));
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
        Network network = boardNetwork;
        if (network == null) {
            return;
        }
        if (!gpsFetchInFlight.compareAndSet(false, true)) {
            return;
        }
        networkExecutor.execute(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) network.openConnection(new URL("http://" + AP_IP + "/api/gps"));
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
        Network network = boardNetwork;
        if (network == null) {
            return;
        }
        if (!dtcFetchInFlight.compareAndSet(false, true)) {
            return;
        }
        networkExecutor.execute(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) network.openConnection(new URL("http://" + AP_IP + "/api/dtc"));
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
        Network network = boardNetwork;
        if (network == null) {
            emitStatus("Connect the board to Wi-Fi first", COLOR_ERROR);
            return;
        }
        networkExecutor.execute(() -> {
            try {
                byte[] body = String.valueOf(faultIndex).getBytes(StandardCharsets.UTF_8);
                HttpURLConnection connection = (HttpURLConnection) network.openConnection(new URL("http://" + AP_IP + "/api/dtc/simulate"));
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
        Network network = boardNetwork;
        if (network == null) {
            emitStatus("Connect the board to Wi-Fi first", COLOR_ERROR);
            return;
        }
        networkExecutor.execute(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) network.openConnection(new URL("http://" + AP_IP + "/api/dtc/clear"));
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
        Network network = boardNetwork;
        if (network == null) {
            return;
        }
        networkExecutor.execute(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) network.openConnection(new URL(
                        "http://" + AP_IP + "/api/can?after=" + after));
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

    private void clearConnection(String status) {
        boardPresent = false;
        boardNetwork = null;
        if (connectTimeoutRunnable != null) {
            mainHandler.removeCallbacks(connectTimeoutRunnable);
            connectTimeoutRunnable = null;
        }
        if (networkCallback != null && connectivityManager != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
            networkCallback = null;
        }
        post(() -> {
            for (Listener listener : listeners) {
                listener.onBoardConnectionChanged(false);
            }
        });
        emitStatus(status, COLOR_DEFAULT);
    }

    public void restartConnection() {
        stopScan();
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
