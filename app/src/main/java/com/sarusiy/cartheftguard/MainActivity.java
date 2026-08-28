package com.sarusiy.cartheftguard;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
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
import android.widget.SeekBar;
import android.widget.TextView;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final String TARGET_NAME = "JC-P4-C6";
    private static final UUID SERVICE_UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    private static final UUID COMMAND_UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb");
    private static final int REQUEST_BLE_PERMISSIONS = 42;
    private static final int MIN_FREQ_MS = 10;
    private static final int MAX_FREQ_MS = 60000;
    private static final int DEFAULT_FREQ_MS = 250;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic commandCharacteristic;
    private boolean scanning;

    private TextView statusText;
    private TextView deviceText;
    private EditText frequencyInput;
    private SeekBar frequencySlider;
    private Button scanButton;
    private Button sendButton;
    private TextView logText;

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = getAdvertisedName(result, device);
            if (TARGET_NAME.equals(name)) {
                appendLog("Found " + TARGET_NAME);
                stopScan();
                connect(device);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            runOnUiThread(() -> {
                scanning = false;
                setStatus("Scan failed: " + errorCode);
                scanButton.setText("Scan");
            });
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                appendLog("Connected; discovering services");
                setStatus("Connected, discovering services");
                if (hasConnectPermission()) {
                    gatt.discoverServices();
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                commandCharacteristic = null;
                appendLog("Disconnected");
                setStatus("Disconnected");
                runOnUiThread(() -> {
                    deviceText.setText("Device: none");
                    sendButton.setEnabled(false);
                    scanButton.setEnabled(true);
                    scanButton.setText("Scan");
                });
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                setStatus("Service discovery failed: " + status);
                return;
            }

            BluetoothGattService service = gatt.getService(SERVICE_UUID);
            if (service == null) {
                setStatus("Service 0xFFF0 not found");
                return;
            }

            commandCharacteristic = service.getCharacteristic(COMMAND_UUID);
            if (commandCharacteristic == null) {
                setStatus("Characteristic 0xFFF1 not found");
                return;
            }

            appendLog("Service 0xFFF0 and characteristic 0xFFF1 ready");
            runOnUiThread(() -> {
                setStatus("Ready");
                sendButton.setEnabled(true);
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                setStatus("Command sent");
                appendLog("Write OK");
            } else {
                setStatus("Write failed: " + status);
                appendLog("Write failed: " + status);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;
        scanner = bluetoothAdapter != null ? bluetoothAdapter.getBluetoothLeScanner() : null;

        setContentView(buildContentView());
        syncPermissionState();
    }

    @Override
    protected void onDestroy() {
        stopScan();
        closeGatt();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLE_PERMISSIONS) {
            syncPermissionState();
        }
    }

    private ScrollView buildContentView() {
        int padding = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);
        root.setBackgroundColor(0xfff5f2ea);

        TextView title = new TextView(this);
        title.setText("CarTheftGuard");
        title.setTextColor(0xff1f2933);
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, matchWrap());

        statusText = label("Status: idle", 18, true);
        root.addView(statusText, matchWrapTop(18));

        deviceText = label("Device: none", 15, false);
        root.addView(deviceText, matchWrapTop(8));

        scanButton = primaryButton("Scan");
        scanButton.setOnClickListener(view -> startScanFlow());
        root.addView(scanButton, matchHeightTop(52, 24));

        TextView freqLabel = label("Blink half-period", 16, true);
        root.addView(freqLabel, matchWrapTop(28));

        frequencyInput = new EditText(this);
        frequencyInput.setText(String.valueOf(DEFAULT_FREQ_MS));
        frequencyInput.setSelectAllOnFocus(true);
        frequencyInput.setSingleLine(true);
        frequencyInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        frequencyInput.setTextSize(20);
        frequencyInput.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(frequencyInput, matchHeightTop(56, 8));

        frequencySlider = new SeekBar(this);
        frequencySlider.setMax(4990);
        frequencySlider.setProgress(valueToSlider(DEFAULT_FREQ_MS));
        frequencySlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    frequencyInput.setText(String.valueOf(sliderToValue(progress)));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        root.addView(frequencySlider, matchWrapTop(12));

        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        int[] presetValues = {100, 250, 500, 1000};
        for (int value : presetValues) {
            Button preset = secondaryButton(String.valueOf(value));
            preset.setOnClickListener(view -> setFrequency(value));
            presets.addView(preset, new LinearLayout.LayoutParams(0, dp(44), 1));
        }
        root.addView(presets, matchWrapTop(12));

        sendButton = primaryButton("Send Frequency");
        sendButton.setEnabled(false);
        sendButton.setOnClickListener(view -> sendFrequency());
        root.addView(sendButton, matchHeightTop(52, 24));

        logText = label("", 13, false);
        logText.setTypeface(Typeface.MONOSPACE);
        root.addView(logText, matchWrapTop(20));

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(root);
        return scrollView;
    }

    private void syncPermissionState() {
        if (bluetoothAdapter == null) {
            setStatus("Bluetooth not available");
            scanButton.setEnabled(false);
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            setStatus("Enable Bluetooth on this phone");
            scanButton.setEnabled(false);
            return;
        }
        if (!hasAllPermissions()) {
            requestPermissions(requiredPermissions(), REQUEST_BLE_PERMISSIONS);
            setStatus("Bluetooth permission required");
            return;
        }
        scanner = bluetoothAdapter.getBluetoothLeScanner();
        scanButton.setEnabled(scanner != null);
        setStatus(scanner == null ? "BLE scanner unavailable" : "Ready to scan");
    }

    private void startScanFlow() {
        if (!hasAllPermissions()) {
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
        commandCharacteristic = null;
        sendButton.setEnabled(false);
        setStatus("Scanning for " + TARGET_NAME);
        appendLog("Scanning");
        scanButton.setText("Stop");
        scanning = true;
        scanner.startScan(scanCallback);
        mainHandler.postDelayed(this::stopScanIfStillScanning, 12000);
    }

    private void stopScanIfStillScanning() {
        if (scanning) {
            stopScan();
            setStatus("Scan timed out");
        }
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        if (!scanning || scanner == null || !hasScanPermission()) {
            scanning = false;
            runOnUiThread(() -> scanButton.setText("Scan"));
            return;
        }
        scanner.stopScan(scanCallback);
        scanning = false;
        runOnUiThread(() -> scanButton.setText("Scan"));
    }

    @SuppressLint("MissingPermission")
    private void connect(BluetoothDevice device) {
        if (!hasConnectPermission()) {
            setStatus("Bluetooth connect permission missing");
            return;
        }
        runOnUiThread(() -> {
            setStatus("Connecting");
            deviceText.setText("Device: " + TARGET_NAME);
            scanButton.setEnabled(false);
        });
        gatt = device.connectGatt(this, false, gattCallback);
    }

    @SuppressLint("MissingPermission")
    private void sendFrequency() {
        if (gatt == null || commandCharacteristic == null) {
            setStatus("Connect first");
            return;
        }
        if (!hasConnectPermission()) {
            setStatus("Bluetooth connect permission missing");
            return;
        }

        int value = parseFrequency();
        if (value < MIN_FREQ_MS || value > MAX_FREQ_MS) {
            setStatus(String.format(Locale.US, "Use %d-%d ms", MIN_FREQ_MS, MAX_FREQ_MS));
            return;
        }

        String command = "freq " + value;
        byte[] payload = command.getBytes(StandardCharsets.UTF_8);
        commandCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        commandCharacteristic.setValue(payload);
        boolean accepted = gatt.writeCharacteristic(commandCharacteristic);
        appendLog(command + (accepted ? " queued" : " not queued"));
        setStatus(accepted ? "Sending " + command : "Write was not accepted");
    }

    private int parseFrequency() {
        try {
            return Integer.parseInt(frequencyInput.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private void setFrequency(int value) {
        frequencyInput.setText(String.valueOf(value));
        frequencySlider.setProgress(valueToSlider(value));
    }

    private int valueToSlider(int value) {
        int clamped = Math.max(MIN_FREQ_MS, Math.min(MAX_FREQ_MS, value));
        double normalized = Math.log10(clamped / 10.0) / Math.log10(MAX_FREQ_MS / 10.0);
        return (int) Math.round(normalized * frequencySliderMax());
    }

    private int sliderToValue(int progress) {
        double normalized = progress / (double) frequencySliderMax();
        double value = 10.0 * Math.pow(MAX_FREQ_MS / 10.0, normalized);
        return Math.max(MIN_FREQ_MS, Math.min(MAX_FREQ_MS, (int) Math.round(value)));
    }

    private int frequencySliderMax() {
        return 4990;
    }

    private void closeGatt() {
        if (gatt != null && hasConnectPermission()) {
            gatt.close();
        }
        gatt = null;
    }

    private String getDeviceName(BluetoothDevice device) {
        if (!hasConnectPermission()) {
            return null;
        }
        return device.getName();
    }

    private String getAdvertisedName(ScanResult result, BluetoothDevice device) {
        if (result.getScanRecord() != null && result.getScanRecord().getDeviceName() != null) {
            return result.getScanRecord().getDeviceName();
        }
        return getDeviceName(device);
    }

    private boolean hasAllPermissions() {
        for (String permission : requiredPermissions()) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private boolean hasScanPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private String[] requiredPermissions() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        return permissions.toArray(new String[0]);
    }

    private void setStatus(String text) {
        runOnUiThread(() -> statusText.setText("Status: " + text));
    }

    private void appendLog(String text) {
        runOnUiThread(() -> {
            String existing = logText.getText().toString();
            String next = existing.isEmpty() ? text : existing + "\n" + text;
            logText.setText(next);
        });
    }

    private TextView label(String text, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(0xff1f2933);
        view.setTextSize(sp);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(0xffffffff);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setBackgroundColor(0xff0b6e69);
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(0xff0b6e69);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setBackgroundColor(0xffffffff);
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWrapTop(int topDp) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(topDp);
        return params;
    }

    private LinearLayout.LayoutParams matchHeightTop(int heightDp, int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp));
        params.topMargin = dp(topDp);
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
