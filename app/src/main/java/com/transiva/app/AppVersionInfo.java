package com.transiva.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

/** Version helper untuk build Google Play. Tidak mengunduh atau memasang APK. */
public final class AppVersionInfo {
    private AppVersionInfo() {}

    public static int installedVersionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                long value = info.getLongVersionCode();
                return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
            }
            return info.versionCode;
        } catch (Exception ignored) {
            return 0;
        }
    }

    public static String installedVersionName(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName == null ? "-" : info.versionName;
        } catch (Exception ignored) {
            return "-";
        }
    }
}
