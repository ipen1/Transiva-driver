package com.transiva.app;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Single orchestration point for all long-running driver services. */
public final class DriverServiceController {
    private DriverServiceController() {}

    public static synchronized void start(Context context) {
        startAll(context, false);
    }

    public static synchronized void startAfterSystemEvent(Context context) {
        startAll(context, true);
    }

    private static void startAll(Context context, boolean systemRestart) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        SessionManager session = new SessionManager(app);
        if (!session.canRunDriverLocation()) {
            stopAll(app);
            return;
        }
        if (systemRestart && !DriverRestartGuard.allow(app)) {
            TransivaDriverCrashReporter.message("service_restart_limited");
            return;
        }
        try {
            Intent location = new Intent(app, LocationService.class)
                    .setAction(LocationService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(location);
            else app.startService(location);
        } catch (Throwable e) {
            TransivaDriverCrashReporter.nonFatal("location_service_start", e);
        }
        // LocationService adalah satu-satunya foreground/background loop utama.
        // BackgroundSyncService tidak dijalankan terus-menerus agar tidak ada
        // duplicate GPS upload + notifikasi foreground kedua.
    }

    public static synchronized void stop(Context context) {
        stopAll(context);
    }

    public static synchronized void stopAll(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        DriverRestartGuard.reset(app);
        try { app.stopService(new Intent(app, LocationService.class)); }
        catch (Throwable e) { TransivaDriverCrashReporter.nonFatal("location_service_stop", e); }
        // Legacy service dinonaktifkan di Manifest. stopService() tetap aman untuk
        // membersihkan instance lama setelah upgrade aplikasi tanpa memicunya kembali.
        try { app.stopService(new Intent(app, BackgroundSyncService.class)); }
        catch (Throwable e) { TransivaDriverCrashReporter.nonFatal("background_sync_stop", e); }
        try { app.stopService(new Intent(app, TransivaDriverForegroundService.class)); }
        catch (Throwable ignored) { }
    }
}
