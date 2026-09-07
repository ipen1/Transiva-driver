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

    public static boolean driverOnline(Context c) {
        try {
            SessionManager session = new SessionManager(c.getApplicationContext());
            return session.isLoggedIn()
                    && "driver".equals(session.normalizeRole(session.getRole()))
                    && ("1".equals(session.get("driver_server_online"))
                        || "1".equals(session.get("driver_is_online")));
        } catch (Throwable ignored) {
            return false;
        }
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
        PremiumDialogs.builder(a)
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
        if (!canOverlay(c) || !enabled(c) || !driverOnline(c)) return;
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
        stopService(c, true);
    }

    /** Hide karena status driver OFFLINE, tanpa mengubah pilihan Bubble di Settings. */
    public static void stopForOffline(Context c) {
        stopService(c, false);
    }

    private static void stopService(Context c, boolean userDisabled) {
        if (c == null) return;
        try {
            Intent i = new Intent(c, DriverBubbleOverlayService.class)
                    .setAction(userDisabled
                            ? DriverBubbleOverlayService.ACTION_STOP
                            : DriverBubbleOverlayService.ACTION_OFFLINE);
            c.startService(i);
        } catch (Throwable ignored) {
            try { c.stopService(new Intent(c, DriverBubbleOverlayService.class)); }
            catch (Throwable ignoredAgain) {}
        }
    }
}
