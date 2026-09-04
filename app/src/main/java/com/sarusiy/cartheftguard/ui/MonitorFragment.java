package com.sarusiy.cartheftguard.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
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
import java.util.Locale;
import java.util.Map;

/** "Monitor" tab: latest OBD-II values read by the P4 over CAN, with visibility filters. */
public class MonitorFragment extends Fragment implements BoardLink.Listener {
    private static final int POLL_INTERVAL_MS = 1000;

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
    private Button openMapButton;
    private Button navigateButton;
    private boolean latestGpsFixValid;
    private double latestGpsLat;
    private double latestGpsLon;
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (isAdded()) {
                boardLink.fetchObdData();
                boardLink.fetchGpsData();
                handler.postDelayed(this, POLL_INTERVAL_MS);
            }
        }
    };

    public MonitorFragment() {
        addMetric("supported", "Supported PIDs", "bitmask");
        addMetric("coolant", "Coolant Temp", "\u00b0C");
        addMetric("rpm", "Engine RPM", "rpm");
        addMetric("speed", "Vehicle Speed", "km/h");
        addMetric("throttle", "Throttle Position", "%");
        addMetric("gps_fix", "GPS Fix", "state");
        addMetric("gps_lat", "GPS Latitude", "degrees");
        addMetric("gps_lon", "GPS Longitude", "degrees");
        addMetric("gps_speed", "GPS Speed", "km/h");
        addMetric("gps_heading", "GPS Heading", "degrees");
        addMetric("gps_sats", "GPS Satellites", "count");
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

        LinearLayout gpsActions = new LinearLayout(context);
        gpsActions.setOrientation(LinearLayout.HORIZONTAL);
        openMapButton = new Button(context);
        openMapButton.setText("Open map");
        openMapButton.setEnabled(false);
        openMapButton.setOnClickListener(view -> openCurrentGpsInMap(false));
        gpsActions.addView(openMapButton, rowCellParams(context));
        navigateButton = new Button(context);
        navigateButton.setText("Navigate");
        navigateButton.setEnabled(false);
        navigateButton.setOnClickListener(view -> openCurrentGpsInMap(true));
        gpsActions.addView(navigateButton, rowCellParams(context));
        root.addView(gpsActions, Views.matchWrapTop(context, 16));

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

    private void openCurrentGpsInMap(boolean navigation) {
        if (!latestGpsFixValid) {
            noteText.setText("Waiting for a valid GPS fix from the P4.");
            return;
        }

        String coordinate = String.format(Locale.US, "%.6f,%.6f", latestGpsLat, latestGpsLon);
        Uri uri = navigation
                ? Uri.parse("google.navigation:q=" + coordinate + "&mode=d")
                : Uri.parse("geo:" + coordinate + "?q=" + coordinate + "(CarTheftGuard)");
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        intent.setPackage("com.google.android.apps.maps");
        if (intent.resolveActivity(requireContext().getPackageManager()) == null) {
            intent.setPackage(null);
        }
        try {
            startActivity(intent);
        } catch (Exception exception) {
            noteText.setText("No maps app is available on this phone.");
        }
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

    @Override
    public void onGpsData(String json) {
        try {
            JSONObject data = new JSONObject(json);
            boolean fixValid = data.optBoolean("fix_valid", false);
            latestGpsFixValid = fixValid;
            latestGpsLat = data.optDouble("lat", 0);
            latestGpsLon = data.optDouble("lon", 0);
            metrics.get("gps_fix").value = fixValid ? "Valid" : "Waiting";
            metrics.get("gps_lat").value = fixValid ? String.format(Locale.US, "%.6f", latestGpsLat) : "--";
            metrics.get("gps_lon").value = fixValid ? String.format(Locale.US, "%.6f", latestGpsLon) : "--";
            metrics.get("gps_speed").value = fixValid ? String.format(Locale.US, "%.1f", data.optDouble("speed_kmh", 0)) : "--";
            metrics.get("gps_heading").value = fixValid ? String.format(Locale.US, "%.1f", data.optDouble("heading_deg", 0)) : "--";
            metrics.get("gps_sats").value = String.valueOf(data.optInt("satellites", 0));
            if (openMapButton != null && navigateButton != null) {
                openMapButton.setEnabled(fixValid);
                navigateButton.setEnabled(fixValid);
            }
            noteText.setText("Live CAN/GPS data from " + boardLink.getBoardIp());
            renderBoxes();
        } catch (JSONException exception) {
            noteText.setText("Invalid GPS data received from the P4.");
        }
    }
}
