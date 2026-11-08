package com.transiva.app;

import com.transiva.app.driver.domain.DriverOrder;
import org.json.JSONObject;

final class DriverActiveOrderSummary {
    final String action;
    final String target;
    final String address;

    private DriverActiveOrderSummary(String action, String target, String address) {
        this.action = action;
        this.target = target;
        this.address = address == null || address.trim().isEmpty() ? "Alamat belum tersedia" : address;
    }

    static DriverOrder guided(android.content.Context context, java.util.List<DriverOrder> active) {
        String owner = NavigationSessionStore.identity(context);
        long now = android.os.SystemClock.elapsedRealtime();
        for (DriverOrder order : active) {
            if (NavigationGuidanceHealth.guiding(owner, order.id, order.source, now)
                    || NavigationGuidanceHealth.guiding(owner, order.raw.optString("public_order_id"), order.source, now)) {
                return order;
            }
        }
        return null;
    }

    static DriverActiveOrderSummary from(DriverOrder order) {
        JSONObject data = order.raw;
        String status = DriverOrderStateMachine.normalize(order.status);
        if ("taken".equals(status)) {
            return new DriverActiveOrderSummary("Tiba di penjemputan", "Lokasi jemput", order.pickupAddress);
        }
        if ("arrived_pickup".equals(status)) {
            return new DriverActiveOrderSummary("Mulai antar", "Lokasi jemput", order.pickupAddress);
        }
        if (("on_delivery".equals(status) || "arrived_delivery".equals(status))
                && !"pickup_orders".equals(order.source)) {
            JSONObject waypoint = DriverEcosystemFeatures.from(data).nextPendingWaypoint();
            if (waypoint != null) {
                return new DriverActiveOrderSummary("Tiba di persinggahan",
                        "Persinggahan " + waypoint.optInt("sequence", 1), waypoint.optString("address"));
            }
        }
        if ("on_delivery".equals(status)) {
            return new DriverActiveOrderSummary("Tiba di tujuan", "Tujuan", order.destinationAddress);
        }
        if ("arrived_delivery".equals(status)) {
            JSONObject extension = data.optJSONObject("destination_extension");
            String action = "pending".equals(data.optString("price_change_status"))
                    ? "Menunggu persetujuan biaya"
                    : data.optInt("customer_received") == 1 ? "Selesaikan order" : "Menunggu konfirmasi penerima";
            if (extension != null && "approved".equals(extension.optString("status"))
                    && extension.optInt("needs_arrival") == 1) action = "Tiba di tujuan tambahan";
            return new DriverActiveOrderSummary(action, "Tujuan", order.destinationAddress);
        }
        return new DriverActiveOrderSummary("Periksa perjalanan", "Tujuan", order.destinationAddress);
    }
}
