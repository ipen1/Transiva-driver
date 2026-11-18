package com.transiva.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;

/** Preview receipts are independent of chat-room read receipts and scoped to login and order. */
final class TripChatDisplayStore {
    private static String key(Context context, JSONObject order) {
        String raw = NavigationSessionStore.identity(context) + "|" + NavigationTargetPolicy.identity(order);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte value : hash) out.append(String.format(java.util.Locale.US, "%02x", value & 255));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static LinkedHashSet<Long> read(SharedPreferences prefs, String key) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        try {
            JSONArray saved = new JSONArray(prefs.getString(key, "[]"));
            for (int i = 0; i < saved.length(); i++) ids.add(saved.optLong(i));
        } catch (org.json.JSONException ignored) {}
        return ids;
    }

    static synchronized boolean shown(Context context, JSONObject order, long id) {
        SharedPreferences prefs = context.getSharedPreferences("trip_chat_preview_receipts_v1", Context.MODE_PRIVATE);
        return read(prefs, key(context, order)).contains(id);
    }

    static synchronized boolean claim(Context context, JSONObject order, long id) {
        if (id <= 0) return false;
        SharedPreferences prefs = context.getSharedPreferences("trip_chat_preview_receipts_v1", Context.MODE_PRIVATE);
        String key = key(context, order);
        LinkedHashSet<Long> ids = read(prefs, key);
        if (!ids.add(id)) return false;
        // IDs are ordered by display, not server watermark, so delayed and out-of-order
        // messages cannot be hidden merely because a newer notification arrived first.
        while (ids.size() > 1024) ids.remove(ids.iterator().next());
        JSONArray saved = new JSONArray();
        for (long value : ids) saved.put(value);
        return prefs.edit().putString(key, saved.toString()).commit();
    }

    static long duration(String text) {
        int length = text.codePointCount(0, text.length());
        return Math.max(2200L, 1400L + length * 65L);
    }
}
