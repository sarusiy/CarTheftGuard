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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.sarusiy.cartheftguard.BoardLink;
import com.sarusiy.cartheftguard.CanCaptureService;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * "Learn" tab: a guided, step-by-step wizard for correlating CAN traffic
 * with real physical actions on the car (lock, unlock, door, ignition).
 * Each step auto-records a short, clearly-labeled CSV via CanCaptureService,
 * then a simple diff against the baseline step highlights candidate CAN IDs
 * -- new IDs that only appeared during that action, and existing IDs whose
 * payload changed -- so you don't have to manually correlate timestamps or
 * eyeball raw CSVs yourself.
 */
public final class LearnFragment extends Fragment {

    private static final class Step {
        final String id;
        final String title;
        final String instructions;
        final int durationSec;

        Step(String id, String title, String instructions, int durationSec) {
            this.id = id;
            this.title = title;
            this.instructions = instructions;
            this.durationSec = durationSec;
        }
    }

    private static final class Car {
        final String id;
        final String label;

        Car(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    private static final Car[] CARS = {
            new Car("fiat500_2014", "Fiat 500 (2014)"),
            new Car("fabia_2026", "Skoda Fabia (2026)"),
    };

    private static final String PREFS_NAME = "learn_prefs";
    private static final String PREF_SELECTED_CAR = "selected_car";

    private static final Step[] STEPS = {
            new Step("baseline", "1. Baseline",
                    "Engine off, don't touch anything. Lets us know what's on the bus with nothing happening.",
                    15),
            new Step("lock", "2. Lock (key fob)",
                    "From outside the car, press the lock button on the key fob 2-3 times.",
                    8),
            new Step("unlock", "3. Unlock (key fob)",
                    "From outside the car, press the unlock button on the key fob 2-3 times.",
                    8),
            new Step("door_open", "4. Open driver door",
                    "Manually open the driver's door.",
                    6),
            new Step("door_close", "5. Close driver door",
                    "Close the driver's door.",
                    6),
            new Step("ignition_acc", "6. Ignition ACC (no start)",
                    "Turn the key/press start to ACC/ignition-on ONLY -- do not start the engine.",
                    8),
    };

    private BoardLink boardLink;
    private int currentStepIndex;
    private boolean stepRunning;
    private boolean receiverRegistered;
    private String selectedCarId;
    private final Map<String, String> stepFiles = new LinkedHashMap<>();

    private LinearLayout carButtonsRow;
    private TextView titleText;
    private TextView instructionsText;
    private TextView statusText;
    private Button startButton;
    private Button repeatButton;
    private Button nextButton;
    private Button analyzeButton;
    private TextView resultsText;

    private final Handler autoStopHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoStopRunnable = this::stopCurrentStep;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean running = intent.getBooleanExtra(CanCaptureService.EXTRA_RUNNING, false);
            String file = intent.getStringExtra(CanCaptureService.EXTRA_FILE);
            String error = intent.getStringExtra(CanCaptureService.EXTRA_ERROR);
            if (stepRunning && !running) {
                stepRunning = false;
                autoStopHandler.removeCallbacks(autoStopRunnable);
                if (error != null && !error.isEmpty()) {
                    statusText.setText("Error: " + error);
                } else if (file != null && !file.isEmpty()) {
                    stepFiles.put(STEPS[currentStepIndex].id, file);
                    statusText.setText("Saved: " + new File(file).getName());
                    repeatButton.setEnabled(true);
                    nextButton.setEnabled(currentStepIndex < STEPS.length - 1);
                    analyzeButton.setEnabled(stepFiles.containsKey("baseline") && stepFiles.size() > 1);
                }
                startButton.setEnabled(true);
                startButton.setText("Start recording (" + STEPS[currentStepIndex].durationSec + "s)");
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

        root.addView(Views.label(context, "Learn", 24, true), Views.matchWrap());
        root.addView(Views.label(context,
                        "Guided capture: one short, labeled recording per action, then a diff against the baseline "
                                + "highlights candidate CAN IDs for you.", 12, false),
                Views.matchWrapTop(context, 4));

        root.addView(Views.label(context, "Car", 16, true), Views.matchWrapTop(context, 20));
        carButtonsRow = new LinearLayout(context);
        carButtonsRow.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(carButtonsRow, Views.matchWrapTop(context, 4));
        refreshCarButtons();

        titleText = Views.label(context, "", 18, true);
        root.addView(titleText, Views.matchWrapTop(context, 24));
        instructionsText = Views.label(context, "", 14, false);
        root.addView(instructionsText, Views.matchWrapTop(context, 6));
        statusText = Views.label(context, "", 13, false);
        statusText.setTextColor(0xff52616b);
        root.addView(statusText, Views.matchWrapTop(context, 8));

        startButton = Views.primaryButton(context, "Start recording");
        startButton.setOnClickListener(view -> startCurrentStep());
        root.addView(startButton, Views.matchHeightTop(context, 52, 16));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        repeatButton = Views.secondaryButton(context, "Repeat this step");
        repeatButton.setEnabled(false);
        repeatButton.setOnClickListener(view -> resetCurrentStepUi());
        row.addView(repeatButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        nextButton = Views.primaryButton(context, "Next step");
        nextButton.setEnabled(false);
        nextButton.setOnClickListener(view -> goToNextStep());
        LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        nextParams.leftMargin = Views.dp(context, 8);
        row.addView(nextButton, nextParams);
        root.addView(row, Views.matchWrapTop(context, 10));

        analyzeButton = Views.secondaryButton(context, "Analyze recorded steps");
        analyzeButton.setEnabled(false);
        analyzeButton.setOnClickListener(view -> runAnalysis());
        root.addView(analyzeButton, Views.matchHeightTop(context, 48, 20));

        root.addView(Views.label(context, "Candidate CAN IDs", 16, true), Views.matchWrapTop(context, 16));
        resultsText = new TextView(context);
        resultsText.setTextSize(11);
        resultsText.setTypeface(android.graphics.Typeface.MONOSPACE);
        resultsText.setTextColor(0xff1f2933);
        resultsText.setBackgroundColor(0xffffffff);
        resultsText.setPadding(Views.dp(context, 8), Views.dp(context, 8), Views.dp(context, 8), Views.dp(context, 8));
        resultsText.setTextIsSelectable(true);
        resultsText.setText("Record the baseline plus at least one action, then tap Analyze.");
        root.addView(resultsText, Views.matchWrapTop(context, 8));

        ScrollView scroll = new ScrollView(context);
        scroll.addView(root);
        showStep(currentStepIndex);
        return scroll;
    }

    @Override
    public void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(CanCaptureService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            requireContext().registerReceiver(statusReceiver, filter);
        }
        receiverRegistered = true;
    }

    @Override
    public void onStop() {
        autoStopHandler.removeCallbacks(autoStopRunnable);
        if (receiverRegistered) {
            requireContext().unregisterReceiver(statusReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    private void refreshCarButtons() {
        if (carButtonsRow == null) {
            return;
        }
        Context context = requireContext();
        carButtonsRow.removeAllViews();
        for (int i = 0; i < CARS.length; i++) {
            Car car = CARS[i];
            boolean selected = car.id.equals(selectedCarId);
            Button button = selected ? Views.primaryButton(context, car.label) : Views.secondaryButton(context, car.label);
            button.setOnClickListener(view -> onCarPicked(car));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i > 0) {
                params.leftMargin = Views.dp(context, 8);
            }
            carButtonsRow.addView(button, params);
        }
    }

    private void onCarPicked(Car car) {
        if (car.id.equals(selectedCarId)) {
            return;
        }
        if (!stepFiles.isEmpty()) {
            new AlertDialog.Builder(requireContext())
                    .setTitle("Switch car?")
                    .setMessage("Switching to " + car.label + " will clear the steps recorded so far in this "
                            + "session (the files themselves are kept on disk, just no longer tracked here).")
                    .setPositiveButton("Switch", (dialog, which) -> selectCar(car))
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
            selectCar(car);
        }
    }

    private void selectCar(Car car) {
        selectedCarId = car.id;
        prefs(requireContext()).edit().putString(PREF_SELECTED_CAR, car.id).apply();
        stepFiles.clear();
        currentStepIndex = 0;
        resultsText.setText("Record the baseline plus at least one action, then tap Analyze.");
        refreshCarButtons();
        showStep(currentStepIndex);
    }

    private void showStep(int index) {
        Step step = STEPS[index];
        titleText.setText(step.title);
        instructionsText.setText(step.instructions);
        if (selectedCarId == null) {
            statusText.setText("Select a car above first.");
            startButton.setEnabled(false);
        } else {
            statusText.setText(stepFiles.containsKey(step.id)
                    ? "Already recorded: " + new File(stepFiles.get(step.id)).getName()
                    : "Not recorded yet.");
            startButton.setEnabled(true);
        }
        startButton.setText("Start recording (" + step.durationSec + "s)");
        repeatButton.setEnabled(stepFiles.containsKey(step.id));
        nextButton.setEnabled(stepFiles.containsKey(step.id) && index < STEPS.length - 1);
    }

    private void resetCurrentStepUi() {
        statusText.setText("Not recorded yet.");
        startButton.setEnabled(true);
        repeatButton.setEnabled(false);
        nextButton.setEnabled(false);
    }

    private void goToNextStep() {
        if (currentStepIndex < STEPS.length - 1) {
            currentStepIndex++;
            showStep(currentStepIndex);
        }
    }

    private void startCurrentStep() {
        if (selectedCarId == null) {
            statusText.setText("Select a car above first.");
            return;
        }
        if (!boardLink.isWifiReady()) {
            statusText.setText("Connect the board to Wi-Fi first (Connect tab).");
            return;
        }
        Step step = STEPS[currentStepIndex];
        stepRunning = true;
        startButton.setEnabled(false);
        repeatButton.setEnabled(false);
        nextButton.setEnabled(false);
        statusText.setText("Recording... (" + step.durationSec + "s) -- do the action now.");
        Intent intent = new Intent(requireContext(), CanCaptureService.class)
                .setAction(CanCaptureService.ACTION_START)
                .putExtra(CanCaptureService.EXTRA_BOARD_IP, boardLink.getBoardIp())
                .putExtra(CanCaptureService.EXTRA_PASSIVE, true)
                .putExtra(CanCaptureService.EXTRA_LABEL, "learn-" + selectedCarId + "-" + step.id)
                .putExtra(CanCaptureService.EXTRA_CAR, selectedCarId);
        ContextCompat.startForegroundService(requireContext(), intent);
        autoStopHandler.postDelayed(autoStopRunnable, step.durationSec * 1000L);
    }

    private void stopCurrentStep() {
        Intent intent = new Intent(requireContext(), CanCaptureService.class)
                .setAction(CanCaptureService.ACTION_STOP);
        requireContext().startService(intent);
    }

    // ---- Analysis: simple CSV diff, no external dependencies ----

    /** can_id (as written in the CSV, e.g. "0x1A2") -> distinct data_hex payloads seen. */
    private Map<String, Set<String>> readIdsAndPayloads(String csvPath) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(csvPath))) {
            String line = reader.readLine(); // header
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split(",", -1);
                if (fields.length < 9) {
                    continue;
                }
                String id = fields[4];
                String data = fields[8];
                result.computeIfAbsent(id, key -> new LinkedHashSet<>()).add(data);
            }
        } catch (Exception ignored) {
            // Missing/unreadable file -- treated as empty, surfaced via the summary instead of crashing.
        }
        return result;
    }

    private void runAnalysis() {
        String baselineFile = stepFiles.get("baseline");
        if (baselineFile == null) {
            resultsText.setText("Record the baseline step first.");
            return;
        }
        Map<String, Set<String>> baseline = readIdsAndPayloads(baselineFile);

        // An ID that already shows more than one distinct payload during the
        // baseline (engine off, nothing touched) is naturally noisy -- e.g.
        // speed/RPM broadcasts that change on their own -- so it can never
        // give a meaningful NEW/CHANGED signal and only adds false positives.
        Set<String> noisyIds = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> entry : baseline.entrySet()) {
            if (entry.getValue().size() > 1) {
                noisyIds.add(entry.getKey());
            }
        }

        StringBuilder summary = new StringBuilder();
        if (!noisyIds.isEmpty()) {
            summary.append("Ignoring background-noise IDs (already vary at baseline/idle): ")
                    .append(String.join(", ", noisyIds)).append("\n\n");
        }
        for (Step step : STEPS) {
            if (step.id.equals("baseline") || !stepFiles.containsKey(step.id)) {
                continue;
            }
            Map<String, Set<String>> action = readIdsAndPayloads(stepFiles.get(step.id));
            summary.append("== ").append(step.title).append(" ==\n");

            boolean any = false;
            for (Map.Entry<String, Set<String>> entry : action.entrySet()) {
                String id = entry.getKey();
                if (noisyIds.contains(id)) {
                    continue;
                }
                if (!baseline.containsKey(id)) {
                    summary.append("  NEW id ").append(id)
                            .append(" (").append(entry.getValue().size()).append(" distinct payload(s))\n");
                    any = true;
                } else {
                    Set<String> newPayloads = new LinkedHashSet<>(entry.getValue());
                    newPayloads.removeAll(baseline.get(id));
                    if (!newPayloads.isEmpty()) {
                        summary.append("  CHANGED id ").append(id).append(": ")
                                .append(String.join(", ", newPayloads)).append("\n");
                        any = true;
                    }
                }
            }
            if (!any) {
                summary.append("  (no new/changed IDs vs baseline -- try repeating the action, "
                        + "or it may be outside the bus segment this OBD-II port exposes)\n");
            }
            summary.append("\n");
        }
        resultsText.setText(summary.length() > 0 ? summary.toString() : "No steps recorded besides baseline yet.");
    }
}
