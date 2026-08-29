package com.transiva.app;

import android.content.Context;

/**
 * Compatibility shim untuk source lama. Build Google Play tidak melakukan self-update APK.
 * Gunakan AppVersionInfo untuk membaca versi aplikasi.
 */
@Deprecated
public final class AppUpdateClient {
    private AppUpdateClient() {}
    public static int installedVersionCode(Context context) { return AppVersionInfo.installedVersionCode(context); }
    public static String installedVersionName(Context context) { return AppVersionInfo.installedVersionName(context); }
}
