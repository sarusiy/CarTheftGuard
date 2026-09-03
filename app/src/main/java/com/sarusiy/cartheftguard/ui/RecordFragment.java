package com.sarusiy.cartheftguard.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.sarusiy.cartheftguard.BoardLink;
import com.sarusiy.cartheftguard.CanCaptureService;

import java.io.File;
import java.text.DateFormat;
import java.util.Arrays;
import java.util.Date;

/** Starts and monitors raw CAN recording to an app-owned CSV file. */
public final class RecordFragment extends Fragment implements BoardLink.Listener {
    private BoardLink boardLink;
    private Button startButton;
    private Button stopButton;
    private CheckBox passiveCheckBox;
    private TextView connectionText;
    private TextView countText;
    private TextView droppedText;
    private TextView fileText;
    private TextView capturesText;
    private boolean receiverRegistered;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean running = intent.getBooleanExtra(CanCaptureService.EXTRA_RUNNING, false);
            long frames = intent.getLongExtra(CanCaptureService.EXTRA_FRAMES, 0);
            long dropped = intent.getLongExtra(CanCaptureService.EXTRA_DROPPED, 0);
            String file = intent.getStringExtra(CanCaptureService.EXTRA_FILE);
            String error = intent.getStringExtra(CanCaptureService.EXTRA_ERROR);
            startButton.setEnabled(!running && boardLink.isWifiReady());
            stopButton.setEnabled(running);
            passiveCheckBox.setEnabled(!running);
            countText.setText("Frames recorded: " + frames);
            droppedText.setText("Frames dropped or overflowed: " + dropped);
            if (file != null && !file.isEmpty()) {
                fileText.setText("File: " + file);
            }
            if (error != null && !error.isEmpty()) {
                connectionText.setText("Recorder error: " + error);
            } else if (running) {
                connectionText.setText("Recording from " + boardLink.getBoardIp());
            } else {
                updateConnectionState();
            }
        }
    };

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        boardLink = BoardLink.getInstance(context);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        Context context = requireContext();
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = Views.dp(context, 20);
        root.setPadding(padding, padding, padding, padding);
        root.setBackgroundColor(0xfff5f2ea);

        root.addView(Views.label(context, "CAN Recorder", 24, true), Views.matchWrap());
        connectionText = Views.label(context, "", 14, false);
        root.addView(connectionText, Views.matchWrapTop(context, 12));

        passiveCheckBox = new CheckBox(context);
        passiveCheckBox.setText("Passive listen-only (real vehicle)");
        passiveCheckBox.setTextColor(0xff1f2933);
        passiveCheckBox.setChecked(true);
        root.addView(passiveCheckBox, Views.matchWrapTop(context, 16));
        TextView modeNote = Views.label(context,
                "Clear this only for the two-node simulator; active mode sends OBD requests.",
                12, false);
        modeNote.setTextColor(0xff52616b);
        root.addView(modeNote, Views.matchWrapTop(context, 2));

        startButton = Views.primaryButton(context, "Start recording");
        startButton.setOnClickListener(view -> startRecording());
        root.addView(startButton, Views.matchHeightTop(context, 52, 20));

        stopButton = Views.secondaryButton(context, "Stop recording");
        stopButton.setEnabled(false);
        stopButton.setOnClickListener(view -> stopRecording());
        root.addView(stopButton, Views.matchHeightTop(context, 48, 10));

        countText = Views.label(context, "Frames recorded: 0", 16, true);
        root.addView(countText, Views.matchWrapTop(context, 24));
        droppedText = Views.label(context, "Frames dropped or overflowed: 0", 14, false);
        root.addView(droppedText, Views.matchWrapTop(context, 8));
        fileText = Views.label(context, "File: not started", 12, false);
        fileText.setTextIsSelectable(true);
        root.addView(fileText, Views.matchWrapTop(context, 12));

        TextView note = Views.label(context,
                "Recordings are CSV files in the app's external files/can-captures directory. "
                        + "The raw CAN IDs and payloads remain unchanged for later analysis.",
                12, false);
        note.setTextColor(0xff52616b);
        root.addView(note, Views.matchWrapTop(context, 20));

        root.addView(Views.label(context, "Saved recordings", 16, true),
                Views.matchWrapTop(context, 24));
        Button refreshButton = Views.secondaryButton(context, "Refresh recordings");
        refreshButton.setOnClickListener(view -> refreshCaptureList());
        root.addView(refreshButton, Views.matchHeightTop(context, 46, 8));
        capturesText = Views.label(context, "", 12, false);
        capturesText.setTextIsSelectable(true);
        root.addView(capturesText, Views.matchWrapTop(context, 8));

        ScrollView scroll = new ScrollView(context);
        scroll.addView(root);
        updateConnectionState();
        refreshCaptureList();
        return scroll;
    }

    @Override
    public void onStart() {
        super.onStart();
        boardLink.addListener(this);
        IntentFilter filter = new IntentFilter(CanCaptureService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            requireContext().registerReceiver(statusReceiver, filter);
        }
        receiverRegistered = true;
        updateConnectionState();
    }

    @Override
    public void onStop() {
        if (receiverRegistered) {
            requireContext().unregisterReceiver(statusReceiver);
            receiverRegistered = false;
        }
        boardLink.removeListener(this);
        super.onStop();
    }

    private void startRecording() {
        if (!boardLink.isWifiReady()) {
            updateConnectionState();
            return;
        }
        Intent intent = new Intent(requireContext(), CanCaptureService.class)
                .setAction(CanCaptureService.ACTION_START)
                .putExtra(CanCaptureService.EXTRA_BOARD_IP, boardLink.getBoardIp())
                .putExtra(CanCaptureService.EXTRA_PASSIVE, passiveCheckBox.isChecked());
        ContextCompat.startForegroundService(requireContext(), intent);
    }

    private void stopRecording() {
        Intent intent = new Intent(requireContext(), CanCaptureService.class)
                .setAction(CanCaptureService.ACTION_STOP);
        requireContext().startService(intent);
    }

    private void refreshCaptureList() {
        if (capturesText == null) {
            return;
        }
        File directory = new File(requireContext().getExternalFilesDir(null), "can-captures");
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".csv"));
        if (files == null || files.length == 0) {
            capturesText.setText("No recordings yet.");
            return;
        }

        Arrays.sort(files, (left, right) -> Long.compare(right.lastModified(), left.lastModified()));
        DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM);
        StringBuilder summary = new StringBuilder();
        int count = Math.min(files.length, 10);
        for (int index = 0; index < count; index++) {
            File file = files[index];
            summary.append(file.getName())
                    .append("  ")
                    .append(file.length() / 1024)
                    .append(" KB  ")
                    .append(dateFormat.format(new Date(file.lastModified())))
                    .append('\n');
        }
        capturesText.setText(summary.toString().trim());
    }

    private void updateConnectionState() {
        boolean ready = boardLink != null && boardLink.isWifiReady();
        if (connectionText != null) {
            connectionText.setText(ready
                    ? "P4 Wi-Fi ready: " + boardLink.getBoardIp()
                    : "Connect the P4 to Wi-Fi before recording.");
        }
        if (startButton != null) {
            startButton.setEnabled(ready);
        }
    }

    @Override
    public void onWifiConnected(String boardIp) {
        updateConnectionState();
    }

    @Override
    public void onBoardConnectionChanged(boolean connected) {
        updateConnectionState();
    }
}
