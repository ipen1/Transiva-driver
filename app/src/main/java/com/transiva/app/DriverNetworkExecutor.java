package com.transiva.app;

import android.util.Log;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded worker pool. Accepted tasks are never silently evicted. */
public final class DriverNetworkExecutor {
    private static final String TAG = "DriverNetwork";
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final int QUEUE_CAPACITY = 128;

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
            EXECUTOR.execute(work);
            return true;
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "Network queue saturated; new task rejected safely");
            return false;
        }
    }

    public static int queued() { return EXECUTOR.getQueue().size(); }
    public static int active() { return EXECUTOR.getActiveCount(); }
}
