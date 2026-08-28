package com.transiva.app;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

/** Central non-fatal diagnostics for critical driver flows. Never throws back to app flow. */
public final class TransivaDiagnostics {
    private static final String TAG = "TransivaDiag";
    private TransivaDiagnostics() {}
    public static void event(Context c, String area, String event) {
        String key = safe(area) + ":" + safe(event);
        Log.i(TAG, key);
        try {
            FirebaseCrashlytics f = FirebaseCrashlytics.getInstance();
            f.setCustomKey("last_area", safe(area));
            f.setCustomKey("last_event", safe(event));
            f.setCustomKey("device", Build.MANUFACTURER + " " + Build.MODEL);
            f.setCustomKey("android_sdk", Build.VERSION.SDK_INT);
            f.log(key);
        } catch (Throwable ignored) { Log.w(TAG, "Crashlytics unavailable"); }
    }
    public static void error(Context c, String area, String stage, Throwable t) {
        Log.e(TAG, safe(area) + ":" + safe(stage), t);
        try {
            FirebaseCrashlytics f = FirebaseCrashlytics.getInstance();
            f.setCustomKey("failure_area", safe(area));
            f.setCustomKey("failure_stage", safe(stage));
            f.setCustomKey("device", Build.MANUFACTURER + " " + Build.MODEL);
            f.setCustomKey("android_sdk", Build.VERSION.SDK_INT);
            f.log("ERROR " + safe(area) + ":" + safe(stage));
            if (t != null) f.recordException(t);
        } catch (Throwable ignored) { Log.w(TAG, "Crashlytics error reporting unavailable"); }
    }
    private static String safe(String s){ return s == null ? "" : s; }
}
