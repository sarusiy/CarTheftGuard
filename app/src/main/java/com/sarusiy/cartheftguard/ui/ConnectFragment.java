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
    private TextView canModeText;
    /* True only if onWifiSetupReady() actually showed the credentials form
     * this connection. Guards onWifiConnected()'s credential-save call so it
     * doesn't overwrite securely-stored Wi-Fi credentials with empty text
     * boxes when the board turned out to already be connected and the form
     * was skipped entirely (see BoardLink's pendingWifiSetupPrompt). */
    private boolean wifiSetupFormShown;
    /* Safety net for when the one-shot "board just became Wi-Fi ready" BLE
     * push arrives late or gets missed (board's own Wi-Fi join can genuinely
     * take longer than BoardLink's short grace-period timer, or the BLE
     * notify can simply be delayed/lost). Without this, the screen only ever
     * self-corrected when the user happened to navigate away and back
     * (which re-runs syncUiToCurrentState() fresh). Polling here means it
     * self-corrects on its own, in place, within about a second of the real
     * state actually changing. Uses the fragment's own View.postDelayed
     * instead of a separate Handler field to avoid any doubt about Handler
     * construction timing. */
    private Runnable wifiStatusPoller;
    private int wifiStatusPollTicks;
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
        syncUiToCurrentState();
        if (!boardLink.isWifiReady()) {
            startWifiStatusPolling();
        }
    }

    private void startWifiStatusPolling() {
        stopWifiStatusPolling();
        wifiStatusPollTicks = 0;
        appendLog("Wi-Fi status poll: started");
        wifiStatusPoller = new Runnable() {
            @Override
            public void run() {
                wifiStatusPollTicks++;
                if (boardLink.isWifiReady()) {
                    appendLog("Wi-Fi status poll: ready after " + wifiStatusPollTicks + "s, updating UI");
                    syncUiToCurrentState();
                    wifiStatusPoller = null;
                    return;
                }
                if (wifiStatusPollTicks % 5 == 0) {
                    appendLog("Wi-Fi status poll: still waiting (" + wifiStatusPollTicks + "s)");
                }
                /* No attempt cap: this is cheap (one boolean check per
                 * second) and onStop() already cancels it the moment the
                 * user leaves this screen, so there's no real cost to
                 * letting it run for as long as the user stays here waiting
                 * to connect -- a real first-time scan -> select device ->
                 * BLE connect -> provision Wi-Fi flow can easily take longer
                 * than any fixed timeout we'd pick. */
                View pollView = getView();
                if (pollView != null) {
                    pollView.postDelayed(this, 1000);
                }
            }
        };
        View view = getView();
        if (view != null) {
            view.postDelayed(wifiStatusPoller, 1000);
        }
    }

    private void stopWifiStatusPolling() {
        if (wifiStatusPoller != null) {
            appendLog("Wi-Fi status poll: stopped after " + wifiStatusPollTicks + "s");
            View view = getView();
            if (view != null) {
                view.removeCallbacks(wifiStatusPoller);
            }
            wifiStatusPoller = null;
        }
    }

    /* Fragment views/instances get recreated on every tab switch (or process
     * recreation), but BoardLink's actual BLE/Wi-Fi connection is a singleton
     * that keeps running underneath. A fresh ConnectFragment instance only
     * ever sees FUTURE events from BoardLink's listener callbacks -- it never
     * gets a replay of "already connected" state from before it existed. That
     * left this screen showing stale defaults ("Ready to scan", "CAN mode:
     * unknown", the Wi-Fi setup form) even while the board was already fully
     * connected and other screens (Monitor) were showing live data fine.
     * Query BoardLink's actual current state directly here instead of
     * relying purely on event replay. */
    private void syncUiToCurrentState() {
        boolean bleConnected = boardLink.isBoardConnected();
        boolean wifiReady = boardLink.isWifiReady();
        deviceText.setText(bleConnected || wifiReady ? "Board: " + BoardLink.TARGET_NAME : "Board: not connected");

        if (wifiReady) {
            /* Wi-Fi/HTTP reachability -- exactly what MonitorFragment relies
             * on via fetchObdData()/fetchGpsData() (which only gate on
             * isWifiReady(), never on isBoardConnected()) -- is the real
             * signal for "is the board giving me data right now", and it is
             * independent of the separate interactive BLE session. Android
             * commonly drops idle BLE GATT connections on its own (screen
             * lock, backgrounding, radio congestion) while Wi-Fi/HTTP keeps
             * working fine with the already-known IP, no BLE required. This
             * used to be nested inside the BLE-connected check below and
             * returned early before ever getting here, which is exactly why
             * this screen kept showing "Ready to scan" / stale CAN mode even
             * while Monitor had live data. */
            scanSection.setVisibility(View.GONE);
            wifiSetupSection.setVisibility(View.GONE);
            wifiSetupFormShown = false;
            String bleNote = bleConnected ? "" : " (BLE control channel idle)";
            setStatus("Connected, Wi-Fi ready: " + boardLink.getBoardIp() + bleNote, BoardLink.COLOR_SUCCESS);
            boardLink.fetchCanMode(mode -> requireActivity().runOnUiThread(() -> {
                if (canModeText != null) {
                    canModeText.setText("CAN mode: " + mode);
                }
            }));
            return;
        }

        if (!bleConnected) {
            scanSection.setVisibility(View.VISIBLE);
            wifiSetupSection.setVisibility(View.GONE);
            wifiSetupFormShown = false;
            provisionButton.setEnabled(false);
            if (canModeText != null) {
                canModeText.setText("CAN mode: unknown");
            }
            prefillSavedWifi();
        }
        /* else: connected over BLE but Wi-Fi status not confirmed yet --
         * leave the scan/setup sections as-is and let BoardLink's own grace-
         * period timer (pendingWifiSetupPrompt) decide whether to show the
         * Wi-Fi form once it resolves. */
    }

    @Override
    public void onStop() {
        stopWifiStatusPolling();
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
        TextView versionText = Views.label(context, "Version: " + appVersionLabel(context), 12, false);
        versionText.setTextColor(0xff8a94a6);
        root.addView(versionText, Views.matchWrapTop(context, 4));
        statusText = Views.label(context, "Status: idle", 17, true);
        root.addView(statusText, Views.matchWrapTop(context, 16));
        deviceText = Views.label(context, "Board: not connected", 14, false);
        root.addView(deviceText, Views.matchWrapTop(context, 6));
        canModeText = Views.label(context, "CAN mode: unknown", 14, false);
        root.addView(canModeText, Views.matchWrapTop(context, 6));

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
            wifiSetupFormShown = false;
            if (canModeText != null) {
                canModeText.setText("CAN mode: unknown");
            }
        } else if (!boardLink.isWifiReady()) {
            appendLog("BLE connected, Wi-Fi not yet ready; starting status poll");
            /* A fresh BLE connection is the real start of a connection
             * attempt -- restart polling from here (not just from
             * onStart()/fragment creation) so a full scan -> select device ->
             * connect -> provision Wi-Fi cycle (which can easily take longer
             * than the fragment has already been alive) gets its own full
             * polling window instead of inheriting an already-expired one. */
            startWifiStatusPolling();
        }
    }

    @Override
    public void onWifiSetupReady() {
        /* Defensive re-check, independent of BoardLink's own guard: never
         * show the Wi-Fi form if the board is actually already reachable.
         * Belt-and-suspenders against any race between this event firing and
         * Wi-Fi becoming ready through a different path in the meantime. */
        if (boardLink.isWifiReady()) {
            return;
        }
        provisionButton.setEnabled(true);
        scanSection.setVisibility(View.GONE);
        wifiSetupSection.setVisibility(View.VISIBLE);
        wifiSetupFormShown = true;
        prefillSavedWifi();
    }

    @Override
    public void onWifiConnected(String boardIp) {
        stopWifiStatusPolling();
        appendLog("onWifiConnected fired: ip=" + boardIp);
        /* Set the status text directly here instead of relying solely on the
         * separate onStatus()/emitStatus() callback from BoardLink to arrive
         * correctly -- keeps this self-sufficient regardless of any BLE
         * event-ordering edge case between the two decoupled callbacks. */
        setStatus("Connected, Wi-Fi ready: " + boardIp, BoardLink.COLOR_SUCCESS);
        scanSection.setVisibility(View.GONE);
        wifiSetupSection.setVisibility(View.GONE);
        if (wifiSetupFormShown) {
            secureWifiStore.save(ssidInput.getText().toString().trim(), passwordInput.getText().toString());
            appendLog("Saved Wi-Fi credentials securely for next time");
            wifiSetupFormShown = false;
        }
        if (boardLink != null) {
            boardLink.fetchCanMode(mode -> {
                requireActivity().runOnUiThread(() -> {
                    if (canModeText != null) {
                        canModeText.setText("CAN mode: " + mode);
                    }
                });
            });
        }
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
