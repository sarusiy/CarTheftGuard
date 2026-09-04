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
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.sarusiy.cartheftguard.BoardLink;

import org.json.JSONException;
import org.json.JSONObject;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Live map view for the latest P4 GPS fix, with a moving marker and trail. */
public class TrackFragment extends Fragment implements BoardLink.Listener {
    private static final int POLL_INTERVAL_MS = 2000;
    private static final int MAX_TRAIL_POINTS = 300;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<GeoPoint> trailPoints = new ArrayList<>();
    private BoardLink boardLink;
    private MapView mapView;
    private Marker carMarker;
    private Polyline trailLine;
    private TextView statusText;
    private Button navigateButton;
    private Button clearTrailButton;
    private CheckBox followCheckBox;
    private boolean latestGpsFixValid;
    private double latestGpsLat;
    private double latestGpsLon;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (isAdded()) {
                boardLink.fetchGpsData();
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
        Context context = requireContext();
        Configuration.getInstance().setUserAgentValue(context.getPackageName());

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = Views.dp(context, 12);
        root.setPadding(padding, padding, padding, padding);
        root.setBackgroundColor(0xfff5f2ea);

        root.addView(Views.label(context, "Track", 24, true), Views.matchWrap());

        statusText = Views.label(context, "Waiting for GPS fix", 13, false);
        statusText.setTextColor(0xff52616b);
        root.addView(statusText, Views.matchWrapTop(context, 8));

        mapView = new MapView(context);
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setUseDataConnection(true);
        mapView.setMultiTouchControls(true);
        mapView.getController().setZoom(15.0);
        mapView.getController().setCenter(new GeoPoint(32.0853, 34.7818));

        trailLine = new Polyline(mapView);
        trailLine.getOutlinePaint().setColor(0xff0b6e69);
        trailLine.getOutlinePaint().setStrokeWidth(Views.dp(context, 4));
        mapView.getOverlays().add(trailLine);

        carMarker = new Marker(mapView);
        carMarker.setTitle("CarTheftGuard");
        carMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        carMarker.setVisible(false);
        mapView.getOverlays().add(carMarker);

        LinearLayout.LayoutParams mapParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        mapParams.topMargin = Views.dp(context, 12);
        root.addView(mapView, mapParams);

        LinearLayout actionRow = new LinearLayout(context);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);

        followCheckBox = new CheckBox(context);
        followCheckBox.setText("Follow");
        followCheckBox.setTextColor(0xff1f2933);
        followCheckBox.setChecked(true);
        actionRow.addView(followCheckBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        navigateButton = Views.primaryButton(context, "Navigate");
        navigateButton.setEnabled(false);
        navigateButton.setOnClickListener(view -> openNavigation());
        actionRow.addView(navigateButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        clearTrailButton = Views.secondaryButton(context, "Clear trail");
        clearTrailButton.setOnClickListener(view -> clearTrail());
        actionRow.addView(clearTrailButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        root.addView(actionRow, Views.matchWrapTop(context, 12));
        return root;
    }

    @Override
    public void onStart() {
        super.onStart();
        boardLink.addListener(this);
        handler.post(pollRunnable);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mapView != null) {
            mapView.onResume();
        }
    }

    @Override
    public void onPause() {
        if (mapView != null) {
            mapView.onPause();
        }
        super.onPause();
    }

    @Override
    public void onStop() {
        handler.removeCallbacks(pollRunnable);
        boardLink.removeListener(this);
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        if (mapView != null) {
            mapView.onDetach();
        }
        mapView = null;
        carMarker = null;
        trailLine = null;
        super.onDestroyView();
    }

    @Override
    public void onGpsData(String json) {
        try {
            JSONObject data = new JSONObject(json);
            latestGpsFixValid = data.optBoolean("fix_valid", false);
            if (!latestGpsFixValid) {
                statusText.setText("Waiting for GPS fix from " + boardLink.getBoardIp());
                navigateButton.setEnabled(false);
                if (carMarker != null) {
                    carMarker.setVisible(false);
                    mapView.invalidate();
                }
                return;
            }

            latestGpsLat = data.optDouble("lat", 0);
            latestGpsLon = data.optDouble("lon", 0);
            double speedKmh = data.optDouble("speed_kmh", 0);
            double headingDeg = data.optDouble("heading_deg", 0);
            GeoPoint point = new GeoPoint(latestGpsLat, latestGpsLon);
            updateMap(point, speedKmh, headingDeg, data.optInt("satellites", 0));
        } catch (JSONException exception) {
            statusText.setText("Invalid GPS data received from the P4.");
        }
    }

    private void updateMap(GeoPoint point, double speedKmh, double headingDeg, int satellites) {
        if (mapView == null || carMarker == null || trailLine == null) {
            return;
        }

        carMarker.setPosition(point);
        carMarker.setRotation((float)headingDeg);
        carMarker.setSnippet(String.format(Locale.US, "%.1f km/h, %.1f deg, %d satellites", speedKmh, headingDeg, satellites));
        carMarker.setVisible(true);
        navigateButton.setEnabled(true);

        trailPoints.add(point);
        while (trailPoints.size() > MAX_TRAIL_POINTS) {
            trailPoints.remove(0);
        }
        trailLine.setPoints(new ArrayList<>(trailPoints));

        if (followCheckBox == null || followCheckBox.isChecked()) {
            mapView.getController().animateTo(point);
        }
        statusText.setText(String.format(Locale.US,
                "Car: %.6f, %.6f | %.1f km/h | heading %.1f deg | sats %d",
                latestGpsLat, latestGpsLon, speedKmh, headingDeg, satellites));
        mapView.invalidate();
    }

    private void clearTrail() {
        trailPoints.clear();
        if (trailLine != null) {
            trailLine.setPoints(new ArrayList<>());
        }
        if (mapView != null) {
            mapView.invalidate();
        }
    }

    private void openNavigation() {
        if (!latestGpsFixValid) {
            statusText.setText("Waiting for a valid GPS fix from the P4.");
            return;
        }

        String coordinate = String.format(Locale.US, "%.6f,%.6f", latestGpsLat, latestGpsLon);
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + coordinate + "&mode=d"));
        intent.setPackage("com.google.android.apps.maps");
        if (intent.resolveActivity(requireContext().getPackageManager()) == null) {
            intent.setPackage(null);
        }
        try {
            startActivity(intent);
        } catch (Exception exception) {
            statusText.setText("No maps app is available on this phone.");
        }
    }
}
