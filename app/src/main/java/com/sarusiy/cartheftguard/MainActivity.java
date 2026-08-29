package com.sarusiy.cartheftguard;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
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
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String TARGET_NAME = "JC-P4-C6";
    private static final UUID SERVICE_UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    private static final UUID RESPONSE_UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb");
    private static final UUID WIFI_CONFIG_UUID = UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int REQUEST_BLE_PERMISSIONS = 42;
    private static final int MIN_FREQ_MS = 10;
    private static final int MAX_FREQ_MS = 60000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final Set<String> displayedAddresses = new HashSet<>();

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic responseCharacteristic;
    private BluetoothGattCharacteristic wifiConfigCharacteristic;
    private boolean scanning;
    private String boardIp;

    private TextView statusText;
    private TextView deviceText;
    private TextView logText;
    private LinearLayout scanResults;
    private EditText ssidInput;
    private EditText passwordInput;
    private EditText frequencyInput;
    private Button provisionButton;
    private Button frequencyButton;
    private Button scanButton;

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
            showDevice(device, name, result.getRssi());
            if (TARGET_NAME.equals(name)) {
                stopScan();
                connect(device);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            setStatus("Scan failed: " + errorCode);
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt connectedGatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                setStatus("Connected; discovering services");
                if (hasConnectPermission()) {
                    connectedGatt.discoverServices();
                }
                return;
            }
            clearConnection("Disconnected");
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt connectedGatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                setStatus("Service discovery failed: " + status);
                return;
            }
            BluetoothGattService service = connectedGatt.getService(SERVICE_UUID);
            if (service == null) {
                setStatus("Board service 0xFFF0 not found");
                return;
            }
            responseCharacteristic = service.getCharacteristic(RESPONSE_UUID);
            wifiConfigCharacteristic = service.getCharacteristic(WIFI_CONFIG_UUID);
            if (responseCharacteristic == null || wifiConfigCharacteristic == null) {
                setStatus("Install the current board firmware first");
                return;
            }
            subscribeToBoardResponses(connectedGatt);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt connectedGatt, BluetoothGattDescriptor descriptor, int status) {
            if (CCCD_UUID.equals(descriptor.getUuid()) && status == BluetoothGatt.GATT_SUCCESS) {
                appendLog("Board responses enabled");
                runOnUiThread(() -> provisionButton.setEnabled(true));
                setStatus("Enter Wi-Fi settings");
            } else {
                setStatus("Response subscription failed: " + status);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt connectedGatt, BluetoothGattCharacteristic characteristic) {
            if (!RESPONSE_UUID.equals(characteristic.getUuid())) {
                return;
            }
            String response = new String(characteristic.getValue(), StandardCharsets.UTF_8).trim();
            appendLog("Board: " + response);
            if (response.startsWith("WiFi connected ip=")) {
                boardIp = response.substring("WiFi connected ip=".length()).trim();
                runOnUiThread(() -> frequencyButton.setEnabled(true));
                setStatus("Wi-Fi ready: " + boardIp);
            } else {
                setStatus(response);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = manager == null ? null : manager.getAdapter();
        scanner = bluetoothAdapter == null ? null : bluetoothAdapter.getBluetoothLeScanner();
        setContentView(buildView());
        ensureBleReady();
    }

    @Override
    protected void onDestroy() {
        stopScan();
        closeGatt();
        networkExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLE_PERMISSIONS) {
            ensureBleReady();
        }
    }

    private ScrollView buildView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));
        root.setBackgroundColor(0xfff5f2ea);

        root.addView(label("CarTheftGuard v0.2.0", 28, true), matchWrap());
        statusText = label("Status: idle", 17, true);
        root.addView(statusText, matchWrapTop(16));
        deviceText = label("Board: not connected", 14, false);
        root.addView(deviceText, matchWrapTop(6));

        scanButton = primaryButton("Scan for Board");
        scanButton.setOnClickListener(view -> startScan());
        root.addView(scanButton, matchHeightTop(52, 18));
        scanResults = new LinearLayout(this);
        scanResults.setOrientation(LinearLayout.VERTICAL);
        root.addView(scanResults, matchWrapTop(10));

        root.addView(label("Wi-Fi setup", 16, true), matchWrapTop(24));
        ssidInput = input("Wi-Fi network name", InputType.TYPE_CLASS_TEXT);
        root.addView(ssidInput, matchHeightTop(54, 8));
        passwordInput = input("Wi-Fi password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(passwordInput, matchHeightTop(54, 8));
        provisionButton = primaryButton("Connect Board to Wi-Fi");
        provisionButton.setEnabled(false);
        provisionButton.setOnClickListener(view -> provisionWifi());
        root.addView(provisionButton, matchHeightTop(52, 12));

        root.addView(label("Blink half-period", 16, true), matchWrapTop(28));
        frequencyInput = input("Milliseconds: 10 to 60000", InputType.TYPE_CLASS_NUMBER);
        frequencyInput.setText("250");
        root.addView(frequencyInput, matchHeightTop(54, 8));
        LinearLayout presets = new LinearLayout(this);
        int[] values = {100, 250, 500, 1000};
        for (int value : values) {
            Button preset = secondaryButton(String.valueOf(value));
            preset.setOnClickListener(view -> frequencyInput.setText(String.valueOf(value)));
            presets.addView(preset, new LinearLayout.LayoutParams(0, dp(44), 1));
        }
        root.addView(presets, matchWrapTop(10));
        frequencyButton = primaryButton("Send Frequency Over Wi-Fi");
        frequencyButton.setEnabled(false);
        frequencyButton.setOnClickListener(view -> sendFrequencyOverWifi());
        root.addView(frequencyButton, matchHeightTop(52, 12));

        logText = label("", 13, false);
        logText.setTypeface(Typeface.MONOSPACE);
        root.addView(logText, matchWrapTop(18));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private void ensureBleReady() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            setStatus("Enable Bluetooth on this phone");
            return;
        }
        if (!hasBlePermissions()) {
            requestPermissions(requiredPermissions(), REQUEST_BLE_PERMISSIONS);
            return;
        }
        scanner = bluetoothAdapter.getBluetoothLeScanner();
        scanButton.setEnabled(scanner != null);
        setStatus(scanner == null ? "BLE scanner unavailable" : "Ready to scan");
    }

    @SuppressLint("MissingPermission")
    private void startScan() {
        if (!hasBlePermissions()) {
            requestPermissions(requiredPermissions(), REQUEST_BLE_PERMISSIONS);
            return;
        }
        if (scanning) {
            stopScan();
            return;
        }
        if (scanner == null) {
            setStatus("BLE scanner unavailable");
            return;
        }
        closeGatt();
        boardIp = null;
        responseCharacteristic = null;
        wifiConfigCharacteristic = null;
        displayedAddresses.clear();
        scanResults.removeAllViews();
        provisionButton.setEnabled(false);
        frequencyButton.setEnabled(false);
        scanning = true;
        scanButton.setText("Stop Scan");
        setStatus("Scanning for " + TARGET_NAME);
        scanner.startScan(scanCallback);
        handler.postDelayed(() -> {
            if (scanning) {
                stopScan();
                setStatus("Scan timed out");
            }
        }, 12000);
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        if (scanning && scanner != null && hasScanPermission()) {
            scanner.stopScan(scanCallback);
        }
        scanning = false;
        runOnUiThread(() -> scanButton.setText("Scan for Board"));
    }

    @SuppressLint("MissingPermission")
    private void showDevice(BluetoothDevice device, String name, int rssi) {
        String address = device.getAddress();
        if (!displayedAddresses.add(address)) {
            return;
        }
        String displayName = name == null || name.isEmpty() ? "Unnamed BLE device" : name;
        runOnUiThread(() -> {
            Button button = secondaryButton(displayName + "  " + address + "  " + rssi + " dBm");
            button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            button.setOnClickListener(view -> {
                stopScan();
                connect(device);
            });
            scanResults.addView(button, matchHeightTop(46, 6));
        });
    }

    @SuppressLint("MissingPermission")
    private void connect(BluetoothDevice device) {
        if (!hasConnectPermission()) {
            setStatus("Bluetooth connect permission missing");
            return;
        }
        setStatus("Connecting");
        deviceText.setText("Board: " + TARGET_NAME);
        gatt = device.connectGatt(this, false, gattCallback);
    }

    @SuppressLint("MissingPermission")
    private void subscribeToBoardResponses(BluetoothGatt connectedGatt) {
        if (!connectedGatt.setCharacteristicNotification(responseCharacteristic, true)) {
            setStatus("Cannot enable board responses");
            return;
        }
        BluetoothGattDescriptor descriptor = responseCharacteristic.getDescriptor(CCCD_UUID);
        if (descriptor == null) {
            setStatus("Response descriptor missing");
            return;
        }
        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        if (!connectedGatt.writeDescriptor(descriptor)) {
            setStatus("Cannot subscribe to board responses");
        }
    }

    @SuppressLint("MissingPermission")
    private void provisionWifi() {
        if (gatt == null || wifiConfigCharacteristic == null || !hasConnectPermission()) {
            setStatus("Connect to the board first");
            return;
        }
        String ssid = ssidInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        if (ssid.isEmpty() || password.length() < 8) {
            setStatus("Enter Wi-Fi name and password");
            return;
        }
        wifiConfigCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        wifiConfigCharacteristic.setValue((ssid + "\n" + password).getBytes(StandardCharsets.UTF_8));
        if (gatt.writeCharacteristic(wifiConfigCharacteristic)) {
            setStatus("Connecting board to Wi-Fi");
            appendLog("Wi-Fi credentials sent; waiting for board IP");
        } else {
            setStatus("Wi-Fi configuration was not accepted");
        }
    }

    private void sendFrequencyOverWifi() {
        if (boardIp == null || boardIp.isEmpty()) {
            setStatus("Connect the board to Wi-Fi first");
            return;
        }
        int value;
        try {
            value = Integer.parseInt(frequencyInput.getText().toString().trim());
        } catch (NumberFormatException exception) {
            setStatus("Enter a valid frequency value");
            return;
        }
        if (value < MIN_FREQ_MS || value > MAX_FREQ_MS) {
            setStatus("Use 10 to 60000 ms");
            return;
        }
        String command = "freq " + value;
        setStatus("Sending over Wi-Fi");
        networkExecutor.execute(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL("http://" + boardIp + "/api/frequency").openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setDoOutput(true);
                connection.getOutputStream().write(command.getBytes(StandardCharsets.UTF_8));
                int code = connection.getResponseCode();
                String response = readResponse(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
                connection.disconnect();
                appendLog("Wi-Fi: " + response);
                setStatus(response);
            } catch (Exception exception) {
                appendLog("Wi-Fi request failed: " + exception.getMessage());
                setStatus("Wi-Fi request failed");
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
        runOnUiThread(() -> {
            deviceText.setText("Board: not connected");
            provisionButton.setEnabled(false);
            frequencyButton.setEnabled(false);
        });
        setStatus(status);
    }

    private boolean hasBlePermissions() {
        for (String permission : requiredPermissions()) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private boolean hasScanPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private void setStatus(String status) {
        runOnUiThread(() -> statusText.setText("Status: " + status));
    }

    private void appendLog(String text) {
        runOnUiThread(() -> {
            String old = logText.getText().toString();
            logText.setText(old.isEmpty() ? text : old + "\n" + text);
        });
    }

    private TextView label(String text, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(0xff1f2933);
        view.setTextSize(size);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private EditText input(String hint, int type) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setInputType(type);
        return input;
    }

    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(0xffffffff);
        button.setAllCaps(false);
        button.setBackgroundColor(0xff0b6e69);
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(0xff0b6e69);
        button.setAllCaps(false);
        button.setBackgroundColor(0xffffffff);
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWrapTop(int top) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(top);
        return params;
    }

    private LinearLayout.LayoutParams matchHeightTop(int height, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height));
        params.topMargin = dp(top);
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
