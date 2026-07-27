package com.transiva.app;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

public class TransivaNotificationActionReceiver extends BroadcastReceiver {

    private static final String TAG = "TRANSIVA_ACTION";
    private static final String PREF_NAME = "transiva";
    private static final int DEFAULT_NOTIFICATION_ID = 1001;
    private static final int TIMEOUT_MS = 20000;
    private static final String DEFAULT_ACTION_ENDPOINT =
            "https://transiva.my.id/server/notification_action.php";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;

        int notificationId = intent.getIntExtra("notification_id", DEFAULT_NOTIFICATION_ID);
        cancelNotification(context, notificationId);

        String rawActionName = intent.getAction() == null ? "" : intent.getAction();
        if (rawActionName.contains("NOTIFICATION_DISMISSED")) return;

        final PendingResult pendingResult = goAsync();

        new Thread(() -> {
            try {
                handleAction(context.getApplicationContext(), intent);
            } catch (Exception e) {
                Log.e(TAG, "Gagal proses tombol notifikasi", e);
                openFallbackIfAccept(context.getApplicationContext(), intent);
            } finally {
                try {
                    pendingResult.finish();
                } catch (Exception ignored) {}
            }
        }).start();
    }

    private void handleAction(Context context, Intent intent) throws Exception {
        String orderDbId = firstNotEmpty(
                getExtra(intent, "order_db_id"),
                getExtra(intent, "order_id"),
                getExtra(intent, "id")
        );
        String action = firstNotEmpty(getExtra(intent, "action"), "driver_accept");
        String endpoint = firstNotEmpty(getExtra(intent, "action_endpoint"), DEFAULT_ACTION_ENDPOINT);
        String token = getExtra(intent, "action_token");
        String actor = firstNotEmpty(
                getExtra(intent, "actor"),
                getExtra(intent, "username"),
                getExtra(intent, "offered_driver"),
                getExtra(intent, "driver"),
                getSavedUsername(context)
        );
        String driverType = firstNotEmpty(getExtra(intent, "driver_type"), "bike");

        if (orderDbId.isEmpty() || token.isEmpty()) {
            if ("driver_accept".equals(action)) openDriverTripFallback(context, orderDbId, driverType);
            return;
        }

        JSONObject payload = new JSONObject();
        payload.put("action", action);
        payload.put("order_db_id", orderDbId);
        payload.put("order_id", orderDbId);
        payload.put("id", orderDbId);
        payload.put("actor", actor);
        payload.put("username", actor);
        payload.put("driver", actor);
        payload.put("offered_driver", actor);
        payload.put("driver_type", driverType);
        payload.put("action_token", token);

        JSONObject result = postJson(endpoint, payload);

        if ("driver_accept".equals(action)) {
            saveAcceptedOrder(context, result, orderDbId, driverType);
            openDriverTrip(context, result, orderDbId, driverType);
        }
    }

    private JSONObject postJson(String endpoint, JSONObject payload) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(endpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setDoInput(true);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Cache-Control", "no-store");
            conn.setRequestProperty("X-Transiva-App", "Android-Notification-Action");

            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(conn.getOutputStream(), "UTF-8")
            );
            writer.write(payload.toString());
            writer.flush();
            writer.close();

            int status = conn.getResponseCode();
            InputStream stream = status >= 200 && status < 400 ? conn.getInputStream() : conn.getErrorStream();
            String raw = readStream(stream);
            if (raw == null || raw.trim().isEmpty()) return new JSONObject();
            String clean = raw.trim();
            if (!clean.startsWith("{")) return new JSONObject();
            JSONObject result = new JSONObject(clean);
            result.put("http_status", status);
            return result;
        } finally {
            try {
                if (conn != null) conn.disconnect();
            } catch (Exception ignored) {}
        }
    }

    private void saveAcceptedOrder(Context context, JSONObject result, String orderDbId, String driverType) {
        try {
            JSONObject order = extractOrder(result);
            if (order == null) {
                order = new JSONObject();
                order.put("id", orderDbId);
                order.put("order_id", orderDbId);
                order.put("status", "taken");
                order.put("driver_type", driverType);
            }

            String kind = firstNotEmpty(
                    order.optString("order_kind", ""),
                    order.optString("source_table", ""),
                    order.optString("type", ""),
                    "order"
            ).toLowerCase(Locale.US);
            kind = kind.contains("pickup") ? "pickup" : "order";

            SharedPreferences.Editor editor = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit();
            editor.putString("driver_active_order_json", order.toString());
            editor.putString("active_order_json", order.toString());
            editor.putString("activeOrder", order.toString());
            editor.putString("driver_active_order_id", firstNotEmpty(order.optString("id", ""), orderDbId));
            editor.putString("driver_active_order_kind", kind);
            editor.putString("driver_active_order_status", firstNotEmpty(order.optString("status", ""), "taken"));
            editor.apply();
        } catch (Exception e) {
            Log.e(TAG, "Gagal simpan order aktif", e);
        }
    }

    private void openDriverTrip(Context context, JSONObject result, String orderDbId, String driverType) {
        try {
            JSONObject order = extractOrder(result);
            Intent openIntent = new Intent(context, DriverTripActivity.class);
            openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            openIntent.putExtra("from_notification_action", "1");
            openIntent.putExtra("order_id", orderDbId);
            openIntent.putExtra("order_db_id", orderDbId);
            openIntent.putExtra("driver_type", driverType);

            if (order != null) {
                String kind = firstNotEmpty(
                        order.optString("order_kind", ""),
                        order.optString("source_table", ""),
                        order.optString("type", ""),
                        "order"
                ).toLowerCase(Locale.US);
                kind = kind.contains("pickup") ? "pickup" : "order";
                openIntent.putExtra("order_json", order.toString());
                openIntent.putExtra("active_order_json", order.toString());
                openIntent.putExtra("order_kind", kind);
            } else {
                openIntent.putExtra("order_kind", "order");
            }

            new Handler(Looper.getMainLooper()).post(() -> context.startActivity(openIntent));
        } catch (Exception e) {
            Log.e(TAG, "Gagal buka DriverTripActivity", e);
        }
    }

    private void openFallbackIfAccept(Context context, Intent intent) {
        String action = firstNotEmpty(getExtra(intent, "action"), "");
        if ("driver_accept".equals(action)) {
            openDriverTripFallback(
                    context,
                    firstNotEmpty(getExtra(intent, "order_db_id"), getExtra(intent, "order_id"), getExtra(intent, "id")),
                    firstNotEmpty(getExtra(intent, "driver_type"), "bike")
            );
        }
    }

    private void openDriverTripFallback(Context context, String orderDbId, String driverType) {
        try {
            Intent openIntent = new Intent(context, DriverTripActivity.class);
            openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            openIntent.putExtra("from_notification_action", "1");
            openIntent.putExtra("order_id", orderDbId);
            openIntent.putExtra("order_db_id", orderDbId);
            openIntent.putExtra("order_kind", "order");
            openIntent.putExtra("driver_type", driverType);
            new Handler(Looper.getMainLooper()).post(() -> context.startActivity(openIntent));
        } catch (Exception e) {
            Log.e(TAG, "Fallback trip gagal", e);
        }
    }

    private JSONObject extractOrder(JSONObject result) {
        if (result == null) return null;
        try {
            if (result.has("order") && result.opt("order") instanceof JSONObject) {
                return result.getJSONObject("order");
            }
            if (result.has("data") && result.opt("data") instanceof JSONObject) {
                JSONObject data = result.getJSONObject("data");
                if (data.has("order") && data.opt("order") instanceof JSONObject) {
                    return data.getJSONObject("order");
                }
                if (data.has("id") || data.has("order_id")) return data;
            }
            if (result.has("id") || result.has("order_id")) return result;
        } catch (Exception ignored) {}
        return null;
    }

    private void cancelNotification(Context context, int notificationId) {
        try {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.cancel(notificationId);
        } catch (Exception ignored) {}
    }

    private String readStream(InputStream stream) {
        try {
            if (stream == null) return "";
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) builder.append(line);
            reader.close();
            return builder.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String getSavedUsername(Context context) {
        try {
            SessionManager s = new SessionManager(context);
            return firstNotEmpty(s.getUsername(), s.getName(), "");
        } catch (Exception ignored) {}
        try {
            return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString("username", "");
        } catch (Exception ignored) {}
        return "";
    }

    private String getExtra(Intent intent, String key) {
        try {
            String value = intent.getStringExtra(key);
            return value == null ? "" : value.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private String firstNotEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }
}
