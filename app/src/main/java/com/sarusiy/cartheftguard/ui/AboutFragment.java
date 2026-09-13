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
                if (firmwareStatusText == null) {
                    return;
                }
                if (response != null) {
                    firmwareStatusText.setText("Firmware update: board back online -- " + response);
                    if (pushFirmwareButton != null) {
                        pushFirmwareButton.setEnabled(true);
                    }
                    return;
                }
                if (otaConfirmAttempts < OTA_CONFIRM_POLL_MAX_ATTEMPTS) {
                    handler.postDelayed(otaConfirmRunnable, OTA_CONFIRM_POLL_INTERVAL_MS);
                } else {
                    firmwareStatusText.setText("Firmware update: board did not come back online -- "
                            + "check it manually before assuming the update failed");
                    if (pushFirmwareButton != null) {
                        pushFirmwareButton.setEnabled(true);
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

        DriveUpdates.findLatest(".bin", firmwareFile -> {
            if (!isAdded()) {
                return;
            }
            if (firmwareFile != null) {
                addDriveResultRow("Firmware", firmwareFile, "Download & push to board",
                        () -> pushFirmwareFromDrive(firmwareFile));
            } else {
                addDriveNotFoundRow("firmware (.bin)");
            }
        });
        DriveUpdates.findLatest(".apk", apkFile -> {
            if (!isAdded()) {
                return;
            }
            if (apkFile != null) {
                addDriveResultRow("App", apkFile, "Download & install",
                        () -> installApkFromDrive(apkFile));
            } else {
                addDriveNotFoundRow("app (.apk)");
            }
            driveCheckStatusText.setText("Drive folder checked.");
            checkDriveButton.setEnabled(true);
        });
    }

    private void addDriveResultRow(String kind, DriveUpdates.DriveFile file, String actionLabel, Runnable onAction) {
        Context context = requireContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        String sizeKb = String.valueOf(file.size / 1024);
        String when = file.modifiedTime.length() >= 16 ? file.modifiedTime.substring(0, 16).replace('T', ' ') : file.modifiedTime;
        row.addView(Views.label(context, kind + ": " + file.name + " (" + sizeKb + " KB, " + when + " UTC)", 13, false), Views.matchWrap());
        Button actionButton = Views.secondaryButton(context, actionLabel);
        actionButton.setOnClickListener(v -> onAction.run());
        row.addView(actionButton, Views.matchWrapTop(context, 4));
        driveResultsContainer.addView(row, Views.matchWrapTop(context, 10));
    }

    private void addDriveNotFoundRow(String kindLabel) {
        driveResultsContainer.addView(Views.label(requireContext(), "No " + kindLabel + " found in the Drive folder.", 13, false),
                Views.matchWrapTop(requireContext(), 10));
    }

    private void pushFirmwareFromDrive(DriveUpdates.DriveFile file) {
        firmwareStatusText.setText("Firmware update: downloading " + file.name + " from Drive...");
        File dest = new File(requireContext().getCacheDir(), "ota_download.bin");
        DriveUpdates.download(file.id, dest, success -> {
            if (!isAdded()) {
                return;
            }
            if (!success) {
                firmwareStatusText.setText("Firmware update: failed to download " + file.name + " from Drive");
                return;
            }
            try (InputStream input = new FileInputStream(dest)) {
                pushFirmwareBytes(readAllBytes(input));
            } catch (IOException exception) {
                firmwareStatusText.setText("Firmware update: failed to read downloaded file -- " + exception.getMessage());
            }
        });
    }

    private void installApkFromDrive(DriveUpdates.DriveFile file) {
        driveCheckStatusText.setText("Downloading " + file.name + "...");
        File dest = new File(requireContext().getCacheDir(), "update.apk");
        DriveUpdates.download(file.id, dest, success -> {
            if (!isAdded()) {
                return;
            }
            if (!success) {
                driveCheckStatusText.setText("Failed to download " + file.name);
                return;
            }
            Context context = requireContext();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.getPackageManager().canRequestPackageInstalls()) {
                driveCheckStatusText.setText("Grant \"install unknown apps\" for CarTheftGuard, then tap Install again.");
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + context.getPackageName())));
                return;
            }
            Uri apkUri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", dest);
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(apkUri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(install);
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
        pushFirmwareBytes(firmware);
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

    private void pushFirmwareBytes(byte[] firmware) {
        pushFirmwareButton.setEnabled(false);
        firmwareStatusText.setText("Firmware update: uploading 0%");
        boardLink.pushFirmware(firmware, new BoardLink.OtaListener() {
            @Override
            public void onProgress(int percent) {
                if (firmwareStatusText != null) {
                    firmwareStatusText.setText("Firmware update: uploading " + percent + "%");
                }
            }

            @Override
            public void onResult(boolean success, String message) {
                if (firmwareStatusText == null) {
                    return;
                }
                /* Either outcome can legitimately mean the board rebooted mid-response
                 * (see BoardLink.pushFirmware's doc comment) -- confirm independently by
                 * polling GET /api/ota rather than trusting this callback alone. */
                firmwareStatusText.setText("Firmware update: upload finished (" + message + "), confirming reboot...");
                handler.removeCallbacks(otaConfirmRunnable);
                otaConfirmAttempts = 0;
                handler.postDelayed(otaConfirmRunnable, OTA_CONFIRM_POLL_INTERVAL_MS);
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
