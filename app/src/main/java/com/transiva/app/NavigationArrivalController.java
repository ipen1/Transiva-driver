package com.transiva.app;

import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.transiva.app.driver.data.DriverApiClient;
import org.json.JSONObject;

/** Arrival from navigation uses the same two existing status mutations as Driver Trip. */
final class NavigationArrivalController {
    private final DriverNavigationActivity host;
    private final DriverUiScope scope;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TripSwipeControl swipe;
    private final TextView hint;
    private boolean active;
    private boolean submitting;
    private long stateAt = -1;
    private Location rawFix;
    private String attemptedStatus = "";
    private long attempt;
    private String error = "";

    NavigationArrivalController(DriverNavigationActivity host, LinearLayout dock) {
        this.host = host;
        scope = new DriverUiScope(host);
        swipe = new TripSwipeControl(host, "Geser untuk tiba di lokasi", this::submit);
        LinearLayout.LayoutParams slider = new LinearLayout.LayoutParams(-1, host.dp(49));
        slider.topMargin = host.dp(7);
        dock.addView(swipe, slider);
        hint = new TextView(host);
        hint.setGravity(Gravity.CENTER);
        hint.setTextColor(DriverThemeTokens.textSecondary(host));
        hint.setTextSize(11);
        hint.setPadding(host.dp(3), host.dp(3), host.dp(3), 0);
        dock.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        render();
    }

    void active(boolean value) {
        active = value;
        main.removeCallbacks(tick);
        if (active) {
            stateAt = -1;
            main.post(tick);
        } else swipe.setEnabled(false);
    }

    void resetOrder() {
        stateAt = -1;
        rawFix = null;
        attemptedStatus = "";
        attempt++;
        submitting = false;
        error = "";
        render();
    }

    void fix(Location raw) {
        rawFix = raw == null ? null : new Location(raw);
        render();
    }

    void synced(JSONObject order) {
        if (host.isFinishing()) return;
        stateAt = SystemClock.elapsedRealtime();
        if (!attemptedStatus.isEmpty() && attemptedStatus.equals(DriverOrderStateMachine.normalize(order.optString("status")))) {
            finishArrival();
            return;
        }
        render();
    }

    void close() {
        active(false);
        scope.close();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!active) return;
            render();
            main.postDelayed(this, 1000L);
        }
    };

    private String nextStatus() {
        JSONObject order = host.order;
        if (NavigationTargetPolicy.id(order).isEmpty() || NavigationTargetPolicy.terminal(order)) return "";
        String current = DriverOrderStateMachine.normalize(order.optString("status"));
        if ("taken".equals(current) && "pickup".equals(host.targetMode)) return "arrived_pickup";
        if (!"on_delivery".equals(current) || !"delivery".equals(host.targetMode)
                || DriverEcosystemFeatures.from(order).nextPendingWaypoint() != null) return "";
        return "arrived_delivery";
    }

    private NavigationTargetPolicy.Target target() {
        return NavigationTargetPolicy.resolve(host.order, host.targetMode, Double.NaN, Double.NaN);
    }

    private String blockedReason() {
        if (submitting) return "Mengonfirmasi kedatangan…";
        if (!active || stateAt < 0 || SystemClock.elapsedRealtime() - stateAt > 15_000L) {
            return "Menyinkronkan status perjalanan…";
        }
        if (nextStatus().isEmpty()) return "Lanjutkan tahap ini di Driver Trip";
        float distance = TripArrivalLocation.distance(TripArrivalLocation.verified(rawFix), target());
        if (distance < 0) return "Menunggu lokasi terverifikasi";
        return distance > TripArrivalLocation.RADIUS_METERS ? "Aktif dalam radius 100 m" : "";
    }

    private void render() {
        String reason = blockedReason();
        boolean ready = reason.isEmpty();
        if (swipe.isEnabled() != ready) swipe.setEnabled(ready);
        String guidance = !submitting && !error.isEmpty() ? error : ready ? "Lokasi terverifikasi" : reason;
        if (!guidance.contentEquals(hint.getText())) hint.setText(guidance);
    }

    private void submit() {
        if (!blockedReason().isEmpty() || !scope.alive()) { render(); return; }
        JSONObject order = host.order;
        String next = nextStatus();
        if (next.isEmpty() || !DriverOrderStateGuard.canTransition(order.optString("status"), next)) return;
        Location fix = TripArrivalLocation.verified(rawFix);
        float distance = TripArrivalLocation.distance(fix, target());
        if (distance < 0 || distance > TripArrivalLocation.RADIUS_METERS) { render(); return; }
        String orderId = NavigationTargetPolicy.id(order);
        String dbId = order.optString("id", orderId);
        String source = NavigationTargetPolicy.source(order);
        boolean pickupOrder = "pickup_orders".equals(source);
        String username = host.username;
        submitting = true;
        error = "";
        attemptedStatus = next;
        long request = ++attempt;
        render();
        if (!scope.execute(() -> {
            DriverApiClient client = new DriverApiClient(new SessionManager(host));
            try {
                JSONObject payload = new JSONObject().put("id", dbId).put("order_id", orderId)
                        .put("driver", username).put("driver_username", username)
                        .put("source", source).put("order_kind", pickupOrder ? "pickup" : "order")
                        .put("status", next).put("driver_lat", fix.getLatitude()).put("driver_lng", fix.getLongitude());
                JSONObject result = client.post(DriverOrderStateMachine.endpoint(next, pickupOrder), payload).body;
                if (!result.optBoolean("success")) {
                    response(order, request, result.optString("message", "Kedatangan ditolak server."));
                } else response(order, request, "");
            } catch (Exception e) {
                response(order, request, e.getMessage() == null || e.getMessage().trim().isEmpty()
                        ? "Kedatangan belum terkonfirmasi. Coba lagi." : e.getMessage());
            } finally { client.shutdown(); }
        })) response(order, request, "Koneksi sedang sibuk. Coba lagi.");
    }

    private void response(JSONObject order, long request, String failure) {
        scope.post(() -> {
            if (!submitting || !NavigationTargetPolicy.same(host.order, order)
                    || attempt != request) return;
            submitting = false;
            if (failure.isEmpty()) { finishArrival(); return; }
            error = failure;
            stateAt = -1;
            if (host.liveRoutes != null) host.liveRoutes.refreshOrderState();
            render();
        });
    }

    private void finishArrival() {
        submitting = false;
        attemptedStatus = "";
        attempt++;
        NavigationGuidanceService.stopFor(host, host.order);
        NavigationSessionStore.clear(host);
        host.finish();
    }
}
