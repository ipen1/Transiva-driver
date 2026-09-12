package com.transiva.app;

import org.json.JSONObject;
import java.util.Locale;

/** Pure session-payload normalization; SessionManager remains responsible for persistence only. */
final class SessionUserNormalizer {
    private SessionUserNormalizer() {}

    static JSONObject normalize(JSONObject user) throws Exception {
        JSONObject out = new JSONObject(user == null ? "{}" : user.toString());
        String id = firstNonEmpty(out.optString("id", ""), out.optString("user_id", ""), out.optString("uid", ""));
        String username = firstNonEmpty(out.optString("username", ""), out.optString("user_name", ""), out.optString("name", ""));
        String name = firstNonEmpty(out.optString("name", ""), out.optString("full_name", ""), username);
        String role = normalizeRole(firstNonEmpty(out.optString("role", ""), out.optString("user_role", ""), "customer"));
        JSONObject nestedProfile = out.optJSONObject("driver_profile");
        if (nestedProfile == null) nestedProfile = out.optJSONObject("profile");
        if (nestedProfile == null) nestedProfile = new JSONObject();
        String driverType = firstNonEmpty(out.optString("driver_type", ""), nestedProfile.optString("driver_type", ""), "bike").toLowerCase(Locale.US);
        if (!"car".equals(driverType)) driverType = "bike";
        out.put("id", id);
        out.put("user_id", firstNonEmpty(out.optString("user_id", ""), id));
        out.put("username", username);
        out.put("name", name);
        out.put("role", role);
        out.put("driver_type", driverType);
        if (!out.has("phone")) out.put("phone", "");
        out.put("token", firstNonEmpty(out.optString("token", ""), out.optString("access_token", ""), out.optString("auth_token", ""), out.optString("api_token", ""), out.optString("session_token", "")));
        if (!out.has("restaurant_id")) out.put("restaurant_id", "");
        if (!out.has("balance")) out.put("balance", "0");
        if (!out.has("photo")) out.put("photo", out.optString("driver_photo", ""));
        if (!out.has("driver_photo") || safe(out.optString("driver_photo", "")).isEmpty()) {
            out.put("driver_photo", firstNonEmpty(nestedProfile.optString("driver_photo", ""), nestedProfile.optString("profile_photo", ""), out.optString("photo", "")));
        }
        if (!out.has("plate") || safe(out.optString("plate", "")).isEmpty()) out.put("plate", nestedProfile.optString("plate", ""));
        if (!out.has("verification_status") || safe(out.optString("verification_status", "")).isEmpty()) out.put("verification_status", nestedProfile.optString("verification_status", ""));
        if (!out.has("is_online")) out.put("is_online", nestedProfile.opt("is_online"));
        if (!out.has("is_busy")) out.put("is_busy", nestedProfile.opt("is_busy"));
        return out;
    }

    static String normalizeRole(String roleValue) {
        if (roleValue == null) return "";
        String role = roleValue.trim().toLowerCase(Locale.US);
        if (role.equals("user") || role.equals("pelanggan") || role.equals("costumer") || role.equals("customer")) return "customer";
        if (role.equals("driver") || role.equals("kurir") || role.equals("ojek") || role.equals("rider")) return "driver";
        if (role.equals("merchant") || role.equals("merchen") || role.equals("resto") || role.equals("restaurant") || role.equals("penjual")) return "merchant";
        if (role.equals("admin") || role.equals("administrator") || role.equals("owner") || role.equals("superadmin")) return "admin";
        if (role.equals("wisata") || role.equals("wisataowner") || role.equals("wisata_owner") || role.equals("owner_wisata")) return "wisata";
        return role;
    }

    static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) {
            String clean = safe(value).trim();
            if (!clean.isEmpty() && !clean.equalsIgnoreCase("null") && !clean.equalsIgnoreCase("undefined")) return clean;
        }
        return "";
    }

    static String safe(String value) { return value == null ? "" : value; }
}
