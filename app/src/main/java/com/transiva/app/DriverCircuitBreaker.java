package com.transiva.app;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Circuit breaker ringan untuk mencegah request storm ketika server 429/5xx/down.
 * Tidak memblokir permanen: setelah cooldown request percobaan diizinkan lagi.
 */
public final class DriverCircuitBreaker {
    private static final int FAILURE_THRESHOLD = 4;
    private static final long OPEN_MS = 30_000L;
    private static final AtomicInteger FAILURES = new AtomicInteger(0);
    private static volatile long openUntil = 0L;

    private DriverCircuitBreaker() {}

    public static boolean allowRequest() {
        long now = System.currentTimeMillis();
        if (now >= openUntil) return true;
        return false;
    }

    public static long remainingMs() {
        return Math.max(0L, openUntil - System.currentTimeMillis());
    }

    public static void onSuccess() {
        FAILURES.set(0);
        openUntil = 0L;
    }

    public static void onFailure(int status) {
        if (!(status == 0 || status == 429 || status >= 500)) return;
        int n = FAILURES.incrementAndGet();
        if (n >= FAILURE_THRESHOLD) {
            openUntil = System.currentTimeMillis() + OPEN_MS;
            FAILURES.set(0);
        }
    }
}
