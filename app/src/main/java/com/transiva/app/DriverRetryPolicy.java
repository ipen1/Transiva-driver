package com.transiva.app;

import java.util.concurrent.ThreadLocalRandom;

/** Retry/backoff terpusat untuk request driver. */
public final class DriverRetryPolicy {
    private static final long MAX_DELAY_MS = 60_000L;
    private DriverRetryPolicy() {}

    public static long delayFor(int status, int retryAfterSeconds, int attempt) {
        if (status == 429 && retryAfterSeconds > 0) {
            return Math.min(MAX_DELAY_MS, Math.max(1_000L, retryAfterSeconds * 1_000L));
        }

        if (status == 429 || status >= 500 || status == 0) {
            int safeAttempt = Math.max(0, Math.min(4, attempt));
            long base = Math.min(MAX_DELAY_MS, 5_000L << safeAttempt); // 5,10,20,40,60 dtk
            long jitter = ThreadLocalRandom.current().nextLong(0L, Math.max(1L, base / 5L));
            return Math.min(MAX_DELAY_MS, base + jitter);
        }
        return 0L;
    }
}
