package com.transiva.app;

import android.content.Context;
import android.graphics.Color;
import java.util.Locale;

/**
 * Semantic theme tokens for the programmatic Driver UI.
 *
 * IMPORTANT: the Transiva theme preference is the single source of truth.
 * Do not read Configuration.UI_MODE_NIGHT here because a device may use dark
 * system UI while the Driver app is explicitly set to light mode (or vice versa).
 */
public final class DriverThemeTokens {
    private DriverThemeTokens() {}

    public static boolean isDark(Context c) {
        return c != null && DriverAppSettings.isDarkMode(c);
    }

    public static int background(Context c) { return hex(c, "#F7FBFF", "#0B1220"); }
    public static int surface(Context c) { return hex(c, "#FFFFFF", "#121E2E"); }
    public static int surfaceAlt(Context c) { return hex(c, "#F2F7FF", "#17263A"); }
    public static int textPrimary(Context c) { return hex(c, "#0B3675", "#F4F8FF"); }
    public static int textSecondary(Context c) { return hex(c, "#68758A", "#B8C5D6"); }
    public static int textMuted(Context c) { return hex(c, "#64748B", "#9FB0C5"); }
    public static int hint(Context c) { return hex(c, "#8A96A8", "#8191A6"); }
    public static int border(Context c) { return hex(c, "#D9E2EE", "#29405C"); }
    public static int accent(Context c) { return hex(c, "#1677FF", "#4A9BFF"); }
    public static int onAccent(Context c) { return Color.WHITE; }

    public static int inputBackground(Context c) { return surface(c); }
    public static int dialogBackground(Context c) { return surface(c); }
    public static int navigationBackground(Context c) { return surface(c); }
    public static int divider(Context c) { return hex(c, "#E3EAF4", "#24374E"); }
    public static int disabledText(Context c) { return hint(c); }
    public static int iconPrimary(Context c) { return textPrimary(c); }
    public static int positive(Context c) { return Color.parseColor("#16A34A"); }
    public static int warning(Context c) { return Color.parseColor("#F59E0B"); }
    public static int danger(Context c) { return Color.parseColor("#DC2626"); }

    private static int hex(Context c, String light, String dark) {
        return Color.parseColor(isDark(c) ? dark : light);
    }

    /**
     * Maps legacy programmatic colors to semantic colors in BOTH themes.
     * Brand/status colors are intentionally preserved, while surfaces and text
     * always follow the Transiva preference rather than the OEM/system theme.
     */
    public static int color(Context c, String legacyHex) {
        String h = legacyHex == null ? "" : legacyHex.trim().toUpperCase(Locale.US);
        switch (h) {
            case "#FFFFFF": case "#FFFFFFFF": case "#FCFFFFFF": case "#FAFFFFFF":
                return surface(c);

            case "#F7FAFF": case "#F7FBFF": case "#F3F8FF": case "#EAF4FF":
            case "#F8FAFC": case "#F1F5F9": case "#EEF6FF": case "#F5F9FF":
            case "#F5F8FD": case "#F6F9FE": case "#F7F9FC":
                return background(c);

            case "#0F172A": case "#111827": case "#1E293B": case "#123D7C":
            case "#0B3675": case "#0B3A78": case "#082F63": case "#0A356C":
            case "#0B2F66":
                return textPrimary(c);

            case "#64748B": case "#68758A": case "#475569": case "#7A475569":
            case "#56657A": case "#718096":
                return textSecondary(c);

            case "#94A3B8": case "#8A96A8":
                return hint(c);

            case "#D7E6F8": case "#D8E4F2": case "#D9E2EE": case "#E2ECF8":
            case "#BBD8F5": case "#EEF2F7": case "#E3EAF4": case "#DCE8F7":
                return border(c);

            case "#0B7CFF": case "#086BFF": case "#0878F9": case "#087CFF":
            case "#1683FF": case "#1677FF":
                return accent(c);

            default:
                try { return Color.parseColor(legacyHex); }
                catch (Throwable ignored) { return textPrimary(c); }
        }
    }
}
