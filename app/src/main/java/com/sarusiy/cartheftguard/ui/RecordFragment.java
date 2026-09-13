package com.sarusiy.cartheftguard.ui;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Deque;
import java.util.List;

/** Starts and monitors raw CAN recording to an app-owned CSV file. */
public final class RecordFragment extends Fragment implements BoardLink.Listener {
    /** Same idea as LearnFragment's car selector, kept as an independent
     * selection/preference (own tab, own use case) -- without this,
     * standalone recordings all land flat in can-captures/ with no way to
     * tell later which vehicle they came from except by cross-referencing
     * timestamps and CAN ID content by hand. "General" (null id) preserves
     * the old flat-file behavior for bench/simulator use. */
    private static final class Car {
        final String id;
        final String label;

        Car(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    private static final Car[] CARS = {
            new Car(null, "General (no car)"),
            new Car("fiat500_2014", "Fiat 500 (2014)"),
            new Car("fabia_2026", "Skoda Fabia (2026)"),
    };

    private static final String PREFS_NAME = "record_prefs";
    private static final String PREF_SELECTED_CAR = "selected_car";

    private BoardLink boardLink;
    private Button startButton;
    private Button stopButton;
    private CheckBox passiveCheckBox;
    private TextView connectionText;
    private TextView countText;
    private TextView droppedText;
    private TextView fileText;
    private TextView capturesText;
    private LinearLayout carButtonsRow;
    private String selectedCarId;
    private boolean receiverRegistered;

    /** Live raw-frame tail: independent of CanCaptureService/CSV recording --
     * both just poll the same GET /api/can, so you can watch live without
     * needing to be actively recording to a file, and vice versa. Kept
     * intentionally simple (last LIVE_MAX_LINES frames, most recent last) so
     * it stays readable while you're also doing something physical in the
     * car, rather than a full unbounded log. */
    private static final int LIVE_POLL_INTERVAL_MS = 300;
    private static final int LIVE_MAX_LINES = 60;
    private TextView liveText;
    private CheckBox liveCheckBox;
    private long liveAfterSeq;
    private final Deque<String> liveLines = new ArrayDeque<>();
    private final Handler liveHandler = new Handler(Looper.getMainLooper());
    private final Runnable livePollRunnable = new Runnable() {
        @Override
        public void run() {
            if (isAdded() && liveCheckBox != null && liveCheckBox.isChecked()) {
                boardLink.fetchCanRaw(liveAfterSeq, RecordFragment.this::onLiveFrames);
            }
            if (isAdded()) {
                liveHandler.postDelayed(this, LIVE_POLL_INTERVAL_MS);
            }
        }
    };

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
        selectedCarId = prefs(context).getString(PREF_SELECTED_CAR, null);
    }

    private SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
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

        root.addView(Views.label(context, "Recording for", 16, true), Views.matchWrapTop(context, 16));
        carButtonsRow = new LinearLayout(context);
        carButtonsRow.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(carButtonsRow, Views.matchWrapTop(context, 6));
        TextView carNote = Views.label(context,
                "Tags the file's folder so you can tell vehicles apart later -- pick \"General\" for bench/simulator use.",
                11, false);
        carNote.setTextColor(0xff52616b);
        root.addView(carNote, Views.matchWrapTop(context, 2));

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
        LinearLayout captureButtonsRow = new LinearLayout(context);
        captureButtonsRow.setOrientation(LinearLayout.HORIZONTAL);
        Button refreshButton = Views.secondaryButton(context, "Refresh recordings");
        refreshButton.setOnClickListener(view -> refreshCaptureList());
        captureButtonsRow.addView(refreshButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button clearAllButton = Views.secondaryButton(context, "Clear all recordings");
        clearAllButton.setOnClickListener(view -> confirmClearAllRecordings());
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        clearParams.leftMargin = Views.dp(context, 8);
        captureButtonsRow.addView(clearAllButton, clearParams);
        root.addView(captureButtonsRow, Views.matchHeightTop(context, 46, 8));
        capturesText = Views.label(context, "", 12, false);
        capturesText.setTextIsSelectable(true);
        root.addView(capturesText, Views.matchWrapTop(context, 8));

        LinearLayout liveHeader = new LinearLayout(context);
        liveHeader.setOrientation(LinearLayout.HORIZONTAL);
        liveHeader.setGravity(android.view.Gravity.CENTER_VERTICAL);
        liveHeader.addView(Views.label(context, "Live raw frames", 16, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button clearLiveButton = Views.secondaryButton(context, "Clear");
        clearLiveButton.setOnClickListener(view -> clearLiveFrames());
        liveHeader.addView(clearLiveButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(liveHeader, Views.matchWrapTop(context, 24));
        liveCheckBox = new CheckBox(context);
        liveCheckBox.setText("Show live (independent of recording -- for watching while you act on the car)");
        liveCheckBox.setTextColor(0xff1f2933);
        liveCheckBox.setChecked(true);
        root.addView(liveCheckBox, Views.matchWrapTop(context, 4));
        TextView liveNote = Views.label(context,
                "Newest at the bottom. Last " + LIVE_MAX_LINES + " frames shown; full history is only in the CSV.",
                11, false);
        liveNote.setTextColor(0xff52616b);
        root.addView(liveNote, Views.matchWrapTop(context, 2));
        liveText = new TextView(context);
        liveText.setTextSize(11);
        liveText.setTypeface(android.graphics.Typeface.MONOSPACE);
        liveText.setTextColor(0xff1f2933);
        liveText.setBackgroundColor(0xffffffff);
        liveText.setPadding(Views.dp(context, 8), Views.dp(context, 8), Views.dp(context, 8), Views.dp(context, 8));
        liveText.setTextIsSelectable(true);
        LinearLayout.LayoutParams liveParams = Views.matchWrapTop(context, 8);
        liveText.setMinHeight(Views.dp(context, 220));
        root.addView(liveText, liveParams);

        ScrollView scroll = new ScrollView(context);
        scroll.addView(root);
        updateConnectionState();
        refreshCaptureList();
        refreshCarButtons();
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
        liveHandler.post(livePollRunnable);
    }

    @Override
    public void onStop() {
        liveHandler.removeCallbacks(livePollRunnable);
        if (receiverRegistered) {
            requireContext().unregisterReceiver(statusReceiver);
            receiverRegistered = false;
        }
        boardLink.removeListener(this);
        super.onStop();
    }

    private void onLiveFrames(String json) {
        try {
            JSONObject data = new JSONObject(json);
            JSONArray frames = data.optJSONArray("frames");
            if (frames == null || frames.length() == 0) {
                return;
            }
            for (int i = 0; i < frames.length(); i++) {
                JSONObject frame = frames.optJSONObject(i);
                if (frame == null) {
                    continue;
                }
                long seq = frame.optLong("seq", liveAfterSeq);
                if (seq > liveAfterSeq) {
                    liveAfterSeq = seq;
                }
                String line = String.format(java.util.Locale.US, "#%-6d id=0x%03X dlc=%d data=%s",
                        seq, frame.optInt("id", 0), frame.optInt("dlc", 0), frame.optString("data", ""));
                liveLines.addLast(line);
                while (liveLines.size() > LIVE_MAX_LINES) {
                    liveLines.removeFirst();
                }
            }
            if (liveText != null) {
                liveText.setText(String.join("\n", liveLines));
            }
        } catch (JSONException exception) {
            // Transient/partial response -- next poll will retry; not worth surfacing to the user.
        }
    }

    /** Clears only the on-screen tail, not liveAfterSeq -- polling keeps
     * following on from wherever it already was, so this just gives a clean
     * screen to watch the next action against instead of a running-together
     * scroll of whatever happened before. */
    private void clearLiveFrames() {
        liveLines.clear();
        if (liveText != null) {
            liveText.setText("");
        }
    }

    private void refreshCarButtons() {
        if (carButtonsRow == null) {
            return;
        }
        Context context = requireContext();
        carButtonsRow.removeAllViews();
        for (int i = 0; i < CARS.length; i++) {
            Car car = CARS[i];
            boolean selected = java.util.Objects.equals(car.id, selectedCarId);
            Button button = selected ? Views.primaryButton(context, car.label) : Views.secondaryButton(context, car.label);
            button.setOnClickListener(view -> selectCar(car));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i > 0) {
                params.leftMargin = Views.dp(context, 8);
            }
            carButtonsRow.addView(button, params);
        }
    }

    private void selectCar(Car car) {
        selectedCarId = car.id;
        prefs(requireContext()).edit().putString(PREF_SELECTED_CAR, car.id).apply();
        refreshCarButtons();
    }

    private void startRecording() {
        if (!boardLink.isWifiReady()) {
            updateConnectionState();
            return;
        }
        Intent intent = new Intent(requireContext(), CanCaptureService.class)
                .setAction(CanCaptureService.ACTION_START)
                .putExtra(CanCaptureService.EXTRA_BOARD_IP, boardLink.getBoardIp())
                .putExtra(CanCaptureService.EXTRA_PASSIVE, passiveCheckBox.isChecked())
                .putExtra(CanCaptureService.EXTRA_CAR, selectedCarId);
        ContextCompat.startForegroundService(requireContext(), intent);
    }

    private void stopRecording() {
        Intent intent = new Intent(requireContext(), CanCaptureService.class)
                .setAction(CanCaptureService.ACTION_STOP);
        requireContext().startService(intent);
    }

    /** Recordings now live either flat under can-captures/ (plain Record tab)
     * or nested under can-captures/&lt;car&gt;/ (Learn tab, per-car folders),
     * so this walks one level of subdirectories instead of a flat listFiles(). */
    private List<File> listAllCaptureFiles() {
        File root = new File(requireContext().getExternalFilesDir(null), "can-captures");
        List<File> result = new ArrayList<>();
        File[] entries = root.listFiles();
        if (entries == null) {
            return result;
        }
        for (File entry : entries) {
            if (entry.isDirectory()) {
                File[] nested = entry.listFiles((dir, name) -> name.endsWith(".csv"));
                if (nested != null) {
                    result.addAll(Arrays.asList(nested));
                }
            } else if (entry.getName().endsWith(".csv")) {
                result.add(entry);
            }
        }
        return result;
    }

    private void refreshCaptureList() {
        if (capturesText == null) {
            return;
        }
        List<File> files = listAllCaptureFiles();
        if (files.isEmpty()) {
            capturesText.setText("No recordings yet.");
            return;
        }

        files.sort((left, right) -> Long.compare(right.lastModified(), left.lastModified()));
        DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM);
        StringBuilder summary = new StringBuilder();
        int count = Math.min(files.size(), 10);
        for (int index = 0; index < count; index++) {
            File file = files.get(index);
            File parent = file.getParentFile();
            boolean nested = parent != null && !parent.getName().equals("can-captures");
            summary.append(nested ? parent.getName() + "/" + file.getName() : file.getName())
                    .append("  ")
                    .append(file.length() / 1024)
                    .append(" KB  ")
                    .append(dateFormat.format(new Date(file.lastModified())))
                    .append('\n');
        }
        capturesText.setText(summary.toString().trim());
    }

    private void confirmClearAllRecordings() {
        List<File> files = listAllCaptureFiles();
        int count = files.size();
        if (count == 0) {
            capturesText.setText("No recordings yet.");
            return;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("Delete all recordings?")
                .setMessage("This will permanently delete all " + count + " recording(s) on this phone "
                        + "(from both Record and Learn). This cannot be undone.")
                .setPositiveButton("Delete", (dialog, which) -> clearAllRecordings(files))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void clearAllRecordings(List<File> files) {
        int deleted = 0;
        for (File file : files) {
            if (file.delete()) {
                deleted++;
            }
        }
        capturesText.setText(deleted + " recording(s) deleted.");
        refreshCaptureList();
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
