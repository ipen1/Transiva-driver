package com.transiva.app;

import java.util.concurrent.ThreadLocalRandom;

/** Menyebarkan polling fallback agar armada driver tidak request pada detik yang sama. */
public final class WaveLoadGuard {
    private WaveLoadGuard() {}

    public static long jitter(long baseMs) {
        if (baseMs <= 0L) return 1000L;
        long spread = Math.max(1L, baseMs * 18L / 100L);
        return Math.max(1000L, baseMs + ThreadLocalRandom.current().nextLong(-spread, spread + 1L));
    }
}
