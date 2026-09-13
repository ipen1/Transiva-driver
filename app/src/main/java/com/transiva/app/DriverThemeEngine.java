package com.transiva.app;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.TextView;

/** Stability 4.2 semantic theme applicator for programmatic views. */
public final class DriverThemeEngine {
    private DriverThemeEngine() { }

    public static void applyInput(EditText input) {
        if (input == null) return;
        Context c = input.getContext();
        input.setTextColor(DriverThemeTokens.textPrimary(c));
        input.setHintTextColor(DriverThemeTokens.hint(c));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(DriverThemeTokens.surface(c));
        bg.setStroke(dp(c, 1), DriverThemeTokens.border(c));
        bg.setCornerRadius(dp(c, 14));
        input.setBackground(bg);
    }

    public static void applyCard(View view, int radiusDp) {
        if (view == null) return;
        Context c = view.getContext();
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(DriverThemeTokens.surface(c));
        bg.setStroke(dp(c, 1), DriverThemeTokens.border(c));
        bg.setCornerRadius(dp(c, radiusDp));
        view.setBackground(bg);
    }

    public static void applyText(TextView view, boolean primary) {
        if (view == null) return;
        view.setTextColor(primary ? DriverThemeTokens.textPrimary(view.getContext())
                : DriverThemeTokens.textSecondary(view.getContext()));
    }

    public static void applyDialog(AlertDialog dialog) {
        if (dialog == null) return;
        Window w = dialog.getWindow();
        if (w != null && w.getDecorView() != null) {
            w.getDecorView().setBackgroundColor(DriverThemeTokens.surface(dialog.getContext()));
        }
    }

    public static void applySystemBars(android.app.Activity activity) {
        if (activity == null) return;
        activity.getWindow().setStatusBarColor(DriverThemeTokens.background(activity));
        activity.getWindow().setNavigationBarColor(DriverThemeTokens.background(activity));
    }


    /** Theme Engine 2.1 safety pass for every programmatic Activity.
     * It intentionally normalizes interactive text fields only; brand/status labels keep their semantic status colors. */
    public static void applyAppWide(View root) {
        if (root == null) return;
        if (root instanceof EditText) applyInput((EditText) root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) applyAppWide(group.getChildAt(i));
        }
    }

    public static void applyAppWide(android.app.Activity activity) {
        if (activity == null || activity.getWindow() == null) return;
        View decor = activity.getWindow().getDecorView();
        if (decor != null) applyAppWide(decor);
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
