package com.transiva.app;

import android.os.Handler;
import android.os.SystemClock;

/** Owns route loading progress and retry timing so navigation Activity stays lifecycle-focused. */
public final class NavigationRouteScheduler {
    public interface Callback {
        boolean isFinishing();
        boolean isRouteInFlight();
        boolean hasRoute();
        void requestRoute(boolean force);
        void showRouteLoading(int percent);
    }

    private final Handler main;
    private final Callback callback;
    private int loadingPercent;
    private long loadingStartedAt;
    private boolean started;

    public NavigationRouteScheduler(Handler main, Callback callback) {
        this.main = main;
        this.callback = callback;
    }

    public void start() {
        if (started) return;
        started = true;
        main.postDelayed(retryTick, 2200L);
    }

    public void stop() {
        started = false;
        main.removeCallbacks(retryTick);
        main.removeCallbacks(loadingTick);
    }

    public void beginLoading(int initialPercent) {
        loadingPercent = Math.max(0, Math.min(100, initialPercent));
        loadingStartedAt = SystemClock.elapsedRealtime();
        if (callback != null) callback.showRouteLoading(loadingPercent);
        main.removeCallbacks(loadingTick);
        main.post(loadingTick);
    }

    public void updateProgress(int percent) {
        loadingPercent = Math.max(loadingPercent, Math.max(0, Math.min(100, percent)));
        if (callback != null) callback.showRouteLoading(loadingPercent);
    }

    public void endLoading() {
        main.removeCallbacks(loadingTick);
    }

    private final Runnable loadingTick = new Runnable() {
        @Override public void run() {
            if (!started || callback == null || !callback.isRouteInFlight() || callback.isFinishing()) return;
            long elapsed = Math.max(0L, SystemClock.elapsedRealtime() - loadingStartedAt);
            int target;
            if (elapsed < 500L) target = 18;
            else if (elapsed < 1200L) target = 38;
            else if (elapsed < 2500L) target = 58;
            else if (elapsed < 4500L) target = 74;
            else target = 88;
            if (loadingPercent < target) loadingPercent++;
            callback.showRouteLoading(loadingPercent);
            main.postDelayed(this, 90L);
        }
    };

    private final Runnable retryTick = new Runnable() {
        @Override public void run() {
            if (!started || callback == null || callback.isFinishing()) return;
            if (!callback.hasRoute()) callback.requestRoute(true);
            main.postDelayed(this, callback.hasRoute() ? 15000L : 2200L);
        }
    };
}
