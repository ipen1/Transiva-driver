package com.transiva.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent restart limiter to prevent foreground-service restart loops. */
public final class DriverRestartGuard {
    private static final String PREF = "driver_restart_guard";
    private static final String WINDOW_START = "window_start";
    private static final String COUNT = "count";
    private static final long WINDOW_MS = 15L * 60L * 1000L;
    private static final int MAX_RESTARTS = 3;

    private DriverRestartGuard() {}

    public static synchronized boolean allow(Context context) {
        if (context == null) return false;
        SharedPreferences p = context.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long start = p.getLong(WINDOW_START, 0L);
        int count = p.getInt(COUNT, 0);
        if (start <= 0L || now - start >= WINDOW_MS) {
            p.edit().putLong(WINDOW_START, now).putInt(COUNT, 1).apply();
            return true;
        }
        if (count >= MAX_RESTARTS) return false;
        p.edit().putInt(COUNT, count + 1).apply();
        return true;
    }

    public static synchronized void reset(Context context) {
        if (context == null) return;
        context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().clear().apply();
    }
}
