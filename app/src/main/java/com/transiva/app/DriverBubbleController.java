package com.transiva.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/** Persists the Settings switch separately from a temporary overlay dismissal. */
public final class DriverBubbleController {
    private static final String PREF = "transiva_bubble";
    private static final String ENABLED = "enabled";
    private static volatile boolean dismissed;

    private DriverBubbleController() {}

    public static boolean canOverlay(Context context) {
        return Settings.canDrawOverlays(context);
    }

    public static boolean enabled(Context context) {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(ENABLED, true);
    }

    public static boolean driverOnline(Context context) {
        try {
            SessionManager session = new SessionManager(context.getApplicationContext());
            if (!session.isLoggedIn() || !"driver".equals(session.normalizeRole(session.getRole()))) return false;
            String serverOnline = session.get("driver_server_online");
            return "1".equals(serverOnline.isEmpty() ? session.get("driver_is_online") : serverOnline);
        } catch (ClassCastException error) {
            NavigationDiagnostics.error(context, "BUBBLE_SESSION_INVALID", error);
            return false;
        }
    }

    static void onAppReopened() {
        dismissed = false;
    }

    public static void onActivityResumed(Activity activity) {
        if (activity.isFinishing() || activity instanceof SplashActivity
                || activity instanceof LoginActivity || activity instanceof PinActivity) return;
        if (eligible(activity)) start(activity);
        else stopService(activity);
    }

    static boolean eligible(Context context) {
        return !dismissed && enabled(context) && canOverlay(context) && driverOnline(context);
    }

    public static void start(Context context) {
        if (!eligible(context)) return;
        try {
            context.startService(new Intent(context, DriverBubbleOverlayService.class));
        } catch (IllegalStateException | SecurityException error) {
            NavigationDiagnostics.error(context, "BUBBLE_START_FAILED", error);
        }
    }

    public static void enable(Context context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, true).apply();
        dismissed = false;
        start(context);
    }

    public static void disable(Context context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, false).apply();
        stopForOffline(context);
    }

    static void dismiss(Context context) {
        dismissed = true;
        stopService(context);
    }

    /** Stop on sign-out or offline without changing the Settings switch. */
    public static void stopForOffline(Context context) {
        DriverBubbleOverlayService.clearPending(context);
        stopService(context);
    }

    private static void stopService(Context context) {
        try {
            context.stopService(new Intent(context, DriverBubbleOverlayService.class));
        } catch (IllegalStateException | SecurityException error) {
            NavigationDiagnostics.error(context, "BUBBLE_STOP_FAILED", error);
        }
    }
}
