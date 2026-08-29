package com.transiva.app;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import java.util.Locale;

/**
 * Central performance policy for Transiva Driver.
 *
 * AUTO detects the device once, while LOW/NORMAL/HIGH are explicit user overrides.
 * Every consumer (GPS, map/navigation FPS, image decoding and polling) reads this class,
 * so changing the account setting changes real runtime behaviour instead of only UI labels.
 */
public final class DevicePerformanceProfile {
    public enum Tier { HIGH, NORMAL, LOW, VERY_LOW }
    public enum UserMode { AUTO, LOW, NORMAL, HIGH }

    private static final String PREF = "driver_performance_profile";
    private static final String KEY_MODE = "selected_mode";

    public final Tier tier;
    public final UserMode userMode;
    public final UserMode recommendedMode;
    public final long navigationGpsMs;
    public final long navigationNetworkMs;
    public final float navigationMinDistanceM;
    public final long tripGpsMs;
    public final long tripNetworkMs;
    public final float tripMinDistanceM;
    public final float pollingMultiplier;
    public final int imageMaxSidePx;
    public final int imageWorkerCount;
    public final boolean reduceMapMotion;
    public final long visualFrameMs;
    public final int targetFps;

    private static volatile DevicePerformanceProfile cached;
    private static volatile String cachedMode = "";

    private DevicePerformanceProfile(Tier tier, UserMode userMode, UserMode recommendedMode,
                                     long navGps, long navNet, float navDistance,
                                     long tripGps, long tripNet, float tripDistance,
                                     float pollingMultiplier, int imageMaxSidePx,
                                     int imageWorkerCount, boolean reduceMapMotion,
                                     long visualFrameMs, int targetFps) {
        this.tier = tier;
        this.userMode = userMode;
        this.recommendedMode = recommendedMode;
        this.navigationGpsMs = navGps;
        this.navigationNetworkMs = navNet;
        this.navigationMinDistanceM = navDistance;
        this.tripGpsMs = tripGps;
        this.tripNetworkMs = tripNet;
        this.tripMinDistanceM = tripDistance;
        this.pollingMultiplier = pollingMultiplier;
        this.imageMaxSidePx = imageMaxSidePx;
        this.imageWorkerCount = imageWorkerCount;
        this.reduceMapMotion = reduceMapMotion;
        this.visualFrameMs = visualFrameMs;
        this.targetFps = targetFps;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static UserMode getSelectedMode(Context context) {
        if (context == null) return UserMode.AUTO;
        String raw = prefs(context).getString(KEY_MODE, UserMode.AUTO.name());
        try { return UserMode.valueOf(raw); } catch (Throwable ignored) { return UserMode.AUTO; }
    }

    public static void setSelectedMode(Context context, UserMode mode) {
        if (context == null) return;
        UserMode safe = mode == null ? UserMode.AUTO : mode;
        prefs(context).edit().putString(KEY_MODE, safe.name()).apply();
        synchronized (DevicePerformanceProfile.class) {
            cached = null;
            cachedMode = "";
        }
    }

    public static DevicePerformanceProfile get(Context context) {
        Context app = context == null ? null : context.getApplicationContext();
        UserMode selected = app == null ? UserMode.AUTO : getSelectedMode(app);
        String modeKey = selected.name();
        DevicePerformanceProfile c = cached;
        if (c != null && modeKey.equals(cachedMode)) return c;
        synchronized (DevicePerformanceProfile.class) {
            c = cached;
            if (c == null || !modeKey.equals(cachedMode)) {
                Tier detectedTier = detectTier(app);
                UserMode recommended = recommendedFor(detectedTier);
                cached = build(selected, detectedTier, recommended);
                cachedMode = modeKey;
            }
            return cached;
        }
    }

    public static UserMode getRecommendedMode(Context context) {
        return recommendedFor(detectTier(context == null ? null : context.getApplicationContext()));
    }

    public static boolean isAboveRecommended(Context context, UserMode requested) {
        if (requested == null || requested == UserMode.AUTO) return false;
        return rank(requested) > rank(getRecommendedMode(context));
    }

    public static long scalePolling(Context context, long baseMs) {
        long safe = Math.max(1000L, baseMs);
        return Math.max(1000L, Math.round(safe * get(context).pollingMultiplier));
    }

    public static String title(UserMode mode) {
        if (mode == UserMode.LOW) return "Low · Hemat baterai";
        if (mode == UserMode.HIGH) return "High · Performa maksimum";
        if (mode == UserMode.NORMAL) return "Normal · Seimbang";
        return "Auto · Direkomendasikan";
    }

    public static String description(UserMode mode) {
        if (mode == UserMode.LOW) return "Rendering lebih ringan, GPS lebih hemat, dan FPS dibatasi untuk mengurangi panas serta penggunaan baterai.";
        if (mode == UserMode.HIGH) return "Rendering paling halus, GPS lebih responsif, dan FPS lebih tinggi. Konsumsi baterai serta suhu perangkat dapat meningkat.";
        if (mode == UserMode.NORMAL) return "Keseimbangan kelancaran, akurasi GPS, suhu perangkat, dan penggunaan baterai.";
        return "Transiva menyesuaikan rendering, GPS, gambar, polling, dan FPS berdasarkan kemampuan perangkat.";
    }

    private static int rank(UserMode mode) {
        if (mode == UserMode.HIGH) return 3;
        if (mode == UserMode.NORMAL) return 2;
        if (mode == UserMode.LOW) return 1;
        return 0;
    }

    private static UserMode recommendedFor(Tier tier) {
        if (tier == Tier.HIGH) return UserMode.HIGH;
        if (tier == Tier.NORMAL) return UserMode.NORMAL;
        return UserMode.LOW;
    }

    private static DevicePerformanceProfile build(UserMode selected, Tier detected, UserMode recommended) {
        UserMode effective = selected == UserMode.AUTO ? recommended : selected;
        if (effective == UserMode.HIGH) return high(selected, recommended);
        if (effective == UserMode.NORMAL) return normal(selected, recommended);
        // Keep a slightly more conservative AUTO profile on very-low hardware.
        if (selected == UserMode.AUTO && detected == Tier.VERY_LOW) return veryLow(selected, recommended);
        return low(selected, recommended);
    }

    private static Tier detectTier(Context c) {
        long totalMb = 4096L;
        boolean lowRam = false;
        try {
            if (c != null) {
                ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                    am.getMemoryInfo(mi);
                    if (mi.totalMem > 0) totalMb = mi.totalMem / (1024L * 1024L);
                    if (Build.VERSION.SDK_INT >= 19) lowRam = am.isLowRamDevice();
                }
            }
        } catch (Throwable ignored) {}
        String model = (Build.MANUFACTURER + " " + Build.MODEL).toLowerCase(Locale.US);
        boolean budgetOem = model.contains("itel") || model.contains("tecno") || model.contains("infinix")
                || model.contains("redmi a") || model.contains("realme c");
        if (lowRam || totalMb <= 2300L || Build.VERSION.SDK_INT <= 25) return Tier.VERY_LOW;
        if (totalMb <= 3600L || Build.VERSION.SDK_INT <= 27 || budgetOem) return Tier.LOW;
        if (totalMb >= 7500L && Build.VERSION.SDK_INT >= 30) return Tier.HIGH;
        return Tier.NORMAL;
    }

    private static DevicePerformanceProfile high(UserMode selected, UserMode recommended) {
        return new DevicePerformanceProfile(Tier.HIGH, selected, recommended,
                800L, 1800L, 0f, 1000L, 2200L, 0f,
                1.0f, 1440, 3, false, 16L, 60);
    }

    private static DevicePerformanceProfile normal(UserMode selected, UserMode recommended) {
        return new DevicePerformanceProfile(Tier.NORMAL, selected, recommended,
                1300L, 3200L, 1f, 1500L, 3500L, 1f,
                1.0f, 1080, 3, false, 33L, 30);
    }

    private static DevicePerformanceProfile low(UserMode selected, UserMode recommended) {
        return new DevicePerformanceProfile(Tier.LOW, selected, recommended,
                2000L, 5000L, 2f, 2400L, 5500L, 2f,
                1.40f, 800, 2, true, 50L, 20);
    }

    private static DevicePerformanceProfile veryLow(UserMode selected, UserMode recommended) {
        return new DevicePerformanceProfile(Tier.VERY_LOW, selected, recommended,
                2700L, 7000L, 3f, 3200L, 7500L, 3f,
                1.70f, 640, 1, true, 66L, 15);
    }
}
