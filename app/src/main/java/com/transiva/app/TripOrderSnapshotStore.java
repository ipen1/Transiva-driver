package com.transiva.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

/** Persists/restores the minimum active-trip snapshot outside DriverTripActivity. */
public final class TripOrderSnapshotStore {
    private static final String PREF_NAME = "transiva";
    private final SharedPreferences prefs;

    public TripOrderSnapshotStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public void save(JSONObject order, String orderKind, String driverType) {
        if (order == null) return;
        prefs.edit()
                .putString("driver_active_order_json", order.toString())
                .putString("driver_active_order_id", first(order.optString("order_id"), order.optString("id"), "-"))
                .putString("driver_active_order_kind", clean(orderKind))
                .putString("driver_active_order_status", normalize(first(order.optString("status"), "taken")))
                .putString("driver_active_pickup_address", first(order.optString("pickup_address"), order.optString("pickup"), order.optString("sender_address"), "-"))
                .putString("driver_active_delivery_address", first(order.optString("delivery_address"), order.optString("destination_address"), order.optString("destination"), order.optString("receiver_address"), "-"))
                .putString("driver_active_pickup_lat", String.valueOf(coord(order, "pickup_lat", "user_lat")))
                .putString("driver_active_pickup_lng", String.valueOf(coord(order, "pickup_lng", "user_lng")))
                .putString("driver_active_delivery_lat", String.valueOf(coord(order, "delivery_lat", "destination_lat")))
                .putString("driver_active_delivery_lng", String.valueOf(coord(order, "delivery_lng", "destination_lng")))
                .putString("driver_active_price", String.valueOf(number(order, "price", "fare", "total")))
                .putString("driver_type", clean(driverType))
                .putString("active_driver_type", clean(driverType))
                .apply();
    }

    public void clear() {
        prefs.edit()
                .remove("driver_active_order_json")
                .remove("driver_active_order_id")
                .remove("driver_active_order_kind")
                .remove("driver_active_order_status")
                .apply();
    }

    private static double coord(JSONObject o, String a, String b) {
        try { return Double.parseDouble(first(o.optString(a), o.optString(b), "0")); } catch (Throwable ignored) { return 0d; }
    }
    private static double number(JSONObject o, String... keys) {
        for (String k : keys) try { if (o.has(k)) return Double.parseDouble(o.optString(k, "0")); } catch (Throwable ignored) {}
        return 0d;
    }
    private static String normalize(String s) { return clean(s).toLowerCase(java.util.Locale.US).replace('-', '_').replace(' ', '_'); }
    private static String clean(String s) { return s == null ? "" : s.trim(); }
    private static String first(String... values) {
        if (values != null) for (String s : values) if (s != null && !s.trim().isEmpty() && !"null".equalsIgnoreCase(s.trim())) return s.trim();
        return "";
    }
}
