package com.transiva.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import com.transiva.app.driver.data.DriverApiClient;
import com.transiva.app.driver.domain.DriverDashboardState;
import com.transiva.app.driver.domain.DriverOrder;
import org.json.JSONArray;
import java.util.HashSet;
import java.util.Set;

final class DashboardOfferMonitor {
    interface Host {
        DriverDashboardState state();
        void refreshOrders();
    }

    private final ConnectivityManager connectivity;
    private final SessionManager session;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::check;
    private boolean active;
    private boolean registered;
    private DriverApiClient inFlight;
    private long lastMismatchRefresh;
    private final ConnectivityManager.NetworkCallback network = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network ignored) {
            main.post(() -> {
                if (!active) return;
                DriverOrderTelemetry.local(session.getContext(), "jaringan tersedia · " + DriverOrderTelemetry.deviceContext(session.getContext()));
                host.refreshOrders();
                main.removeCallbacks(tick);
                main.post(tick);
            });
        }
    };

    DashboardOfferMonitor(Context context, SessionManager session, Host host) {
        connectivity = context.getSystemService(ConnectivityManager.class);
        this.session = session;
        this.host = host;
    }

    void start() {
        active = true;
        main.removeCallbacks(tick);
        main.post(tick);
        if (connectivity != null && !registered) {
            try {
                connectivity.registerDefaultNetworkCallback(network);
                registered = true;
            } catch (RuntimeException error) {
                TransivaDiagnostics.error(session.getContext(), "order", "OFFER_NETWORK_WATCH_FAILED", error);
            }
        }
    }

    void stop() {
        active = false;
        main.removeCallbacksAndMessages(null);
        if (inFlight != null) inFlight.shutdown();
        inFlight = null;
        if (registered) {
            registered = false;
            try { connectivity.unregisterNetworkCallback(network); }
            catch (RuntimeException ignored) {}
        }
    }

    private void check() {
        if (!active) return;
        main.removeCallbacks(tick);
        main.postDelayed(tick, WaveLoadGuard.jitter(4500L));
        DriverDashboardState state = host.state();
        if (inFlight != null || state == null || !state.online) return;
        DriverApiClient client = new DriverApiClient(session, true);
        inFlight = client;
        if (!DriverNetworkExecutor.execute(() -> {
            try {
                JSONArray cycles = client.getOnce("driver_order_signal.php").body.optJSONArray("offer_cycles");
                if (cycles != null) {
                    Set<String> live = new HashSet<>();
                    for (int i = 0; i < cycles.length(); i++) live.add(cycles.optString(i));
                    main.post(() -> client.applyIfCurrent(() -> compare(client, live)));
                }
            } catch (DriverApiClient.ApiException ignored) {
                // Dashboard, FCM, and the next successful signal reconcile the state.
            } finally {
                main.post(() -> {
                    if (inFlight == client) inFlight = null;
                    client.shutdown();
                });
            }
        })) {
            inFlight = null;
            client.shutdown();
        }
    }

    private void compare(DriverApiClient client, Set<String> live) {
        DriverDashboardState state = host.state();
        if (!active || inFlight != client || state == null || !state.online) return;
        Set<String> shown = new HashSet<>();
        for (DriverOrder offer : state.offers) {
            String cycle = offer.raw.optString("offer_action_token");
            if (!cycle.isEmpty()) shown.add(cycle);
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (live.equals(shown)) {
            lastMismatchRefresh = 0;
        } else if (lastMismatchRefresh == 0 || now - lastMismatchRefresh >= 6500L) {
            lastMismatchRefresh = now;
            host.refreshOrders();
        }
    }
}
