package com.sarusiy.cartheftguard.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small programmatic-view builders shared by all four tab fragments. */
public final class Views {
    private Views() {}

    public static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static TextView label(Context context, String text, int size, boolean bold) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(0xff1f2933);
        view.setTextSize(size);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    public static EditText input(Context context, String hint, int type) {
        EditText input = new EditText(context);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setInputType(type);
        return input;
    }

    public static Button primaryButton(Context context, String text) {
        Button button = new Button(context);
        button.setText(text);
        button.setTextColor(0xffffffff);
        button.setAllCaps(false);
        button.setBackgroundColor(0xff0b6e69);
        return button;
    }

    public static Button secondaryButton(Context context, String text) {
        Button button = new Button(context);
        button.setText(text);
        button.setTextColor(0xff0b6e69);
        button.setAllCaps(false);
        button.setBackgroundColor(0xffffffff);
        return button;
    }

    public static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams matchWrapTop(Context context, int top) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(context, top);
        return params;
    }

    public static LinearLayout.LayoutParams matchHeightTop(Context context, int height, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, height));
        params.topMargin = dp(context, top);
        return params;
    }
}
