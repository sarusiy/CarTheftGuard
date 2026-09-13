package com.sarusiy.cartheftguard.ui;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.sarusiy.cartheftguard.BoardLink;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Fiat" tab: vehicle-specific view for the 2014 Fiat 500, showing only the
 * PIDs actually confirmed against the real car (see
 * JC-ESP32P4-M3/captures/fiat500_2014/PID_SUPPORT_ANALYSIS.md). Deliberately
 * separate from the generic Monitor tab -- as more of this car's supported
 * PIDs (0x04 engine load, 0x06/0x07 fuel trims, 0x0B intake MAP, 0x0E timing
 * advance, 0x0F intake air temp, 0x1F runtime, and whatever's in the second
 * PID page 0x21-0x40) get decoded in firmware, add them here the same way.
 */
public class FiatMonitorFragment extends Fragment implements BoardLink.Listener {
    private static final int POLL_INTERVAL_MS = 1000;

    private static final class Metric {
        final String label;
        final String unit;
        String value = "--";

        Metric(String label, String unit) {
            this.label = label;
            this.unit = unit;
        }
    }

    /* Ordered so newly-decoded PIDs can just be appended here later. */
    private final Map<String, Metric> metrics = new LinkedHashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BoardLink boardLink;
    private LinearLayout boxGrid;
    private TextView noteText;
    private TextView pidPagesText;
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (isAdded()) {
                boardLink.fetchObdData();
                handler.postDelayed(this, POLL_INTERVAL_MS);
            }
        }
    };

    public FiatMonitorFragment() {
        /* Currently-decoded PIDs only -- confirmed supported by this car's
         * own PID-support bitmask (be3eb813), and already decoded in
         * main.c's obd_print_response(). */
        metrics.put("coolant", new Metric("Coolant Temp (PID 0x05)", "°C"));
        metrics.put("rpm", new Metric("Engine RPM (PID 0x0C)", "rpm"));
        metrics.put("speed", new Metric("Vehicle Speed (PID 0x0D)", "km/h"));
        metrics.put("throttle", new Metric("Throttle Position (PID 0x11)", "%"));
    }

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
    }

    @Override
    public void onStop() {
        handler.removeCallbacks(pollRunnable);
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

        root.addView(Views.label(context, "Fiat 500 (2014)", 24, true), Views.matchWrap());
        root.addView(Views.label(context, "Only PIDs confirmed supported on the real car. "
                        + "More get added here as they're decoded -- see PID_SUPPORT_ANALYSIS.md.", 12, false),
                Views.matchWrapTop(context, 6));

        boxGrid = new LinearLayout(context);
        boxGrid.setOrientation(LinearLayout.VERTICAL);
        root.addView(boxGrid, Views.matchWrapTop(context, 20));

        root.addView(Views.label(context, "Known supported-PID pages", 16, true), Views.matchWrapTop(context, 28));
        pidPagesText = Views.label(context, "PIDs 01-20: -- / PIDs 21-40: --", 13, false);
        pidPagesText.setTextColor(0xff52616b);
        root.addView(pidPagesText, Views.matchWrapTop(context, 6));
        root.addView(Views.label(context, "Confirmed page-1 support (be3eb813): 01,03,04,05,06,07,0B,0C,0D,0E,0F,"
                        + "11,13,14,15,1C,1F,20 -- MAP-based fueling (0x0B, no MAF), single O2 sensor pair "
                        + "(0x13/14/15). Page 2 (0x21-0x40) queried but not yet decoded into named fields.", 12, false),
                Views.matchWrapTop(context, 4));

        noteText = Views.label(context, "", 12, false);
        noteText.setTextColor(0xff52616b);
        noteText.setText("Live values refresh from the P4 over Wi-Fi.");
        root.addView(noteText, Views.matchWrapTop(context, 20));

        ScrollView scroll = new ScrollView(context);
        scroll.addView(root);
        renderBoxes();
        return scroll;
    }

    private void renderBoxes() {
        if (boxGrid == null) {
            return;
        }
        Context context = requireContext();
        boxGrid.removeAllViews();
        List<Metric> all = new ArrayList<>(metrics.values());
        for (int i = 0; i < all.size(); i += 2) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(buildBox(all.get(i)), rowCellParams(context));
            if (i + 1 < all.size()) {
                row.addView(buildBox(all.get(i + 1)), rowCellParams(context));
            } else {
                row.addView(new View(context), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            }
            boxGrid.addView(row, Views.matchWrapTop(context, i == 0 ? 0 : 10));
        }
    }

    private LinearLayout.LayoutParams rowCellParams(Context context) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        params.rightMargin = Views.dp(context, 8);
        return params;
    }

    private View buildBox(Metric metric) {
        Context context = requireContext();
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Views.dp(context, 14);
        box.setPadding(pad, pad, pad, pad);
        box.setBackgroundColor(0xffffffff);

        TextView labelView = Views.label(context, metric.label, 12, true);
        labelView.setTextColor(0xff52616b);
        box.addView(labelView, Views.matchWrap());

        TextView valueView = Views.label(context, metric.value, 22, true);
        valueView.setTextColor(0xff0b6e69);
        box.addView(valueView, Views.matchWrapTop(context, 4));

        TextView unitView = Views.label(context, metric.unit, 12, false);
        unitView.setTextColor(0xff52616b);
        box.addView(unitView, Views.matchWrapTop(context, 2));
        return box;
    }

    @Override
    public void onObdData(String json) {
        try {
            JSONObject data = new JSONObject(json);
            metrics.get("coolant").value = String.valueOf(data.optInt("coolant_c", 0));
            metrics.get("rpm").value = String.valueOf(data.optInt("rpm", 0));
            metrics.get("speed").value = String.valueOf(data.optInt("speed_kmh", 0));
            metrics.get("throttle").value = String.valueOf(data.optInt("throttle_pct", 0));
            if (pidPagesText != null) {
                pidPagesText.setText("PIDs 01-20: " + data.optString("supported_pids", "--")
                        + " / PIDs 21-40: " + data.optString("supported_pids_2", "--"));
            }
            noteText.setText("Live CAN data from " + boardLink.getBoardIp());
            renderBoxes();
        } catch (JSONException exception) {
            noteText.setText("Invalid OBD data received from the P4.");
        }
    }
}
