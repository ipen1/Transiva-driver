package com.transiva.app;

import android.location.Location;
import org.json.JSONObject;

/** One next action and one eligibility rule for both map activities. */
final class TripActionPolicy {
    static final class Action {
        final String next, label, hint;
        final int stop;
        final boolean enabled;
        Action(String next, String label, String hint, int stop, boolean enabled) {
            this.next = next; this.label = label; this.hint = hint; this.stop = stop; this.enabled = enabled;
        }
    }

    static Action resolve(TripActionSession session) {
        JSONObject order = session.order();
        String status = DriverOrderStateMachine.normalize(order.optString("status"));
        String next = "", label = "Perjalanan berakhir", reason = "";
        int stop = 0;
        boolean arrival = false;
        double radius = 100;
        NavigationTargetPolicy.Target target = NavigationTargetPolicy.resolve(order, null, Double.NaN, Double.NaN);
        if ("taken".equals(status)) { next = "arrived_pickup"; label = "Geser untuk tiba di penjemputan"; arrival = true; }
        else if ("arrived_pickup".equals(status)) {
            next = "on_delivery";
            label = "Geser untuk mulai perjalanan";
            if (FoodTripPresentation.waitsForMerchant(order)) reason = "Menunggu konfirmasi restoran";
        }
        else if ("on_delivery".equals(status) || "arrived_delivery".equals(status)) {
            JSONObject waypoint = DriverEcosystemFeatures.from(order).nextPendingWaypoint();
            JSONObject extension = order.optJSONObject("destination_extension");
            boolean extra = extension != null && "approved".equals(extension.optString("status"))
                    && extension.optInt("needs_arrival") == 1;
            if (waypoint != null) {
                stop = waypoint.optInt("sequence", 1); next = "waypoint";
                label = "Geser untuk tiba di persinggahan " + stop; arrival = true; radius = 200;
                target = new NavigationTargetPolicy.Target("waypoint_" + stop,
                        waypoint.optDouble("latitude", Double.NaN), waypoint.optDouble("longitude", Double.NaN));
            } else if ("on_delivery".equals(status) || extra) {
                next = extra ? "extension" : "arrived_delivery";
                label = "Geser untuk tiba di pengantaran"; arrival = true; radius = extra ? 200 : 100;
            } else {
                next = "finished"; label = "Geser untuk selesaikan order";
                if (extension != null && "pending".equals(extension.optString("status"))) reason = "Menunggu persetujuan tujuan tambahan";
                else if ("pending".equals(order.optString("price_change_status"))) reason = "Menunggu persetujuan biaya pelanggan";
                else if (order.optInt("customer_received") != 1)
                    reason = "Menunggu konfirmasi pelanggan";
            }
        }
        if ("arrived_pickup".equals(next) && FoodTripPresentation.isFood(order)) label = "Geser untuk tiba di resto";
        if ("arrived_pickup".equals(next) && DeliveryTripPresentation.shop(order)) label = "Geser untuk tiba di toko";
        if (arrival) {
            Location fix = session.fix();
            float distance = TripArrivalLocation.distance(fix, target);
            if (distance < 0) reason = "Menunggu lokasi terverifikasi";
            else if (distance > radius) reason = "Aktif dalam radius " + (int) radius + " m";
        }
        if (NavigationTargetPolicy.terminal(order)) {
            next = ""; label = "Perjalanan berakhir"; reason = "Perjalanan telah berakhir";
        }
        if ("pending".equals(order.optString("price_change_status"))
                && order.optString("price_change_reason").startsWith("[Lokasi #")) reason = "Menunggu customer meninjau usulan lokasi";
        if (session.busy()) reason = "Memproses perubahan order…";
        else if (!session.fresh()) reason = "Menyinkronkan status perjalanan…";
        boolean enabled = !next.isEmpty() && reason.isEmpty() && !NavigationTargetPolicy.terminal(order);
        return new Action(next, label, enabled ? (arrival ? "Lokasi terverifikasi" : "Geser untuk melanjutkan perjalanan") : reason,
                stop, enabled);
    }
}
