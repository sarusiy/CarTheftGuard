package com.sarusiy.cartheftguard.ui;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.sarusiy.cartheftguard.BoardLink;

import java.util.HashSet;
import java.util.Set;

/**
 * "Connect" tab: one way to connect, always -- BLE scanning finds the board
 * ("JC-P4-C6"), which automatically joins its Wi-Fi AP in the background.
 * No manual Wi-Fi switching, no credentials, no home network involved.
 */
public class ConnectFragment extends Fragment implements BoardLink.Listener {

    private BoardLink boardLink;

    private final ActivityResultLauncher<String[]> blePermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(),
            grants -> {
                if (!grants.isEmpty() && !grants.containsValue(false)) {
                    ensureBleReady();
                } else {
                    setStatus("Bluetooth permission denied. Tap 'Open Permission Settings' below.", BoardLink.COLOR_ERROR);
                }
            });

    private final Set<String> displayedAddresses = new HashSet<>();

    private TextView statusText;
    private TextView deviceText;
    private TextView canModeText;
    private TextView logText;
    private LinearLayout scanSection;
    private LinearLayout scanResults;
    private Button scanButton;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        boardLink = BoardLink.getInstance(context);
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
        syncUiToCurrentState();
    }

    /* Fragment views/instances get recreated on every tab switch (or process
     * recreation), but BoardLink's actual connection is a singleton that
     * keeps running underneath. A fresh ConnectFragment instance only ever
     * sees FUTURE events from BoardLink's listener callbacks -- it never gets
     * a replay of "already connected" state from before it existed. Query
     * BoardLink's actual current state directly here instead of relying
     * purely on event replay. */
    private void syncUiToCurrentState() {
        boolean boardPresent = boardLink.isBoardConnected();
        boolean wifiReady = boardLink.isWifiReady();
        deviceText.setText(boardPresent ? "Board: " + BoardLink.TARGET_NAME : "Board: not found yet");

        if (wifiReady) {
            scanSection.setVisibility(View.GONE);
            setStatus("Connected, Wi-Fi ready: " + boardLink.getBoardIp(), BoardLink.COLOR_SUCCESS);
            refreshCanMode();
            return;
        }

        scanSection.setVisibility(View.VISIBLE);
        if (canModeText != null) {
            canModeText.setText("CAN mode: unknown");
        }
        if (!boardPresent) {
            setStatus(boardLink.isScanning() ? "Scanning for " + BoardLink.TARGET_NAME : "Ready to scan", BoardLink.COLOR_DEFAULT);
        }
    }

    @Override
    public void onStop() {
        boardLink.removeListener(this);
        super.onStop();
    }

    private View buildView() {
        Context context = requireContext();
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = Views.dp(context, 20);
        root.setPadding(padding, padding, padding, padding);
        root.setBackgroundColor(0xfff5f2ea);

        root.addView(Views.label(context, "Connect", 24, true), Views.matchWrap());
        TextView versionText = Views.label(context, "Version: " + appVersionLabel(context), 12, false);
        versionText.setTextColor(0xff8a94a6);
        root.addView(versionText, Views.matchWrapTop(context, 4));
        statusText = Views.label(context, "Status: idle", 17, true);
        root.addView(statusText, Views.matchWrapTop(context, 16));
        deviceText = Views.label(context, "Board: not found yet", 14, false);
        root.addView(deviceText, Views.matchWrapTop(context, 6));
        canModeText = Views.label(context, "CAN mode: unknown", 14, false);
        root.addView(canModeText, Views.matchWrapTop(context, 6));

        root.addView(Views.label(context, "Join CarTheftGuard-P4's own Wi-Fi automatically as soon as it's "
                        + "detected nearby over Bluetooth -- no home network needed, works anywhere.", 12, false),
                Views.matchWrapTop(context, 8));

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
        if (!boardLink.isBoardConnected() && !boardLink.isScanning()) {
            setStatus("Ready to scan", BoardLink.COLOR_DEFAULT);
        }
    }

    private void startScan() {
        if (!boardLink.hasBlePermissions()) {
            blePermissionLauncher.launch(boardLink.requiredBlePermissions());
            return;
        }
        displayedAddresses.clear();
        scanResults.removeAllViews();
        scanSection.setVisibility(View.VISIBLE);
        boardLink.startScan();
    }

    private void refreshCanMode() {
        boardLink.fetchCanMode(mode -> {
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                if (canModeText != null) {
                    canModeText.setText("CAN mode: " + mode);
                }
            });
        });
    }

    /* Surfaces the installed app version directly on this screen so it can be
     * confirmed after a reinstall without needing a separate About screen. */
    private String appVersionLabel(Context context) {
        try {
            android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            long versionCode = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P
                    ? info.getLongVersionCode() : info.versionCode;
            return info.versionName + " (" + versionCode + ")";
        } catch (android.content.pm.PackageManager.NameNotFoundException exception) {
            return "unknown";
        }
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
        scanResults.addView(button, Views.matchHeightTop(context, 46, 6));
    }

    @Override
    public void onBoardConnectionChanged(boolean connected) {
        deviceText.setText(connected ? "Board: " + BoardLink.TARGET_NAME : "Board: not found yet");
        if (!connected) {
            scanSection.setVisibility(View.VISIBLE);
            if (canModeText != null) {
                canModeText.setText("CAN mode: unknown");
            }
        }
    }

    @Override
    public void onWifiConnected(String boardIp) {
        appendLog("onWifiConnected fired: ip=" + boardIp);
        setStatus("Connected, Wi-Fi ready: " + boardIp, BoardLink.COLOR_SUCCESS);
        scanSection.setVisibility(View.GONE);
        refreshCanMode();
    }
}
