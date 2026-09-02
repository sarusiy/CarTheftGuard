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
