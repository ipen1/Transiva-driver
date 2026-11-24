package com.transiva.app;

import android.location.Location;
import com.transiva.app.driver.data.DriverApiClient;
import org.json.JSONObject;

/** Same endpoints, waypoint sequence and completion flow used by the trip screen. */
final class TripNavigationAction {
    static void execute(DriverNavigationActivity host, TripActionSession shared, TripActionPolicy.Action expected) {
        TripActionPolicy.Action action = TripActionPolicy.resolve(shared);
        if (!action.enabled || !action.next.equals(expected.next) || action.stop != expected.stop) return;
        JSONObject order = shared.order();
        Location fix = shared.fix();
        String source = NavigationTargetPolicy.source(order);
        String id = order.optString("id", NavigationTargetPolicy.id(order));
        String publicId = NavigationTargetPolicy.id(order);
        DriverApiClient client = new DriverApiClient(new SessionManager(host));
        if (!shared.begin()) { client.shutdown(); return; }
        boolean queued = DriverNetworkExecutor.executeOrderAction(() -> {
            try {
                JSONObject payload = new JSONObject().put("id", id).put("order_id", publicId)
                        .put("source", source).put("order_kind", "pickup_orders".equals(source) ? "pickup" : "order")
                        .put("driver", host.username).put("driver_username", host.username)
                        .put("status", action.next);
                if (fix != null) payload.put("driver_lat", fix.getLatitude()).put("driver_lng", fix.getLongitude());
                String endpoint = DriverOrderStateMachine.endpoint(action.next, "pickup_orders".equals(source));
                if ("waypoint".equals(action.next)) {
                    endpoint = "ride_ecosystem_order.php";
                    payload.put("action", "arrive_waypoint").put("sequence", action.stop);
                } else if ("extension".equals(action.next)) {
                    endpoint = "driver_destination_extension.php";
                    payload.put("action", "arrive").put("request_id", order.getJSONObject("destination_extension").optLong("id"));
                }
                JSONObject result = client.post(endpoint, payload).body;
                if (!result.optBoolean("success")) throw new java.io.IOException(result.optString("message", "Status belum terkonfirmasi."));
                JSONObject updated = TripActionSession.merge(order, result.optJSONObject("order"));
                if ("waypoint".equals(action.next)) {
                    if (result.optJSONObject("ecosystem") != null) updated.put("ecosystem", result.optJSONObject("ecosystem"));
                    else throw new java.io.IOException("Menunggu sinkronisasi persinggahan.");
                } else if ("extension".equals(action.next)) {
                    updated = TripActionSession.merge(updated, client.post("driver_trip_realtime.php",
                            new JSONObject().put("order_id", publicId).put("order_db_id", id)).body.optJSONObject("order"));
                } else updated.put("status", action.next);
                final JSONObject fresh = updated;
                host.main.post(() -> client.applyIfCurrent(() -> {
                    shared.completed(fresh);
                    new TripOrderSnapshotStore(host).save(fresh, "pickup_orders".equals(source) ? "pickup" : "order", host.vehicleType);
                    if ("finished".equals(action.next)) {
                        host.order = fresh;
                        if (host.liveRoutes != null) host.liveRoutes.close();
                        new TripOrderSnapshotStore(host).clearFor(fresh);
                        NavigationGuidanceService.stopFor(host, fresh);
                        DriverMessageUnreadRepository.clearOrder(host, publicId);
                        DriverOrderCompletionDialog.show(host, result, fresh, (rating, review, reasons, callback) ->
                                DriverNetworkExecutor.execute(() -> {
                                    DriverApiClient ratingClient = new DriverApiClient(new SessionManager(host));
                                    try {
                                        JSONObject response = ratingClient.post("driver_rate_customer.php", new JSONObject()
                                                .put("id", id).put("order_id", publicId).put("source", source)
                                                .put("rating", rating).put("review", review).put("reasons", reasons)).body;
                                        callback.complete(response.optBoolean("success"), response.optString("message"));
                                    } catch (Exception e) { callback.complete(false, e.getMessage()); }
                                    finally { ratingClient.shutdown(); }
                                }), host::finish);
                    } else {
                        if (host.liveRoutes != null) host.liveRoutes.actionConfirmed(fresh);
                        else host.order = fresh;
                        host.onNavigationOrderSynced(fresh);
                    }
                }));
            } catch (Exception error) {
                host.main.post(() -> client.applyIfCurrent(() -> {
                    shared.failed();
                    if (host.liveRoutes != null) host.liveRoutes.refreshOrderState();
                    message(host, error.getMessage());
                }));
            } finally {
                // Keep the owner current until the posted UI application finishes.
                host.main.post(client::shutdown);
            }
        });
        if (!queued) { shared.failed(); client.shutdown(); message(host, "Antrean aksi penuh. Coba lagi."); }
    }

    private static void message(DriverNavigationActivity host, String message) {
        if (!host.isFinishing() && !host.isDestroyed())
            PremiumDialogs.builder(host).setMessage(message == null ? "Status belum terkonfirmasi." : message)
                    .setPositiveButton("Tutup", null).show();
    }
}
