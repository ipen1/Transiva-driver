package com.transiva.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

/** One-time permission UX + safe service lifecycle. */
public final class DriverBubbleController {
    private static final String PREF = "transiva_bubble";
    private DriverBubbleController() {}

    public static boolean canOverlay(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    public static boolean enabled(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("enabled", false);
    }

    public static void onActivityResumed(Activity a) {
        if (a == null || a.isFinishing() || a instanceof SplashActivity || a instanceof LoginActivity || a instanceof PinActivity) return;
        if (canOverlay(a) && enabled(a)) start(a);
    }

    public static void requestOnce(Activity a) {
        if (a == null || Build.VERSION.SDK_INT < 23 || canOverlay(a)) { onActivityResumed(a); return; }
        boolean asked = a.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("permission_asked_v1", false);
        if (asked) return;
        a.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("permission_asked_v1", true).apply();
        new AlertDialog.Builder(a)
                .setTitle("Aktifkan Bubble Transiva")
                .setMessage("Bubble menampilkan order baru, pesan customer, dan mention di atas aplikasi lain. Bubble bisa digeser ke tanda × untuk ditutup kapan saja.")
                .setNegativeButton("Nanti", null)
                .setPositiveButton("Aktifkan", (d, w) -> {
                    try {
                        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + a.getPackageName()));
                        a.startActivity(i);
                    } catch (Throwable ignored) {}
                }).show();
    }

    public static void start(Context c) {
        if (!canOverlay(c) || !enabled(c)) return;
        try {
            Intent i = new Intent(c, DriverBubbleOverlayService.class).setAction(DriverBubbleOverlayService.ACTION_START);
            c.startService(i);
        } catch (Throwable ignored) {}
    }

    public static void enable(Context c) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("enabled", true).apply();
        start(c);
    }

    public static void disable(Context c) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("enabled", false).apply();
        try {
            Intent i = new Intent(c, DriverBubbleOverlayService.class).setAction(DriverBubbleOverlayService.ACTION_STOP);
            c.startService(i);
        } catch (Throwable ignored) {}
    }
}
