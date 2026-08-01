package com.transiva.app;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * NativeSessionGuard.java
 *
 * Helper kecil supaya semua komponen native memakai aturan session yang sama.
 * Pakai ini sebelum start LocationService, BackgroundSyncService, dan service foreground lain.
 */
public final class NativeSessionGuard {

    private static final String TAG = "TRANSIVA_GUARD";

    private NativeSessionGuard() {}

    public static boolean isLoggedIn(Context context) {
        try {
            return context != null && new SessionManager(context).isLoggedIn();
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean canRunNativeServices(Context context) {
        try {
            return context != null && new SessionManager(context).canRunNativeServices();
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean canRunDriverLocation(Context context) {
        try {
            return context != null && new SessionManager(context).canRunDriverLocation();
        } catch (Exception e) {
            return false;
        }
    }

    public static void clearAndStop(Context context, String reason) {
        if (context == null) return;

        try {
            SessionManager sessionManager = new SessionManager(context);
            sessionManager.markLoggedOut(reason == null ? "logout" : reason);
        } catch (Exception ignored) {}

        stopAllNativeServices(context);
    }

    public static void stopAllNativeServices(Context context) {
        DriverServiceController.stopAll(context);
    }

    public static boolean startAllowedServices(Context context) {
        if (context == null) return false;
        if (!canRunNativeServices(context)) {
            DriverServiceController.stopAll(context);
            return false;
        }
        try {
            SessionManager sessionManager = new SessionManager(context);
            sessionManager.touchSession();
            String role = sessionManager.getRole();
            if ("driver".equals(role)) {
                if (isDriverOnline(context) && hasLocationPermission(context)) {
                    DriverServiceController.start(context);
                    return true;
                }
                DriverServiceController.stopAll(context);
                return false;
            }
            // Merchant/admin tidak menjalankan service lokasi driver.
            DriverServiceController.stopAll(context);
        } catch (Exception e) {
            Log.e(TAG, "Start allowed services gagal", e);
        }
        return false;
    }


    private static boolean isDriverOnline(Context context) {
        if (context == null) return false;

        try {
            SharedPreferences prefs = context.getSharedPreferences(
                    "transiva",
                    Context.MODE_PRIVATE
            );

            return prefs.getBoolean("driver_online", false)
                    || "1".equals(prefs.getString("driver_online_text", "0"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean hasLocationPermission(Context context) {
        if (context == null) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;

        try {
            return context.checkSelfPermission(
                    Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
                    || context.checkSelfPermission(
                    Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED;
        } catch (Exception ignored) {
            return false;
        }
    }

}
