package com.transiva.app.driver.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.SystemClock;

import com.transiva.app.R;
import com.transiva.app.DevicePerformanceProfile;

/**
 * Transisi premium untuk lima halaman utama driver.
 *
 * Urutan:
 * 0 Beranda
 * 1 Aktivitas
 * 2 Pesan
 * 3 Pendapatan
 * 4 Akun
 */
public final class DriverPageTransition {

    public static final int HOME = 0;
    public static final int ACTIVITY = 1;
    public static final int CHAT = 2;
    public static final int EARNINGS = 3;
    public static final int PROFILE = 4;

    private static final long CLICK_GUARD_MS = 450L;
    private static long lastNavigationAt;

    private DriverPageTransition() {
    }

    public static void open(
            Activity activity,
            Class<?> target,
            int fromIndex,
            int toIndex
    ) {
        if (
                activity == null
                        || target == null
                        || fromIndex == toIndex
        ) {
            return;
        }

        long now = SystemClock.elapsedRealtime();

        if (now - lastNavigationAt < CLICK_GUARD_MS) {
            return;
        }

        lastNavigationAt = now;

        Intent intent = new Intent(activity, target);
        intent.addFlags(
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
        );

        activity.startActivity(intent);
        if (!DevicePerformanceProfile.get(activity).reduceMapMotion) {
            activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        } else {
            activity.overridePendingTransition(0, 0);
        }
    }

    public static void animateResume(Activity activity, android.view.View root) {
        if (activity == null || root == null) return;
        DevicePerformanceProfile perf = DevicePerformanceProfile.get(activity);
        if (perf.reduceMapMotion) { root.setAlpha(1f); root.setTranslationY(0f); return; }
        root.setAlpha(0.96f);
        root.setTranslationY(Math.round(5 * activity.getResources().getDisplayMetrics().density));
        root.animate().alpha(1f).translationY(0f).setDuration(perf.targetFps >= 60 ? 180L : 130L)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }
}
