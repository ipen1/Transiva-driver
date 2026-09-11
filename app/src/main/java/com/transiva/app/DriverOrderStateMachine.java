package com.transiva.app;

import java.util.Locale;

/** Pure order-state rules shared by Trip UI and regression tests. */
public final class DriverOrderStateMachine {
    private DriverOrderStateMachine() {}

    public static String normalize(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.US)
                .replace('-', '_').replace(' ', '_');
        if (s.isEmpty()) return "taken";
        if (eq(s, "accepted", "driver_accepted", "driver_assigned", "assigned",
                "merchant_accepted", "processing", "confirmed")) return "taken";
        if (eq(s, "arrived", "at_pickup", "pickup_arrived", "arrive_pickup")) return "arrived_pickup";
        if (eq(s, "picked_up", "pickedup", "start_delivery", "delivering", "in_delivery", "otw_delivery")) return "on_delivery";
        if (eq(s, "at_delivery", "delivery_arrived", "arrive_delivery")) return "arrived_delivery";
        if (eq(s, "finish", "done", "success", "completed")) return "finished";
        return s;
    }

    public static boolean isDeliveryPhase(String raw) {
        String s = normalize(raw);
        return "arrived_pickup".equals(s) || "on_delivery".equals(s)
                || "arrived_delivery".equals(s) || "finished".equals(s);
    }

    public static String label(String raw) {
        String s = normalize(raw);
        if ("taken".equals(s)) return "Menuju Penjemputan";
        if ("arrived_pickup".equals(s)) return "Tiba di Penjemputan";
        if ("on_delivery".equals(s)) return "Menuju Delivery";
        if ("arrived_delivery".equals(s)) return "Tiba Delivery";
        if ("finished".equals(s)) return "Selesai";
        return s.isEmpty() ? "Menuju Penjemputan" : s;
    }

    public static String endpoint(String next, boolean pickupOrder) {
        if (pickupOrder) return "driver_update_unified_status.php";
        String s = normalize(next);
        if ("arrived_pickup".equals(s)) return "driverArrivedPickup.php";
        if ("on_delivery".equals(s)) return "driverStartDelivery.php";
        if ("arrived_delivery".equals(s)) return "driverArrivedDelivery.php";
        if ("finished".equals(s)) return "finishOrder.php";
        return "driver_update_unified_status.php";
    }

    private static boolean eq(String value, String... choices) {
        for (String choice : choices) if (choice.equals(value)) return true;
        return false;
    }
}
