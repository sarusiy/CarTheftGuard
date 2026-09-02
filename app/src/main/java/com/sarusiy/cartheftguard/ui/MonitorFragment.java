package com.sarusiy.cartheftguard.ui;

import android.content.Context;
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
import androidx.fragment.app.Fragment;

import com.sarusiy.cartheftguard.BoardLink;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** "Monitor" tab: latest OBD-II values read by the P4 over CAN, with visibility filters. */
public class MonitorFragment extends Fragment implements BoardLink.Listener {

    private static final class Metric {
        final String id;
        final String label;
        final String unit;
        String value = "--";
        boolean visible = true;

        Metric(String id, String label, String unit) {
            this.id = id;
            this.label = label;
            this.unit = unit;
        }
    }

    private final Map<String, Metric> metrics = new LinkedHashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BoardLink boardLink;
    private LinearLayout boxGrid;
    private TextView noteText;
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (isAdded()) {
                boardLink.fetchObdData();
                handler.postDelayed(this, 500);
            }
        }
    };

    public MonitorFragment() {
        addMetric("supported", "Supported PIDs", "bitmask");
        addMetric("coolant", "Coolant Temp", "\u00b0C");
        addMetric("rpm", "Engine RPM", "rpm");
        addMetric("speed", "Vehicle Speed", "km/h");
        addMetric("throttle", "Throttle Position", "%");
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        boardLink = BoardLink.getInstance(context);
    }

    private void addMetric(String id, String label, String unit) {
        metrics.put(id, new Metric(id, label, unit));
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

        root.addView(Views.label(context, "Monitor", 24, true), Views.matchWrap());

        root.addView(Views.label(context, "Show", 16, true), Views.matchWrapTop(context, 20));
        LinearLayout filterRow = new LinearLayout(context);
        filterRow.setOrientation(LinearLayout.VERTICAL);
        for (Metric metric : metrics.values()) {
            CheckBox checkBox = new CheckBox(context);
            checkBox.setText(metric.label);
            checkBox.setTextColor(0xff1f2933);
            checkBox.setChecked(metric.visible);
            checkBox.setOnCheckedChangeListener((button, checked) -> {
                metric.visible = checked;
                renderBoxes();
            });
            filterRow.addView(checkBox, Views.matchWrap());
        }
        root.addView(filterRow, Views.matchWrapTop(context, 4));

        boxGrid = new LinearLayout(context);
        boxGrid.setOrientation(LinearLayout.VERTICAL);
        root.addView(boxGrid, Views.matchWrapTop(context, 20));

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
        List<Metric> visible = new ArrayList<>();
        for (Metric metric : metrics.values()) {
            if (metric.visible) {
                visible.add(metric);
            }
        }
        for (int i = 0; i < visible.size(); i += 2) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(buildBox(visible.get(i)), rowCellParams(context));
            if (i + 1 < visible.size()) {
                row.addView(buildBox(visible.get(i + 1)), rowCellParams(context));
            } else {
                row.addView(new View(context), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            }
            boxGrid.addView(row, Views.matchWrapTop(context, i == 0 ? 0 : 10));
        }
        if (visible.isEmpty()) {
            boxGrid.addView(Views.label(context, "Nothing selected above.", 13, false), Views.matchWrap());
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
            metrics.get("supported").value = data.optString("supported_pids", "--");
            metrics.get("coolant").value = String.valueOf(data.optInt("coolant_c", 0));
            metrics.get("rpm").value = String.valueOf(data.optInt("rpm", 0));
            metrics.get("speed").value = String.valueOf(data.optInt("speed_kmh", 0));
            metrics.get("throttle").value = String.valueOf(data.optInt("throttle_pct", 0));
            noteText.setText("Live CAN data from " + boardLink.getBoardIp());
            renderBoxes();
        } catch (JSONException exception) {
            noteText.setText("Invalid OBD data received from the P4.");
        }
    }
}
