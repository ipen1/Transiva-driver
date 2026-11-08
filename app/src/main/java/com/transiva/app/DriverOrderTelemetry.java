package com.transiva.app;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import com.transiva.app.driver.domain.DriverOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;

/** Driver diagnostics are isolated from the order action and never transmit credentials in receipts. */
public final class DriverOrderTelemetry {
    private static final LinkedHashSet<String> SEEN = new LinkedHashSet<>();
    private static final int LIMIT = 256;

    private DriverOrderTelemetry() {}

    static void push(Context context, Map<String, String> data, String event) {
        if (data == null) return;
        String id = data.get("order_db_id");
        if (id == null || id.isEmpty()) id = data.get("order_id");
        offer(context, data.get("source"), id, data.get("action_token"), event,
                -1, PushExpiryPolicy.remainingMillis(data, System.currentTimeMillis(), false));
    }

    static void watchVisibility(android.view.View card, DriverOrder offer, java.util.function.LongSupplier deadline) {
        if (offer.raw.optString("offer_action_token").isEmpty()) return;
        card.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                long remaining = deadline.getAsLong();
                if (!card.isAttachedToWindow() || remaining <= 0L) {
                    card.getViewTreeObserver().removeOnPreDrawListener(this);
                } else if (card.hasWindowFocus() && card.isShown()
                        && card.getGlobalVisibleRect(new android.graphics.Rect())) {
                    card.getViewTreeObserver().removeOnPreDrawListener(this);
                    DriverOrderTelemetry.shown(card.getContext(), offer, remaining);
                }
                return true;
            }
        });
    }
    static void shown(Context context, DriverOrder order, long remaining) {
        offer(context, order.source, order.id, order.raw.optString("offer_action_token"),
                "offer_shown", -1, remaining);
    }

    public static void offer(Context context, String source, String id, String token,
                             String event, long elapsed, long remaining) {
        if (token == null || token.isEmpty()) return;
        try {
            record(context, source, id, hash(token), event, elapsed, remaining);
        } catch (Exception ignored) {}
    }

    static void cancellation(Context context, String source, String id, String operation,
                             long elapsed, String tapContext, long tappedAt) {
        try {
            record(context, source, id, operation, "cancel_tap", -1, -1, tapContext, tappedAt);
            record(context, source, id, operation, "cancel_app_ack", elapsed, -1);
        } catch (Exception ignored) {}
    }

    private static void record(Context context, String source, String id, String key,
                               String event, long elapsed, long remaining) throws Exception {
        record(context, source, id, key, event, elapsed, remaining, deviceContext(context), System.currentTimeMillis());
    }

    private static void record(Context context, String source, String id, String key,
                               String event, long elapsed, long remaining, String device, long recorded) throws Exception {
        if (context == null || id == null || !id.matches("[0-9]{1,18}")
                || !("orders".equals(source) || "pickup_orders".equals(source))) return;
        String owner = NavigationSessionStore.identity(context);
        if (owner.isEmpty()) return;
        String signature = hash(owner + "|" + source + "|" + id + "|" + key + "|" + event);
        synchronized (DriverOrderTelemetry.class) {
            if (!SEEN.add(signature)) return;
            if (SEEN.size() > LIMIT) SEEN.remove(SEEN.iterator().next());
        }

        try {
            if (!"cancel_tap".equals(event)) {
                local(context, event + " · #" + id + " · " + device
                        + (remaining >= 0 ? " · sisa " + remaining + " ms" : "")
                        + (elapsed >= 0 ? " · proses " + elapsed + " ms" : ""));
            }
            Data data = new Data.Builder().putString("owner", owner).putString("source", source)
                    .putString("order_id", id).putString("key", key).putString("event", event)
                    .putString("device_context", device).putLong("elapsed_ms", elapsed)
                    .putLong("remaining_ms", remaining).putLong("recorded", recorded).build();
            OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(DriverOrderTelemetryWorker.class)
                    .setInputData(data)
                    .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build();
            WorkManager.getInstance(context).enqueueUniqueWork("driver-order-" + signature,
                    ExistingWorkPolicy.KEEP, work);
        } catch (RuntimeException ignored) {
            synchronized (DriverOrderTelemetry.class) { SEEN.remove(signature); }
        }
    }

    public static String deviceContext(Context context) {
        String network = "offline";
        boolean locked = false;
        try {
            ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
            NetworkCapabilities caps = manager == null ? null : manager.getNetworkCapabilities(manager.getActiveNetwork());
            if (caps != null) {
                network = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "wifi"
                        : caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "mobile" : "other";
            }
            KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
            locked = keyguard != null && keyguard.isKeyguardLocked();
        } catch (RuntimeException ignored) {}
        ActivityManager.RunningAppProcessInfo app = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(app);
        return network + "." + (locked ? "locked" : "open") + "."
                + (app.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ? "front" : "back");
    }

    public static synchronized void local(Context context, String event) {
        String owner = NavigationSessionStore.identity(context);
        if (owner.isEmpty()) return;
        android.content.SharedPreferences prefs = context.getSharedPreferences("driver_order_timeline", 0);
        String old = owner.equals(prefs.getString("owner", "")) ? prefs.getString("events", "") : "";
        String now = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        String[] lines = (now + " · " + event + "\n" + old).split("\n");
        StringBuilder recent = new StringBuilder();
        for (int i = 0; i < Math.min(35, lines.length); i++) recent.append(lines[i]).append('\n');
        prefs.edit().putString("owner", owner).putString("events", recent.toString()).apply();
    }

    static String recent(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences("driver_order_timeline", 0);
        return NavigationSessionStore.identity(context).equals(prefs.getString("owner", ""))
                ? prefs.getString("events", "") : "";
    }

    private static String hash(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >>> 4) & 15, 16));
            hex.append(Character.forDigit(b & 15, 16));
        }
        return hex.toString();
    }
}
