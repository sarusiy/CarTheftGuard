package com.sarusiy.cartheftguard.ui;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
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
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import com.sarusiy.cartheftguard.BoardLink;
import com.sarusiy.cartheftguard.DriveUpdates;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/** "About" tab: static app/board identification info, plus checking the
 * project's shared Drive folder for the newest firmware/app build and
 * applying it -- pushing firmware to the board over its existing Wi-Fi link,
 * or installing a new APK -- without a USB/PC visit. A manual file picker
 * remains as a fallback for firmware not yet uploaded to Drive. */
public class AboutFragment extends Fragment {
    /** How long to keep polling GET /api/ota after a push before giving up on
     * confirming the board came back -- covers the ~0.5s the board waits
     * before rebooting plus typical ESP32 boot + Wi-Fi AP bring-up time. */
    private static final int OTA_CONFIRM_POLL_INTERVAL_MS = 2000;
    private static final int OTA_CONFIRM_POLL_MAX_ATTEMPTS = 10;

    private BoardLink boardLink;
    private TextView firmwareStatusText;
    private Button pushFirmwareButton;
    private ActivityResultLauncher<String[]> firmwarePicker;
    private TextView driveCheckStatusText;
    private LinearLayout driveResultsContainer;
    private Button checkDriveButton;
    /** Which status text/button a push-in-progress reports to -- the manual
     * picker section and the two-step Drive flow's inline row each have
     * their own, so progress shows up right where the user clicked instead
     * of only in the (easy to miss) "Firmware Update (manual)" section
     * lower on the screen. Set at the start of pushFirmwareBytes(). */
    private TextView activeFirmwareStatusText;
    private Button activePushButton;
    private TextView pushDownloadedStatusText;
    private Button pushDownloadedButton;
    private TextView firmwareVersionText;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int otaConfirmAttempts;
    private final Runnable otaConfirmRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isAdded()) {
                return;
            }
            otaConfirmAttempts++;
            boardLink.fetchOtaStatus(response -> {
                if (activeFirmwareStatusText == null) {
                    return;
                }
                if (response != null) {
                    activeFirmwareStatusText.setText("Firmware update: board back online -- " + response);
                    if (activePushButton != null) {
                        activePushButton.setEnabled(true);
                    }
                    refreshFirmwareVersion();
                    return;
                }
                if (otaConfirmAttempts < OTA_CONFIRM_POLL_MAX_ATTEMPTS) {
                    handler.postDelayed(otaConfirmRunnable, OTA_CONFIRM_POLL_INTERVAL_MS);
                } else {
                    activeFirmwareStatusText.setText("Firmware update: board did not come back online -- "
                            + "check it manually before assuming the update failed");
                    if (activePushButton != null) {
                        activePushButton.setEnabled(true);
                    }
                }
            });
        }
    };

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        boardLink = BoardLink.getInstance(context);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        firmwarePicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) {
                pushFirmware(uri);
            }
        });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return buildView();
    }

    @Override
    public void onStop() {
        handler.removeCallbacks(otaConfirmRunnable);
        super.onStop();
    }

    private View buildView() {
        Context context = requireContext();
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = Views.dp(context, 20);
        root.setPadding(padding, padding, padding, padding);
        root.setBackgroundColor(0xfff5f2ea);

        root.addView(Views.label(context, "About", 24, true), Views.matchWrap());
        root.addView(Views.label(context, "CarTheftGuard", 20, true), Views.matchWrapTop(context, 20));
        root.addView(infoRow(context, "Version", versionLabel(context)), Views.matchWrapTop(context, 12));
        root.addView(infoRow(context, "Package", context.getPackageName()), Views.matchWrapTop(context, 8));
        root.addView(infoRow(context, "Board", BoardLink.TARGET_NAME + " (JC-ESP32P4-M3)"), Views.matchWrapTop(context, 8));
        root.addView(infoRow(context, "Board Wi-Fi", BoardLink.AP_SSID), Views.matchWrapTop(context, 8));
        LinearLayout firmwareVersionRow = new LinearLayout(context);
        firmwareVersionRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView firmwareVersionLabel = Views.label(context, "Firmware version", 14, true);
        firmwareVersionLabel.setTextColor(0xff52616b);
        firmwareVersionRow.addView(firmwareVersionLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        firmwareVersionText = Views.label(context, "checking...", 14, false);
        firmwareVersionRow.addView(firmwareVersionText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2));
        root.addView(firmwareVersionRow, Views.matchWrapTop(context, 8));
        refreshFirmwareVersion();

        root.addView(Views.label(context, "About this app", 16, true), Views.matchWrapTop(context, 28));
        root.addView(Views.label(context, "Detects a JC-ESP32P4-M3 board nearby over BLE, then automatically joins "
                        + "its own Wi-Fi in the background -- one way to connect, anywhere, no home network needed -- "
                        + "and lets you monitor and control it from your phone.", 14, false),
                Views.matchWrapTop(context, 6));

        root.addView(link(context, "Firmware: github.com/sarusiy/JC-ESP32P4-M3",
                "https://github.com/sarusiy/JC-ESP32P4-M3"), Views.matchWrapTop(context, 20));
        root.addView(link(context, "App: github.com/sarusiy/CarTheftGuard",
                "https://github.com/sarusiy/CarTheftGuard"), Views.matchWrapTop(context, 6));

        root.addView(Views.label(context, "Check for Updates", 16, true), Views.matchWrapTop(context, 28));
        driveCheckStatusText = Views.label(context, "Not checked yet", 14, false);
        root.addView(driveCheckStatusText, Views.matchWrapTop(context, 4));
        root.addView(Views.label(context, "Looks for the newest firmware/app build in the project's shared Drive "
                        + "folder -- no file transfer to this phone needed, builds land there automatically "
                        + "(see tools/publish-firmware-to-drive.ps1 and tools/publish-apk-to-drive.ps1).", 12, false),
                Views.matchWrapTop(context, 4));
        checkDriveButton = Views.secondaryButton(context, "Check Drive for updates");
        checkDriveButton.setOnClickListener(view -> checkDriveForUpdates());
        root.addView(checkDriveButton, Views.matchWrapTop(context, 8));
        driveResultsContainer = new LinearLayout(context);
        driveResultsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(driveResultsContainer, Views.matchWrapTop(context, 4));

        /* Deliberately built unconditionally here (not only inside the
         * "Check Drive for updates" results list) and driven purely by
         * whether downloadedFirmwareFile() exists on disk -- switching tabs
         * to join the board's Wi-Fi and coming back recreates this
         * fragment's whole view (onCreateView runs again), which wipes the
         * dynamic driveResultsContainer list built by step 1. This section
         * survives that because it re-checks the actual file on disk every
         * time the view is built, instead of depending on in-memory state
         * from a "Check Drive for updates" tap that may have happened
         * before the view was torn down. */
        root.addView(Views.label(context, "Push Downloaded Firmware", 16, true), Views.matchWrapTop(context, 28));
        root.addView(Views.label(context, "Step 2 lives here permanently -- do step 1 above (needs internet), "
                        + "then come back to this tab (needs only the board's Wi-Fi, even after switching tabs "
                        + "to connect to it) and push from here.", 12, false),
                Views.matchWrapTop(context, 4));
        pushDownloadedStatusText = Views.label(context, "", 14, false);
        root.addView(pushDownloadedStatusText, Views.matchWrapTop(context, 4));
        pushDownloadedButton = Views.secondaryButton(context, "Push to board");
        pushDownloadedButton.setOnClickListener(v -> pushDownloadedFirmware());
        root.addView(pushDownloadedButton, Views.matchWrapTop(context, 8));
        refreshPushDownloadedSection();

        root.addView(Views.label(context, "Firmware Update (manual)", 16, true), Views.matchWrapTop(context, 28));
        firmwareStatusText = Views.label(context, "Firmware update: idle", 14, false);
        root.addView(firmwareStatusText, Views.matchWrapTop(context, 4));
        root.addView(Views.label(context, "Fallback for a .bin not yet uploaded to Drive -- get the file onto this "
                        + "phone any other way (adb push, cloud drive, email) and push it from here instead. "
                        + "The board rejects a bad image before booting it, and rolls back automatically if a new "
                        + "image crash-loops after boot.", 12, false),
                Views.matchWrapTop(context, 4));
        pushFirmwareButton = Views.secondaryButton(context, "Select firmware (.bin) and push to board");
        pushFirmwareButton.setOnClickListener(view -> firmwarePicker.launch(new String[]{"application/octet-stream", "*/*"}));
        root.addView(pushFirmwareButton, Views.matchWrapTop(context, 8));

        ScrollView scroll = new ScrollView(context);
        scroll.addView(root);
        return scroll;
    }

    private void checkDriveForUpdates() {
        checkDriveButton.setEnabled(false);
        driveCheckStatusText.setText("Checking Drive folder...");
        driveResultsContainer.removeAllViews();

        DriveUpdates.findLatest(".bin", result -> {
            if (!isAdded()) {
                return;
            }
            if (result.file != null) {
                addFirmwareDriveResultRow(result.file);
                fetchAndShowTargetVersion("jc-esp32p4-m3.version.txt");
            } else if (result.error != null) {
                addDriveErrorRow("firmware (.bin)", result.error);
            } else {
                addDriveNotFoundRow("firmware (.bin)");
            }
        });
        DriveUpdates.findLatest(".apk", result -> {
            if (!isAdded()) {
                return;
            }
            if (result.file != null) {
                addDriveResultRow("App", result.file, "Download & install",
                        (status, button) -> installApkFromDrive(result.file, status, button));
                fetchAndShowTargetVersion("app-debug.version.txt");
            } else if (result.error != null) {
                addDriveErrorRow("app (.apk)", result.error);
            } else {
                addDriveNotFoundRow("app (.apk)");
            }
            driveCheckStatusText.setText("Drive folder checked.");
            checkDriveButton.setEnabled(true);
        });
    }

    /** Looks up the small companion version-string file publish-*-to-drive.ps1
     * uploads alongside each .bin/.apk (exact-name lookup via findLatest's
     * suffix match -- passing the whole filename as "suffix" works since
     * endsWith(wholeName) is effectively an exact match) and appends it as
     * its own line, so you can compare the pending update's version against
     * the "Version"/"Firmware version" rows above before committing to it.
     * Best-effort: silently does nothing if the companion file is missing
     * (e.g. an older publish predating this feature). */
    private void fetchAndShowTargetVersion(String versionFileName) {
        DriveUpdates.findLatest(versionFileName, result -> {
            if (!isAdded() || result.file == null) {
                return;
            }
            DriveUpdates.downloadText(result.file.id, version -> {
                if (!isAdded() || version == null) {
                    return;
                }
                TextView row = Views.label(requireContext(), "Target version: " + version, 12, false);
                row.setTextColor(0xff0b6e69);
                driveResultsContainer.addView(row, Views.matchWrapTop(requireContext(), 2));
            });
        });
    }

    private void addDriveResultRow(String kind, DriveUpdates.DriveFile file, String actionLabel,
                                    java.util.function.BiConsumer<TextView, Button> onAction) {
        Context context = requireContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        String sizeKb = String.valueOf(file.size / 1024);
        String when = file.modifiedTime.length() >= 16 ? file.modifiedTime.substring(0, 16).replace('T', ' ') : file.modifiedTime;
        row.addView(Views.label(context, kind + ": " + file.name + " (" + sizeKb + " KB, " + when + " UTC)", 13, false), Views.matchWrap());
        TextView actionStatus = Views.label(context, "", 12, false);
        row.addView(actionStatus, Views.matchWrapTop(context, 4));
        Button actionButton = Views.secondaryButton(context, actionLabel);
        actionButton.setOnClickListener(v -> {
            /* Immediate, right at the button, so it never feels unresponsive
             * during the gap before the first network callback lands --
             * previously the only feedback was a status text far above this
             * row, easy to miss, so tapping felt like nothing happened. */
            actionButton.setEnabled(false);
            actionStatus.setText("Starting...");
            onAction.accept(actionStatus, actionButton);
        });
        row.addView(actionButton, Views.matchWrapTop(context, 4));
        driveResultsContainer.addView(row, Views.matchWrapTop(context, 10));
    }

    private void addDriveNotFoundRow(String kindLabel) {
        driveResultsContainer.addView(Views.label(requireContext(), "No " + kindLabel + " found in the Drive folder.", 13, false),
                Views.matchWrapTop(requireContext(), 10));
    }

    private void addDriveErrorRow(String kindLabel, String error) {
        TextView row = Views.label(requireContext(), "Couldn't check for " + kindLabel + ": " + error, 13, false);
        row.setTextColor(0xffb00020);
        driveResultsContainer.addView(row, Views.matchWrapTop(requireContext(), 10));
    }

    /** Same file every time -- deliberately not the SAF-invisible cache dir
     * that the old combined download-then-push flow used, and deliberately
     * not exposed to a file picker either: the app just reads back the
     * exact file it wrote itself, so "push" never needs Drive or a picker,
     * only whatever this fragment already downloaded. Survives app restart
     * (getFilesDir(), not cache) so a download made before leaving Wi-Fi
     * range is still there when you come back with the board instead. */
    private File downloadedFirmwareFile() {
        return new File(requireContext().getFilesDir(), "downloaded_firmware.bin");
    }

    /** Two separate steps instead of one combined "download & push" action --
     * added after finding that a real car location often can't have both
     * internet (for the Drive download) and the board's own Wi-Fi AP (for
     * the push) connected at once. This row is step 1 only (download);
     * step 2 (push) lives in the always-rebuilt "Push Downloaded Firmware"
     * section instead of here, since switching tabs to join the board's
     * Wi-Fi and coming back tears down and rebuilds this whole view,
     * wiping whatever was in driveResultsContainer -- see
     * refreshPushDownloadedSection's doc comment. */
    private void addFirmwareDriveResultRow(DriveUpdates.DriveFile file) {
        Context context = requireContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        String sizeKb = String.valueOf(file.size / 1024);
        String when = file.modifiedTime.length() >= 16 ? file.modifiedTime.substring(0, 16).replace('T', ' ') : file.modifiedTime;
        row.addView(Views.label(context, "Firmware: " + file.name + " (" + sizeKb + " KB, " + when + " UTC)", 13, false),
                Views.matchWrap());

        TextView firmwareDriveStatus = Views.label(context, downloadedFirmwareFile().exists()
                ? "Already downloaded to this phone -- see \"Push Downloaded Firmware\" below."
                : "Not downloaded yet.", 12, false);
        row.addView(firmwareDriveStatus, Views.matchWrapTop(context, 4));

        Button downloadButton = Views.secondaryButton(context, "1. Download to phone (needs internet, not the board)");
        downloadButton.setOnClickListener(v -> {
            downloadButton.setEnabled(false);
            firmwareDriveStatus.setText("Downloading " + file.name + "...");
            DriveUpdates.download(file.id, downloadedFirmwareFile(), error -> {
                downloadButton.setEnabled(true);
                if (!isAdded()) {
                    return;
                }
                firmwareDriveStatus.setText(error != null
                        ? "Download failed -- " + error
                        : "Downloaded -- see \"Push Downloaded Firmware\" below (works after switching to the board's Wi-Fi).");
                refreshPushDownloadedSection();
            });
        });
        row.addView(downloadButton, Views.matchWrapTop(context, 8));

        driveResultsContainer.addView(row, Views.matchWrapTop(context, 10));
    }

    /** Reflects whatever's actually on disk right now -- called on every
     * view build (so it survives a tab-switch-and-back) and again right
     * after a successful download (so it updates immediately without
     * needing to leave and return to this tab). */
    private void refreshPushDownloadedSection() {
        if (pushDownloadedStatusText == null || pushDownloadedButton == null) {
            return;
        }
        boolean ready = downloadedFirmwareFile().exists();
        pushDownloadedStatusText.setText(ready
                ? "A downloaded firmware image is ready. Connect to the board's Wi-Fi, then push."
                : "Nothing downloaded yet -- use step 1 above first (needs internet).");
        pushDownloadedButton.setEnabled(ready);
    }

    private void pushDownloadedFirmware() {
        File dest = downloadedFirmwareFile();
        if (!dest.exists()) {
            pushDownloadedStatusText.setText("Nothing downloaded yet -- use step 1 above first.");
            return;
        }
        pushDownloadedButton.setEnabled(false);
        try (InputStream input = new FileInputStream(dest)) {
            pushFirmwareBytes(readAllBytes(input), pushDownloadedStatusText, pushDownloadedButton);
        } catch (IOException exception) {
            pushDownloadedButton.setEnabled(true);
            pushDownloadedStatusText.setText("Failed to read downloaded file -- " + exception.getMessage());
        }
    }

    private void installApkFromDrive(DriveUpdates.DriveFile file, TextView status, Button button) {
        status.setText("Downloading " + file.name + "...");
        File dest = new File(requireContext().getCacheDir(), "update.apk");
        DriveUpdates.download(file.id, dest, error -> {
            if (!isAdded()) {
                return;
            }
            if (error != null) {
                status.setText("Failed to download " + file.name + " -- " + error);
                button.setEnabled(true);
                return;
            }
            Context context = requireContext();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.getPackageManager().canRequestPackageInstalls()) {
                status.setText("Grant \"install unknown apps\" for CarTheftGuard, then tap Install again.");
                button.setEnabled(true);
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + context.getPackageName())));
                return;
            }
            status.setText("Downloaded -- opening installer...");
            Uri apkUri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", dest);
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(apkUri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(install);
            button.setEnabled(true);
        });
    }

    private void pushFirmware(Uri uri) {
        pushFirmwareButton.setEnabled(false);
        firmwareStatusText.setText("Firmware update: reading file...");
        byte[] firmware;
        try (InputStream input = requireContext().getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new IOException("Could not open the selected file");
            }
            firmware = readAllBytes(input);
        } catch (IOException exception) {
            firmwareStatusText.setText("Firmware update: failed to read file -- " + exception.getMessage());
            pushFirmwareButton.setEnabled(true);
            return;
        }
        pushFirmwareBytes(firmware, firmwareStatusText, pushFirmwareButton);
    }

    private byte[] readAllBytes(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = input.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    /** {@code statusText}/{@code button} let this be called from either the
     * manual-picker section or the two-step Drive flow's own row, so
     * progress always shows up right where the user clicked -- see
     * activeFirmwareStatusText's doc comment. */
    private void pushFirmwareBytes(byte[] firmware, TextView statusText, Button button) {
        activeFirmwareStatusText = statusText;
        activePushButton = button;
        if (button != null) {
            button.setEnabled(false);
        }
        statusText.setText("Firmware update: uploading 0%");
        boardLink.pushFirmware(firmware, new BoardLink.OtaListener() {
            @Override
            public void onProgress(int percent) {
                if (activeFirmwareStatusText != null) {
                    activeFirmwareStatusText.setText("Firmware update: uploading " + percent + "%");
                }
            }

            @Override
            public void onResult(boolean success, String message) {
                if (activeFirmwareStatusText == null) {
                    return;
                }
                /* "Not connected to the board" means the upload never even
                 * started (no network to send to) -- unambiguous failure,
                 * not the "maybe it rebooted mid-response" case below, so
                 * don't claim a reboot is being confirmed when nothing was
                 * ever sent. Any other outcome (including a reported
                 * failure) can legitimately mean the board rebooted
                 * mid-response (see BoardLink.pushFirmware's doc comment),
                 * so those still get confirmed by polling GET /api/ota
                 * rather than trusting this callback alone. */
                if (!success && "Not connected to the board".equals(message)) {
                    activeFirmwareStatusText.setText("Firmware update: failed -- not connected to the board. "
                            + "Join its Wi-Fi first, then try again.");
                    if (activePushButton != null) {
                        activePushButton.setEnabled(true);
                    }
                    return;
                }
                activeFirmwareStatusText.setText("Firmware update: upload finished (" + message + "), confirming reboot...");
                handler.removeCallbacks(otaConfirmRunnable);
                otaConfirmAttempts = 0;
                handler.postDelayed(otaConfirmRunnable, OTA_CONFIRM_POLL_INTERVAL_MS);
            }
        });
    }

    /** Pulls firmware_version from GET /api/health (see main.c's
     * health_http_handler) -- the only way to confirm which firmware build
     * is actually running on the board, e.g. right after an OTA push.
     * Called on view build and again once a push's reboot is confirmed. */
    private void refreshFirmwareVersion() {
        if (firmwareVersionText == null || boardLink == null) {
            return;
        }
        boardLink.fetchHealth(json -> {
            if (firmwareVersionText == null) {
                return;
            }
            if (json == null) {
                firmwareVersionText.setText("not connected to the board");
                return;
            }
            try {
                firmwareVersionText.setText(new JSONObject(json).optString("firmware_version", "unknown"));
            } catch (Exception exception) {
                firmwareVersionText.setText("unknown (malformed response)");
            }
        });
    }

    private String versionLabel(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            long versionCode = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P
                    ? info.getLongVersionCode() : info.versionCode;
            return info.versionName + " (" + versionCode + ")";
        } catch (PackageManager.NameNotFoundException exception) {
            return "unknown";
        }
    }

    private View infoRow(Context context, String label, String value) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView labelView = Views.label(context, label, 14, true);
        labelView.setTextColor(0xff52616b);
        row.addView(labelView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(Views.label(context, value, 14, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2));
        return row;
    }

    private View link(Context context, String text, String url) {
        TextView view = Views.label(context, text, 14, false);
        view.setTextColor(0xff0b6e69);
        view.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))));
        return view;
    }
}
