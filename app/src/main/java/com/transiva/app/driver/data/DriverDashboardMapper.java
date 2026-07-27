package com.transiva.app.driver.data;

import com.transiva.app.driver.domain.DriverDashboardState;
import com.transiva.app.driver.domain.DriverOrder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DriverDashboardMapper {

    private DriverDashboardMapper() {}

    public static DriverDashboardState map(JSONObject root) {
        JSONObject driver = root.optJSONObject("driver");
        JSONObject wallet = root.optJSONObject("wallet");
        JSONObject performance = root.optJSONObject("performance");

        if (driver == null) driver = new JSONObject();
        if (wallet == null) wallet = new JSONObject();
        if (performance == null) performance = new JSONObject();

        DriverOrder active = mapOrder(root.optJSONObject("active_order"));
        List<DriverOrder> offers = new ArrayList<>();

        JSONArray array = root.optJSONArray("offers");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                DriverOrder order = mapOrder(array.optJSONObject(i));
                if (order != null) offers.add(order);
            }
        }

        return new DriverDashboardState(
                driver.optString("username", ""),
                first(driver.optString("name"), driver.optString("username"), "Driver"),
                normalizeDriverType(driver.optString("driver_type", "bike")),
                readBoolean(driver, "is_online", false),
                readBoolean(driver, "verified",
                        readBoolean(driver, "verified_by_admin", false)),
                readLong(wallet, "balance", 0),
                readLong(wallet, "pending_deposit", 0),
                readLong(wallet, "pending_withdraw", 0),
                readLong(performance, "today_earning", 0),
                readInt(performance, "today_trips", 0),
                readDouble(performance, "rating", 0),
                active,
                offers,
                readLong(root, "server_time_millis", System.currentTimeMillis())
        );
    }

    public static DriverOrder mapOrder(JSONObject order) {
        if (order == null || order.length() == 0) return null;

        return new DriverOrder(
                first(order.optString("id"), order.optString("order_id")),
                first(order.optString("source"), order.optString("_transiva_table"), "orders"),
                first(order.optString("service_name"), order.optString("service_type"),
                        order.optString("order_type"), "Transiva"),
                order.optString("status", ""),
                first(order.optString("pickup_address"), order.optString("pickup"), "-"),
                first(order.optString("destination_address"),
                        order.optString("delivery_address"),
                        order.optString("destination"),
                        order.optString("delivery"), "-"),
                readLong(order, "driver_earning",
                        readLong(order, "price",
                                readLong(order, "total_price", 0))),
                first(order.optString("pickup_distance_text"),
                        order.optString("distance_km"), ""),
                readInt(order, "remaining_seconds", -1),
                order
        );
    }

    private static boolean readBoolean(JSONObject object, String key, boolean fallback) {
        if (object == null || !object.has(key)) return fallback;

        Object value = object.opt(key);
        if (value == null || value == JSONObject.NULL) return fallback;
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() != 0;

        String clean = String.valueOf(value).trim().toLowerCase(Locale.US);

        if (clean.equals("1") || clean.equals("true") || clean.equals("yes")
                || clean.equals("on") || clean.equals("online")
                || clean.equals("aktif")) return true;

        if (clean.equals("0") || clean.equals("false") || clean.equals("no")
                || clean.equals("off") || clean.equals("offline")
                || clean.equals("nonaktif") || clean.isEmpty()) return false;

        return fallback;
    }

    private static long readLong(JSONObject object, String key, long fallback) {
        if (object == null || !object.has(key)) return fallback;
        Object value = object.opt(key);

        if (value instanceof Number) return ((Number) value).longValue();

        try {
            return Long.parseLong(String.valueOf(value)
                    .replace("Rp", "")
                    .replace(".", "")
                    .replace(",", "")
                    .trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int readInt(JSONObject object, String key, int fallback) {
        long value = readLong(object, key, fallback);
        if (value > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (value < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) value;
    }

    private static double readDouble(JSONObject object, String key, double fallback) {
        if (object == null || !object.has(key)) return fallback;
        Object value = object.opt(key);

        if (value instanceof Number) return ((Number) value).doubleValue();

        try {
            return Double.parseDouble(String.valueOf(value)
                    .replace(",", ".")
                    .trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String normalizeDriverType(String value) {
        String clean = value == null ? "" : value.trim().toLowerCase(Locale.US);
        return clean.equals("car") || clean.equals("mobil")
                || clean.equals("transcar") ? "car" : "bike";
    }

    private static String first(String... values) {
        if (values == null) return "";

        for (String value : values) {
            if (value == null) continue;
            String clean = value.trim();

            if (!clean.isEmpty()
                    && !"null".equalsIgnoreCase(clean)
                    && !"undefined".equalsIgnoreCase(clean)) {
                return clean;
            }
        }

        return "";
    }
}
