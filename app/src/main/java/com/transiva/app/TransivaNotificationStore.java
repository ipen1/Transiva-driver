package com.transiva.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

public final class TransivaNotificationStore {

    private static final String PREF = "transiva_notification_center";
    private static final String KEY_ITEMS = "items";
    private static final String KEY_UNREAD = "unread_count";
    private static final int MAX_ITEMS = 50;

    private TransivaNotificationStore() {
    }

    public static synchronized void add(
            Context context,
            String type,
            String title,
            String body,
            String orderId,
            String roomId,
            String url
    ) {
        if (context == null) return;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            JSONArray oldItems = new JSONArray(prefs.getString(KEY_ITEMS, "[]"));
            JSONArray newItems = new JSONArray();

            JSONObject item = new JSONObject();
            item.put("type", safe(type, "general"));
            item.put("title", safe(title, "Transiva"));
            item.put("body", safe(body, "Notifikasi baru"));
            item.put("order_id", safe(orderId, ""));
            item.put("room_id", safe(roomId, ""));
            item.put("url", safe(url, ""));
            item.put("time", System.currentTimeMillis());
            newItems.put(item);

            for (int i = 0; i < oldItems.length() && newItems.length() < MAX_ITEMS; i++) {
                newItems.put(oldItems.optJSONObject(i));
            }

            prefs.edit()
                    .putString(KEY_ITEMS, newItems.toString())
                    .putInt(KEY_UNREAD, Math.min(99, prefs.getInt(KEY_UNREAD, 0) + 1))
                    .apply();
        } catch (Exception ignored) {
        }
    }

    public static JSONArray getItems(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            return new JSONArray(prefs.getString(KEY_ITEMS, "[]"));
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    public static int getUnreadCount(Context context) {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_UNREAD, 0);
    }

    public static void markAllRead(Context context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_UNREAD, 0)
                .apply();
    }

    public static void clear(Context context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_ITEMS)
                .putInt(KEY_UNREAD, 0)
                .apply();
    }

    private static String safe(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
