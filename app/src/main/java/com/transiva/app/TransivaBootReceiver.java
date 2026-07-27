package com.transiva.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * TransivaBootReceiver.java
 *
 * Versi clean stabil:
 * - Tidak menyalakan service kalau user belum login.
 * - Tidak hanya mengandalkan flag driver_online / merchant_online lama.
 * - Aman saat HP boot, aplikasi di-update, quick boot, dan locked boot.
 * - Mencegah status sinkronisasi muncul sebelum session valid.
 * - Start BackgroundSyncService hanya untuk driver login.
 * - Start TransivaDriverForegroundService hanya jika user driver/merchant sedang online.
 */
public class TransivaBootReceiver extends BroadcastReceiver {

    private static final String TAG = "TRANSIVA_BOOT";

    private static final String PREF_NAME = "transiva";
    private static final String KEY_DRIVER_ONLINE = "driver_online";
    private static final String KEY_MERCHANT_ONLINE = "merchant_online";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }

        String action = intent.getAction();

        if (!isAllowedBootAction(action)) {
            return;
        }

        try {
            SessionManager sessionManager = new SessionManager(context);

            if (!isValidLogin(sessionManager)) {
                clearOnlineFlags(context);
                stopAllServices(context);
                Log.d(TAG, "Session kosong / logout. Service tidak dijalankan.");
                return;
            }

            String role = safe(sessionManager.getRole()).toLowerCase();

            boolean driverOnline = context
                    .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY_DRIVER_ONLINE, false);

            boolean merchantOnline = context
                    .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY_MERCHANT_ONLINE, false);

            boolean isDriver = role.contains("driver");
            boolean isMerchant = role.contains("merchant");

            if (isDriver) {
                if (driverOnline) {
                    startDriverForegroundServiceSafe(context);
                    startBackgroundSyncServiceSafe(context);
                    Log.d(TAG, "Driver login dan online. Service dijalankan.");
                } else {
                    stopAllServices(context);
                    Log.d(TAG, "Driver login tapi offline. Service tidak dijalankan.");
                }
                return;
            }

            if (isMerchant) {
                if (merchantOnline) {
                    startDriverForegroundServiceSafe(context);
                    Log.d(TAG, "Merchant login dan online. Foreground service dijalankan.");
                } else {
                    stopAllServices(context);
                    Log.d(TAG, "Merchant login tapi offline. Service tidak dijalankan.");
                }
                return;
            }

            stopAllServices(context);
            Log.d(TAG, "Role bukan driver/merchant. Service tidak dijalankan.");

        } catch (Exception e) {
            Log.e(TAG, "Receiver error: " + safe(e.getMessage()));
        }
    }

    private boolean isAllowedBootAction(String action) {
        return Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action);
    }

    private boolean isValidLogin(SessionManager sessionManager) {
        try {
            if (sessionManager == null) return false;
            if (!sessionManager.isLoggedIn()) return false;

            String username = safe(sessionManager.getUsername()).trim();
            String role = safe(sessionManager.getRole()).trim();

            return !username.isEmpty() && !role.isEmpty();

        } catch (Exception e) {
            return false;
        }
    }

    private void startDriverForegroundServiceSafe(Context context) {
        try {
            Intent serviceIntent = new Intent(context, TransivaDriverForegroundService.class);
            serviceIntent.setAction(TransivaDriverForegroundService.ACTION_START);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Gagal start foreground service: " + safe(e.getMessage()));
        }
    }

    private void startBackgroundSyncServiceSafe(Context context) {
        try {
            BackgroundSyncService.start(context);
        } catch (Exception e) {
            Log.e(TAG, "Gagal start background sync: " + safe(e.getMessage()));
        }
    }

    private void stopAllServices(Context context) {
        try {
            BackgroundSyncService.stop(context);
        } catch (Exception ignored) {}

        try {
            Intent serviceIntent = new Intent(context, TransivaDriverForegroundService.class);
            serviceIntent.setAction(TransivaDriverForegroundService.ACTION_STOP);
            context.startService(serviceIntent);
        } catch (Exception ignored) {}
    }

    private void clearOnlineFlags(Context context) {
        try {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_DRIVER_ONLINE, false)
                    .putBoolean(KEY_MERCHANT_ONLINE, false)
                    .apply();
        } catch (Exception ignored) {}
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
