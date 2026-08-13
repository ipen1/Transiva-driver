package com.transiva.app;

import android.util.Log;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded worker pool + telemetry ringan. Accepted tasks are never silently evicted. */
public final class DriverNetworkExecutor {
    private static final String TAG = "DriverNetwork";
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final int QUEUE_CAPACITY = 128;
    private static final AtomicLong SUBMITTED = new AtomicLong();
    private static final AtomicLong COMPLETED = new AtomicLong();
    private static final AtomicLong REJECTED = new AtomicLong();
    private static final AtomicInteger PEAK_QUEUE = new AtomicInteger();

    private static final ThreadFactory FACTORY = r -> {
        Thread t = new Thread(r, "transiva-driver-net-" + IDS.incrementAndGet());
        t.setDaemon(true);
        return t;
    };

    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            4, 6, 30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            FACTORY,
            new ThreadPoolExecutor.AbortPolicy()
    );

    static { EXECUTOR.allowCoreThreadTimeOut(true); }
    private DriverNetworkExecutor() {}

    public static ExecutorService executor() { return EXECUTOR; }

    public static boolean execute(Runnable work) {
        if (work == null || EXECUTOR.isShutdown()) return false;
        try {
            SUBMITTED.incrementAndGet();
            EXECUTOR.execute(() -> {
                try { work.run(); }
                finally { COMPLETED.incrementAndGet(); }
            });
            updatePeak();
            return true;
        } catch (RejectedExecutionException e) {
            REJECTED.incrementAndGet();
            Log.w(TAG, "Network queue saturated; new task rejected safely");
            return false;
        }
    }

    private static void updatePeak() {
        int q = EXECUTOR.getQueue().size();
        int old;
        do { old = PEAK_QUEUE.get(); if (q <= old) return; }
        while (!PEAK_QUEUE.compareAndSet(old, q));
    }

    public static int queued() { return EXECUTOR.getQueue().size(); }
    public static int active() { return EXECUTOR.getActiveCount(); }
    public static int peakQueued() { return PEAK_QUEUE.get(); }
    public static long submitted() { return SUBMITTED.get(); }
    public static long completed() { return COMPLETED.get(); }
    public static long rejected() { return REJECTED.get(); }
}
