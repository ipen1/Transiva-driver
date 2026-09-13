package com.transiva.app;

import android.content.Context;
import android.content.res.Configuration;
import androidx.core.content.ContextCompat;
import android.graphics.Color;
import java.util.Locale;

/** Semantic theme tokens for programmatic Java UI. Never infer readable colors from fixed hex values. */
public final class DriverThemeTokens {
    private DriverThemeTokens() {}
    public static boolean isDark(Context c) {
        return c != null && (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }
    public static int background(Context c) { return ContextCompat.getColor(c, R.color.transiva_bg); }
    public static int surface(Context c) { return ContextCompat.getColor(c, R.color.transiva_card); }
    public static int surfaceAlt(Context c) { return ContextCompat.getColor(c, R.color.transiva_version_bg); }
    public static int textPrimary(Context c) { return ContextCompat.getColor(c, R.color.transiva_title); }
    public static int textSecondary(Context c) { return ContextCompat.getColor(c, R.color.transiva_subtitle); }
    public static int textMuted(Context c) { return ContextCompat.getColor(c, R.color.transiva_muted); }
    public static int hint(Context c) { return ContextCompat.getColor(c, R.color.transiva_hint); }
    public static int border(Context c) { return ContextCompat.getColor(c, R.color.transiva_border); }
    public static int accent(Context c) { return ContextCompat.getColor(c, R.color.transiva_blue); }
    public static int onAccent(Context c) { return ContextCompat.getColor(c, R.color.transiva_on_accent); }


    // Theme Engine 2.1 app-wide semantic roles. Status/brand colors remain intentional,
    // while readable surfaces/text always resolve against current uiMode resources.
    public static int inputBackground(Context c) { return surface(c); }
    public static int dialogBackground(Context c) { return surface(c); }
    public static int navigationBackground(Context c) { return surface(c); }
    public static int divider(Context c) { return border(c); }
    public static int disabledText(Context c) { return hint(c); }
    public static int iconPrimary(Context c) { return textPrimary(c); }
    public static int positive(Context c) { return color(c, "#16A34A"); }
    public static int warning(Context c) { return color(c, "#F59E0B"); }
    public static int danger(Context c) { return color(c, "#DC2626"); }

    /** Maps legacy programmatic hex colors onto semantic resources in dark mode. */
    public static int color(Context c, String legacyHex) {
        String h = legacyHex == null ? "" : legacyHex.trim().toUpperCase(Locale.US);
        switch (h) {
            case "#FFFFFF": case "#FFFFFFFF": case "#FCFFFFFF": case "#FAFFFFFF":
                return surface(c);
            case "#F7FAFF": case "#F7FBFF": case "#F3F8FF": case "#EAF4FF":
            case "#F8FAFC": case "#F1F5F9": case "#EEF6FF": case "#F5F9FF":
                return background(c);
            case "#0F172A": case "#111827": case "#1E293B": case "#123D7C": case "#0B3675": case "#0B3A78": case "#082F63": case "#0A356C":
                return textPrimary(c);
            case "#64748B": case "#68758A": case "#475569": case "#7A475569":
                return textSecondary(c);
            case "#94A3B8": case "#8A96A8": return hint(c);
            case "#D7E6F8": case "#D8E4F2": case "#D9E2EE": case "#E2ECF8": case "#BBD8F5":
                return border(c);
            case "#0B7CFF": case "#086BFF": case "#0878F9": case "#087CFF": case "#1683FF":
                return accent(c);
            default:
                try { return Color.parseColor(legacyHex); }
                catch (Throwable ignored) { return textPrimary(c); }
        }
    }
}
