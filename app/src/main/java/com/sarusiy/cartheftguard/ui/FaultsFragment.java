package com.sarusiy.cartheftguard.ui;

import android.content.Context;
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
import androidx.fragment.app.Fragment;

import com.sarusiy.cartheftguard.BoardLink;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** "Faults" tab: active DTCs (fault codes) reported by the P4 over CAN, with
 * a button to simulate one (round-trips through the P4 to the Arduino
 * simulator over the real CAN bus) and a button to clear them. */
public class FaultsFragment extends Fragment implements BoardLink.Listener {
    private static final int POLL_INTERVAL_MS = 1000;
    private static final int SIMULATED_FAULT_COUNT = 5;

    private BoardLink boardLink;
    private LinearLayout faultList;
    private TextView noteText;
    private int nextSimulatedFaultIndex;
    /** CAN mode ("active"/"passive") the board was in when this tab was entered, so it can be
     * restored on exit. Reading and clearing DTCs both require active mode (see BoardLink /
     * P4 firmware), so this tab forces it on entry rather than silently showing nothing. */
    private String previousCanMode;
    private boolean restoreCanModeOnExit;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (isAdded()) {
                boardLink.fetchDtcs();
                handler.postDelayed(this, POLL_INTERVAL_MS);
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
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return buildView();
    }

    @Override
    public void onStart() {
        super.onStart();
        boardLink.addListener(this);
        handler.post(pollRunnable);

        restoreCanModeOnExit = false;
        boardLink.fetchCanMode(mode -> {
            previousCanMode = "ACTIVE".equals(mode) ? "active" : "passive";
            if (!"active".equals(previousCanMode)) {
                restoreCanModeOnExit = true;
                boardLink.setCanMode("active", success -> { });
            }
        });
    }

    @Override
    public void onStop() {
        handler.removeCallbacks(pollRunnable);
        boardLink.removeListener(this);
        if (restoreCanModeOnExit && previousCanMode != null) {
            boardLink.setCanMode(previousCanMode, success -> { });
        }
        super.onStop();
    }

    private View buildView() {
        Context context = requireContext();
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = Views.dp(context, 20);
        root.setPadding(padding, padding, padding, padding);
        root.setBackgroundColor(0xfff5f2ea);

        root.addView(Views.label(context, "Faults", 24, true), Views.matchWrap());

        faultList = new LinearLayout(context);
        faultList.setOrientation(LinearLayout.VERTICAL);
        root.addView(faultList, Views.matchWrapTop(context, 20));

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button simulateButton = Views.secondaryButton(context, "Simulate Fault");
        simulateButton.setOnClickListener(view -> {
            boardLink.simulateFault(nextSimulatedFaultIndex);
            nextSimulatedFaultIndex = (nextSimulatedFaultIndex + 1) % SIMULATED_FAULT_COUNT;
        });
        actions.addView(simulateButton, rowCellParams(context));
        Button clearButton = Views.primaryButton(context, "Clear Faults");
        clearButton.setOnClickListener(view -> boardLink.clearDtcs());
        actions.addView(clearButton, rowCellParams(context));
        root.addView(actions, Views.matchWrapTop(context, 20));

        noteText = Views.label(context, "", 12, false);
        noteText.setTextColor(0xff52616b);
        noteText.setText("Live fault codes read from the P4 over Wi-Fi.");
        root.addView(noteText, Views.matchWrapTop(context, 20));

        ScrollView scroll = new ScrollView(context);
        scroll.addView(root);
        renderFaults(new JSONArray());
        return scroll;
    }

    private LinearLayout.LayoutParams rowCellParams(Context context) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        params.rightMargin = Views.dp(context, 8);
        return params;
    }

    private void renderFaults(JSONArray codes) {
        if (faultList == null) {
            return;
        }
        Context context = requireContext();
        faultList.removeAllViews();
        if (codes.length() == 0) {
            faultList.addView(Views.label(context, "No active faults.", 14, false), Views.matchWrap());
            return;
        }
        for (int i = 0; i < codes.length(); i++) {
            JSONObject fault = codes.optJSONObject(i);
            if (fault == null) {
                continue;
            }
            faultList.addView(buildFaultRow(context,
                    fault.optString("code", "?"), fault.optString("description", "")),
                    Views.matchWrapTop(context, i == 0 ? 0 : 10));
        }
    }

    private View buildFaultRow(Context context, String code, String description) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Views.dp(context, 14);
        box.setPadding(pad, pad, pad, pad);
        box.setBackgroundColor(0xfffceceb);

        TextView codeView = Views.label(context, code, 18, true);
        codeView.setTextColor(0xffb3261e);
        box.addView(codeView, Views.matchWrap());

        TextView descriptionView = Views.label(context, description, 13, false);
        descriptionView.setTextColor(0xff52616b);
        box.addView(descriptionView, Views.matchWrapTop(context, 2));
        return box;
    }

    @Override
    public void onDtcData(String json) {
        try {
            JSONObject data = new JSONObject(json);
            JSONArray codes = data.optJSONArray("codes");
            renderFaults(codes != null ? codes : new JSONArray());
            noteText.setText("Live fault codes from " + boardLink.getBoardIp());
        } catch (JSONException exception) {
            noteText.setText("Invalid fault data received from the P4.");
        }
    }
}
