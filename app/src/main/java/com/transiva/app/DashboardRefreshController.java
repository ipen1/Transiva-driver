package com.transiva.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.transiva.app.driver.domain.DriverDashboardState;

/** Owns dashboard polling/countdown lifecycle so Activity only renders UI. */
public final class DashboardRefreshController {
    public interface Host {
        Context context();
        DriverDashboardState state();
        void refreshDashboard();
        void tickOfferCountdown();
    }

    private static final long IDLE_REFRESH_MS = 60_000L;
    private static final long ACTIVE_REFRESH_MS = 15_000L;
    private static final long OFFER_REFRESH_MS = 8_000L;
    private static final long COUNTDOWN_TICK_MS = 1_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Host host;
    private boolean running;

    public DashboardRefreshController(Host host) {
        this.host = host;
    }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!running) return;
            host.refreshDashboard();
            main.postDelayed(this, WaveLoadGuard.jitter(adaptiveRefreshMs()));
        }
    };

    private final Runnable countdown = new Runnable() {
        @Override public void run() {
            if (!running) return;
            host.tickOfferCountdown();
            main.postDelayed(this, COUNTDOWN_TICK_MS);
        }
    };

    public void start() {
        running = true;
        main.removeCallbacks(refresh);
        main.removeCallbacks(countdown);
        main.postDelayed(refresh, WaveLoadGuard.jitter(adaptiveRefreshMs()));
        main.post(countdown);
    }

    public void stop() {
        running = false;
        main.removeCallbacks(refresh);
        main.removeCallbacks(countdown);
    }

    public void scheduleSoon(long delayMs) {
        if (!running) return;
        main.removeCallbacks(refresh);
        main.postDelayed(refresh, Math.max(0L, delayMs));
    }

    public void destroy() {
        stop();
        main.removeCallbacksAndMessages(null);
    }

    long adaptiveRefreshMs() {
        DriverDashboardState state = host.state();
        long base = IDLE_REFRESH_MS;
        if (state != null) {
            if (state.offers != null && !state.offers.isEmpty()) base = OFFER_REFRESH_MS;
            else if (state.activeOrders != null && !state.activeOrders.isEmpty()) base = ACTIVE_REFRESH_MS;
        }
        return DriverPollingCoordinator.interval(host.context(), base);
    }
}
