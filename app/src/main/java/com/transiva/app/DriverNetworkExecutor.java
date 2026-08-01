package com.transiva.app;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded worker pool untuk mencegah ledakan thread pada jaringan lambat. */
public final class DriverNetworkExecutor {
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final ThreadFactory FACTORY = r -> {
        Thread t = new Thread(r, "transiva-net-" + IDS.incrementAndGet());
        t.setDaemon(true);
        return t;
    };
    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(
            4, 6, 30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(96), FACTORY,
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private DriverNetworkExecutor() {}
    public static void execute(Runnable work) { if (work != null) EXECUTOR.execute(work); }
}
