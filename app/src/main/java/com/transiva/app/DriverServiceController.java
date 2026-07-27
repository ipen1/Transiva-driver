package com.transiva.app;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public final class DriverServiceController {

    private static final String TAG = "DriverServiceController";

    private DriverServiceController() {}

    public static void start(Context context) {
        try {
            Intent intent = new Intent(context, LocationService.class);
            intent.setAction(LocationService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Gagal memulai tracking", e);
        }
    }

    public static void stop(Context context) {
        try {
            Intent intent = new Intent(context, LocationService.class);
            intent.setAction(LocationService.ACTION_STOP);
            context.startService(intent);
        } catch (Exception e) {
            Log.e(TAG, "Gagal menghentikan tracking", e);
            context.stopService(new Intent(context, LocationService.class));
        }
    }
}
