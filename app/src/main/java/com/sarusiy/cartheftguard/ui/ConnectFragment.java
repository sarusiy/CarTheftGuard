package com.sarusiy.cartheftguard.ui;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.sarusiy.cartheftguard.BoardLink;
import com.sarusiy.cartheftguard.SecureWifiStore;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** "Connect" tab: BLE scan/pair with the board, then hand it Wi-Fi credentials (saved securely for next time). */
public class ConnectFragment extends Fragment implements BoardLink.Listener {

    private BoardLink boardLink;
    private SecureWifiStore secureWifiStore;

    private final ActivityResultLauncher<String[]> blePermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(),
            grants -> {
                if (!grants.isEmpty() && !grants.containsValue(false)) {
                    ensureBleReady();
                } else {
                    setStatus("Bluetooth permission denied. Tap 'Open Permission Settings' below.", BoardLink.COLOR_ERROR);
                }
            });

    private final ActivityResultLauncher<String[]> wifiPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(),
            grants -> {
                if (!grants.isEmpty() && !grants.containsValue(false)) {
                    boardLink.refreshWifiNetworks();
                } else {
                    setStatus("Location permission denied. Tap 'Open Permission Settings' below.", BoardLink.COLOR_ERROR);
                }
            });

    private final Set<String> displayedAddresses = new HashSet<>();

    private TextView statusText;
    private TextView deviceText;
    private TextView logText;
    private LinearLayout scanSection;
    private LinearLayout scanResults;
    private LinearLayout wifiSetupSection;
    private LinearLayout wifiNetworks;
    private EditText ssidInput;
    private EditText passwordInput;
    private Button provisionButton;
    private Button scanButton;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        boardLink = BoardLink.getInstance(context);
        secureWifiStore = new SecureWifiStore(context);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return buildView();
    }

    @Override
    public void onStart() {
        super.onStart();
        boardLink.addListener(this);
        ensureBleReady();
        deviceText.setText(boardLink.isBoardConnected() ? "Board: " + BoardLink.TARGET_NAME : "Board: not connected");
        if (!boardLink.isBoardConnected()) {
            prefillSavedWifi();
        }
    }

    @Override
    public void onStop() {
        boardLink.removeListener(this);
        super.onStop();
    }

    private void prefillSavedWifi() {
        if (secureWifiStore.hasSavedCredentials() && ssidInput.getText().length() == 0) {
            ssidInput.setText(secureWifiStore.getSsid());
            passwordInput.setText(secureWifiStore.getPassword());
            appendLog("Loaded saved Wi-Fi network: " + secureWifiStore.getSsid());
        }
    }

    private View buildView() {
        Context context = requireContext();
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = Views.dp(context, 20);
        root.setPadding(padding, padding, padding, padding);
        root.setBackgroundColor(0xfff5f2ea);

        root.addView(Views.label(context, "Connect", 24, true), Views.matchWrap());
        statusText = Views.label(context, "Status: idle", 17, true);
        root.addView(statusText, Views.matchWrapTop(context, 16));
        deviceText = Views.label(context, "Board: not connected", 14, false);
        root.addView(deviceText, Views.matchWrapTop(context, 6));

        Button restartButton = Views.secondaryButton(context, "Restart Connection");
        restartButton.setOnClickListener(view -> boardLink.restartConnection());
        root.addView(restartButton, Views.matchHeightTop(context, 46, 10));

        scanSection = new LinearLayout(context);
        scanSection.setOrientation(LinearLayout.VERTICAL);
        scanButton = Views.primaryButton(context, "Scan for Board");
        scanButton.setOnClickListener(view -> startScan());
        scanSection.addView(scanButton, Views.matchHeightTop(context, 52, 18));
        scanResults = new LinearLayout(context);
        scanResults.setOrientation(LinearLayout.VERTICAL);
        scanSection.addView(scanResults, Views.matchWrapTop(context, 10));
        root.addView(scanSection, Views.matchWrap());

        wifiSetupSection = new LinearLayout(context);
        wifiSetupSection.setOrientation(LinearLayout.VERTICAL);
        wifiSetupSection.setVisibility(View.GONE);
        wifiSetupSection.addView(Views.label(context, "Wi-Fi setup", 16, true), Views.matchWrapTop(context, 24));
        Button refreshWifiButton = Views.secondaryButton(context, "Refresh Wi-Fi Networks");
        refreshWifiButton.setOnClickListener(view -> refreshWifiNetworks());
        wifiSetupSection.addView(refreshWifiButton, Views.matchHeightTop(context, 48, 8));
        wifiNetworks = new LinearLayout(context);
        wifiNetworks.setOrientation(LinearLayout.VERTICAL);
        wifiSetupSection.addView(wifiNetworks, Views.matchWrapTop(context, 8));
        ssidInput = Views.input(context, "Wi-Fi network name", InputType.TYPE_CLASS_TEXT);
        wifiSetupSection.addView(ssidInput, Views.matchHeightTop(context, 54, 8));
        passwordInput = Views.input(context, "Wi-Fi password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        wifiSetupSection.addView(passwordInput, Views.matchHeightTop(context, 54, 8));
        CheckBox showPasswordCheckbox = new CheckBox(context);
        showPasswordCheckbox.setText("Show password");
        showPasswordCheckbox.setTextColor(0xff1f2933);
        showPasswordCheckbox.setOnCheckedChangeListener((button, checked) -> {
            int type = InputType.TYPE_CLASS_TEXT | (checked
                    ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    : InputType.TYPE_TEXT_VARIATION_PASSWORD);
            passwordInput.setInputType(type);
            passwordInput.setSelection(passwordInput.length());
        });
        wifiSetupSection.addView(showPasswordCheckbox, Views.matchWrapTop(context, 0));
        provisionButton = Views.primaryButton(context, "Connect Board to Wi-Fi");
        provisionButton.setEnabled(false);
        provisionButton.setOnClickListener(view -> provisionWifi());
        wifiSetupSection.addView(provisionButton, Views.matchHeightTop(context, 52, 12));
        Button forgetButton = Views.secondaryButton(context, "Forget Saved Wi-Fi");
        forgetButton.setOnClickListener(view -> {
            secureWifiStore.clear();
            ssidInput.setText("");
            passwordInput.setText("");
            setStatus("Saved Wi-Fi network forgotten", BoardLink.COLOR_DEFAULT);
        });
        wifiSetupSection.addView(forgetButton, Views.matchHeightTop(context, 44, 8));
        root.addView(wifiSetupSection, Views.matchWrap());

        Button settingsButton = Views.secondaryButton(context, "Open Permission Settings");
        settingsButton.setOnClickListener(view -> openAppSettings());
        root.addView(settingsButton, Views.matchHeightTop(context, 46, 20));

        LinearLayout logHeader = new LinearLayout(context);
        logHeader.setOrientation(LinearLayout.HORIZONTAL);
        logHeader.setGravity(Gravity.CENTER_VERTICAL);
        logHeader.addView(Views.label(context, "Log", 14, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button clearLogButton = Views.secondaryButton(context, "Clear Log");
        clearLogButton.setOnClickListener(view -> logText.setText(""));
        logHeader.addView(clearLogButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(logHeader, Views.matchWrapTop(context, 18));

        logText = Views.label(context, "", 13, false);
        logText.setTypeface(Typeface.MONOSPACE);
        root.addView(logText, Views.matchWrapTop(context, 6));

        ScrollView scroll = new ScrollView(context);
        scroll.addView(root);
        return scroll;
    }

    private void ensureBleReady() {
        if (!boardLink.isBleReady()) {
            setStatus("Enable Bluetooth on this phone", BoardLink.COLOR_ERROR);
            return;
        }
        if (!boardLink.hasBlePermissions()) {
            blePermissionLauncher.launch(boardLink.requiredBlePermissions());
            return;
        }
        setStatus("Ready to scan", BoardLink.COLOR_DEFAULT);
    }

    private void startScan() {
        if (!boardLink.hasBlePermissions()) {
            blePermissionLauncher.launch(boardLink.requiredBlePermissions());
            return;
        }
        displayedAddresses.clear();
        scanResults.removeAllViews();
        provisionButton.setEnabled(false);
        wifiSetupSection.setVisibility(View.GONE);
        scanSection.setVisibility(View.VISIBLE);
        boardLink.startScan();
    }

    private void refreshWifiNetworks() {
        if (!boardLink.hasWifiPermissions()) {
            wifiPermissionLauncher.launch(boardLink.requiredWifiPermissions());
            return;
        }
        boardLink.refreshWifiNetworks();
    }

    private void provisionWifi() {
        boardLink.provisionWifi(ssidInput.getText().toString().trim(), passwordInput.getText().toString());
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", requireContext().getPackageName(), null));
        startActivity(intent);
    }

    private void setStatus(String status, int color) {
        if (statusText != null) {
            statusText.setText("Status: " + status);
            statusText.setTextColor(color);
        }
    }

    private void appendLog(String text) {
        if (logText == null) {
            return;
        }
        String old = logText.getText().toString();
        logText.setText(old.isEmpty() ? text : old + "\n" + text);
    }

    @Override
    public void onStatus(String status, int color) {
        setStatus(status, color);
    }

    @Override
    public void onLog(String line) {
        appendLog(line);
    }

    @Override
    public void onScanningChanged(boolean scanning) {
        scanButton.setText(scanning ? "Stop Scan" : "Scan for Board");
    }

    @Override
    public void onDeviceFound(BluetoothDevice device, String name, int rssi) {
        String address = device.getAddress();
        if (!displayedAddresses.add(address)) {
            return;
        }
        String displayName = name == null || name.isEmpty() ? "Unnamed BLE device" : name;
        Context context = requireContext();
        Button button = Views.secondaryButton(context, displayName + "  " + address + "  " + rssi + " dBm");
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setOnClickListener(view -> {
            boardLink.stopScan();
            boardLink.connect(device);
        });
        scanResults.addView(button, Views.matchHeightTop(context, 46, 6));
    }

    @Override
    public void onBoardConnectionChanged(boolean connected) {
        deviceText.setText(connected ? "Board: " + BoardLink.TARGET_NAME : "Board: not connected");
        if (!connected) {
            provisionButton.setEnabled(false);
            scanSection.setVisibility(View.VISIBLE);
            wifiSetupSection.setVisibility(View.GONE);
        }
    }

    @Override
    public void onWifiSetupReady() {
        provisionButton.setEnabled(true);
        scanSection.setVisibility(View.GONE);
        wifiSetupSection.setVisibility(View.VISIBLE);
        prefillSavedWifi();
    }

    @Override
    public void onWifiConnected(String boardIp) {
        wifiSetupSection.setVisibility(View.GONE);
        secureWifiStore.save(ssidInput.getText().toString().trim(), passwordInput.getText().toString());
        appendLog("Saved Wi-Fi credentials securely for next time");
    }

    @Override
    public void onWifiNetworksUpdated(List<ScanResult> results) {
        wifiNetworks.setVisibility(View.VISIBLE);
        wifiNetworks.removeAllViews();
        Context context = requireContext();
        Set<String> ssids = new HashSet<>();
        for (ScanResult result : results) {
            String ssid = result.SSID == null ? "" : result.SSID.trim();
            if (ssid.isEmpty() || !ssids.add(ssid)) {
                continue;
            }
            Button network = Views.secondaryButton(context, ssid + "  " + result.level + " dBm");
            network.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            network.setOnClickListener(view -> {
                ssidInput.setText(ssid);
                setStatus("Selected Wi-Fi network: " + ssid, BoardLink.COLOR_DEFAULT);
                wifiNetworks.setVisibility(View.GONE);
                passwordInput.requestFocus();
            });
            wifiNetworks.addView(network, Views.matchHeightTop(context, 44, 4));
        }
        if (ssids.isEmpty()) {
            setStatus("No Wi-Fi networks found; enter a hidden network name manually", BoardLink.COLOR_DEFAULT);
        }
    }
}
