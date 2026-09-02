package com.sarusiy.cartheftguard.ui;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
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

/** "About" tab: static app/board identification info. */
public class AboutFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return buildView();
    }

    private View buildView() {
        Context context = requireContext();
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = Views.dp(context, 20);
        root.setPadding(padding, padding, padding, padding);
        root.setBackgroundColor(0xfff5f2ea);

        root.addView(Views.label(context, "About", 24, true), Views.matchWrap());
        root.addView(Views.label(context, "CarTheftGuard", 20, true), Views.matchWrapTop(context, 20));
        root.addView(infoRow(context, "Version", versionLabel(context)), Views.matchWrapTop(context, 12));
        root.addView(infoRow(context, "Package", context.getPackageName()), Views.matchWrapTop(context, 8));
        root.addView(infoRow(context, "Board", BoardLink.TARGET_NAME + " (JC-ESP32P4-M3)"), Views.matchWrapTop(context, 8));
        root.addView(infoRow(context, "BLE service", BoardLink.SERVICE_UUID.toString()), Views.matchWrapTop(context, 8));

        root.addView(Views.label(context, "About this app", 16, true), Views.matchWrapTop(context, 28));
        root.addView(Views.label(context, "Connects to a JC-ESP32P4-M3 board over BLE, hands it Wi-Fi credentials, "
                        + "and lets you monitor and control it from your phone.", 14, false),
                Views.matchWrapTop(context, 6));

        root.addView(link(context, "Firmware: github.com/sarusiy/JC-ESP32P4-M3",
                "https://github.com/sarusiy/JC-ESP32P4-M3"), Views.matchWrapTop(context, 20));
        root.addView(link(context, "App: github.com/sarusiy/CarTheftGuard",
                "https://github.com/sarusiy/CarTheftGuard"), Views.matchWrapTop(context, 6));

        ScrollView scroll = new ScrollView(context);
        scroll.addView(root);
        return scroll;
    }

    private String versionLabel(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            long versionCode = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P
                    ? info.getLongVersionCode() : info.versionCode;
            return info.versionName + " (" + versionCode + ")";
        } catch (PackageManager.NameNotFoundException exception) {
            return "unknown";
        }
    }

    private View infoRow(Context context, String label, String value) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView labelView = Views.label(context, label, 14, true);
        labelView.setTextColor(0xff52616b);
        row.addView(labelView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(Views.label(context, value, 14, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2));
        return row;
    }

    private View link(Context context, String text, String url) {
        TextView view = Views.label(context, text, 14, false);
        view.setTextColor(0xff0b6e69);
        view.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))));
        return view;
    }
}
