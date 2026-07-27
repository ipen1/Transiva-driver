package com.transiva.app.driver.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.SystemClock;

import com.transiva.app.R;

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

        boolean moveRight = toIndex > fromIndex;

        activity.overridePendingTransition(
                moveRight
                        ? R.anim.transiva_page_enter_right
                        : R.anim.transiva_page_enter_left,
                moveRight
                        ? R.anim.transiva_page_exit_left
                        : R.anim.transiva_page_exit_right
        );
    }
}
