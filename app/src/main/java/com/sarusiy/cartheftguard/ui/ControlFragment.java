package com.sarusiy.cartheftguard.ui;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.sarusiy.cartheftguard.BoardLink;

/** "Control" tab: commands sent to the board over the Wi-Fi link established on the Connect tab. */
public class ControlFragment extends Fragment implements BoardLink.Listener {

    private BoardLink boardLink;

    private TextView statusText;
    private TextView linkText;
    private EditText frequencyInput;
    private Button frequencyButton;
    private TextView canModeText;
    private Button activeModeButton;
    private Button passiveModeButton;
    private TextView partnerText;
    private Button simMode11Button;
    private Button simMode29Button;

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
        updateLinkState();
        refreshCanMode();
        refreshCanPartner();
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

        root.addView(Views.label(context, "Control", 24, true), Views.matchWrap());
        linkText = Views.label(context, "Wi-Fi link: not connected", 14, false);
        root.addView(linkText, Views.matchWrapTop(context, 12));
        statusText = Views.label(context, "Status: idle", 15, true);
        root.addView(statusText, Views.matchWrapTop(context, 8));

        root.addView(Views.label(context, "Blink half-period", 16, true), Views.matchWrapTop(context, 28));
        frequencyInput = Views.input(context, "Milliseconds: " + BoardLink.MIN_FREQ_MS + " to " + BoardLink.MAX_FREQ_MS, InputType.TYPE_CLASS_NUMBER);
        frequencyInput.setText("250");
        root.addView(frequencyInput, Views.matchHeightTop(context, 54, 8));

        LinearLayout presets = new LinearLayout(context);
        int[] values = {100, 250, 500, 1000};
        for (int value : values) {
            Button preset = Views.secondaryButton(context, String.valueOf(value));
            preset.setOnClickListener(view -> frequencyInput.setText(String.valueOf(value)));
            presets.addView(preset, new LinearLayout.LayoutParams(0, Views.dp(context, 44), 1));
        }
        root.addView(presets, Views.matchWrapTop(context, 10));

        frequencyButton = Views.primaryButton(context, "Send Frequency Over Wi-Fi");
        frequencyButton.setEnabled(boardLink.isWifiReady());
        frequencyButton.setOnClickListener(view -> sendFrequency());
        root.addView(frequencyButton, Views.matchHeightTop(context, 52, 16));

        root.addView(Views.label(context, "CAN Bus Mode", 16, true), Views.matchWrapTop(context, 28));
        canModeText = Views.label(context, "CAN mode: unknown", 14, false);
        root.addView(canModeText, Views.matchWrapTop(context, 4));
        root.addView(Views.label(context, "Active sends requests on the bus (needed for Monitor's Supported PIDs, "
                        + "and for the Faults tab); Passive only listens, never transmits.", 12, false),
                Views.matchWrapTop(context, 4));
        LinearLayout modeButtons = new LinearLayout(context);
        modeButtons.setOrientation(LinearLayout.HORIZONTAL);
        passiveModeButton = Views.secondaryButton(context, "Set Passive");
        passiveModeButton.setOnClickListener(view -> setCanMode("passive"));
        modeButtons.addView(passiveModeButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        activeModeButton = Views.primaryButton(context, "Set Active");
        activeModeButton.setOnClickListener(view -> setCanMode("active"));
        LinearLayout.LayoutParams activeParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        activeParams.leftMargin = Views.dp(context, 8);
        modeButtons.addView(activeModeButton, activeParams);
        root.addView(modeButtons, Views.matchWrapTop(context, 8));

        root.addView(Views.label(context, "Bus Partner", 16, true), Views.matchWrapTop(context, 28));
        partnerText = Views.label(context, "Partner: unknown", 14, false);
        root.addView(partnerText, Views.matchWrapTop(context, 4));
        root.addView(Views.label(context, "Who's actually answering on the bus, and which OBD-II addressing "
                        + "scheme they use -- SIM_* is the bench simulator, CAR_* is a real vehicle. The two "
                        + "buttons below only work against the simulator (a real car ignores them) and are "
                        + "only enabled while a simulator is detected.", 12, false),
                Views.matchWrapTop(context, 4));
        LinearLayout simModeButtons = new LinearLayout(context);
        simModeButtons.setOrientation(LinearLayout.HORIZONTAL);
        simMode11Button = Views.secondaryButton(context, "Sim: 11-bit");
        simMode11Button.setEnabled(false);
        simMode11Button.setOnClickListener(view -> setSimulatorMode("11"));
        simModeButtons.addView(simMode11Button, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        simMode29Button = Views.secondaryButton(context, "Sim: 29-bit");
        simMode29Button.setEnabled(false);
        simMode29Button.setOnClickListener(view -> setSimulatorMode("29"));
        LinearLayout.LayoutParams simMode29Params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        simMode29Params.leftMargin = Views.dp(context, 8);
        simModeButtons.addView(simMode29Button, simMode29Params);
        root.addView(simModeButtons, Views.matchWrapTop(context, 8));
        Button refreshPartnerButton = Views.secondaryButton(context, "Refresh partner status");
        refreshPartnerButton.setOnClickListener(view -> refreshCanPartner());
        root.addView(refreshPartnerButton, Views.matchHeightTop(context, 44, 8));

        root.addView(Views.label(context, "More controls (headlights, horn, lock, etc.) land here as the firmware grows.", 12, false),
                Views.matchWrapTop(context, 24));

        ScrollView scroll = new ScrollView(context);
        scroll.addView(root);
        return scroll;
    }

    private void sendFrequency() {
        int value;
        try {
            value = Integer.parseInt(frequencyInput.getText().toString().trim());
        } catch (NumberFormatException exception) {
            setStatus("Enter a valid frequency value", BoardLink.COLOR_ERROR);
            return;
        }
        boardLink.sendFrequency(value);
    }

    private void setCanMode(String mode) {
        boardLink.setCanMode(mode, success -> refreshCanMode());
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

    private void setSimulatorMode(String mode) {
        boardLink.setSimulatorMode(mode, success -> refreshCanPartner());
    }

    private void refreshCanPartner() {
        boardLink.fetchCanPartner(partner -> {
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                if (partnerText != null) {
                    partnerText.setText("Partner: " + partner);
                }
                boolean isSimulator = partner.startsWith("SIM_");
                if (simMode11Button != null) {
                    simMode11Button.setEnabled(isSimulator);
                }
                if (simMode29Button != null) {
                    simMode29Button.setEnabled(isSimulator);
                }
            });
        });
    }

    private void updateLinkState() {
        linkText.setText(boardLink.isWifiReady() ? "Wi-Fi link: " + boardLink.getBoardIp() : "Wi-Fi link: not connected");
        frequencyButton.setEnabled(boardLink.isWifiReady());
    }

    private void setStatus(String status, int color) {
        if (statusText != null) {
            statusText.setText("Status: " + status);
            statusText.setTextColor(color);
        }
    }

    @Override
    public void onStatus(String status, int color) {
        setStatus(status, color);
    }

    @Override
    public void onWifiConnected(String boardIp) {
        updateLinkState();
    }

    @Override
    public void onBoardConnectionChanged(boolean connected) {
        if (!connected) {
            updateLinkState();
        }
    }
}
