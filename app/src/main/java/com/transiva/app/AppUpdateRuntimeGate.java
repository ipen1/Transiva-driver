package com.transiva.app;

import android.app.Activity;

/** Compatibility shim. Update binary aplikasi dikelola Google Play. */
@Deprecated
public final class AppUpdateRuntimeGate {
    private AppUpdateRuntimeGate() {}
    public static void onActivityResumed(Activity activity) { /* no-op */ }
    public static void clearLaunchingFlag() { /* no-op */ }
}
