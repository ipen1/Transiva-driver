package com.transiva.app;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.google.firebase.crashlytics.FirebaseCrashlytics;

import java.util.Map;

/** Lightweight navigation telemetry for OEM/device-specific failures. */
public final class NavigationDiagnostics {
    private static final String TAG = "TransivaNav";
    private NavigationDiagnostics() {}

    public static void event(Context context, String event, Map<String, String> extras) {
        try {
            FirebaseCrashlytics c = FirebaseCrashlytics.getInstance();
            c.setCustomKey("nav_last_event", safe(event));
            c.setCustomKey("nav_device", Build.MANUFACTURER + " " + Build.MODEL);
            c.setCustomKey("nav_android", Build.VERSION.SDK_INT);
            if (extras != null) for (Map.Entry<String,String> e : extras.entrySet()) {
                c.setCustomKey("nav_" + e.getKey(), safe(e.getValue()));
            }
            c.log("NAV " + safe(event));
        } catch (Throwable ignored) {}
        Log.i(TAG, safe(event));
    }

    public static void error(Context context, String stage, Throwable error) {
        Log.e(TAG, safe(stage), error);
        try {
            FirebaseCrashlytics c = FirebaseCrashlytics.getInstance();
            c.setCustomKey("nav_failure_stage", safe(stage));
            c.setCustomKey("nav_device", Build.MANUFACTURER + " " + Build.MODEL);
            c.setCustomKey("nav_android", Build.VERSION.SDK_INT);
            c.log("NAV_ERROR " + safe(stage));
            if (error != null) c.recordException(error);
        } catch (Throwable ignored) {}
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
