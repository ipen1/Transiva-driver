package com.transiva.app;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.text.TextUtils;

import androidx.core.app.NotificationCompat;
import androidx.core.app.Person;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
abstract class TransivaFirebaseServiceLayer2 extends TransivaFirebaseServiceLayer1 {


    /**
     * Android 14+ special-access gate. Pre-14 the manifest permission is sufficient.
     * Never use this helper for orders, chat, promos, wallet events, or broadcasts.
     */
    protected boolean canUseFullScreenCallIntent() {
        if (Build.VERSION.SDK_INT < 34) return true;
        try {
            NotificationManager manager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            return manager != null && manager.canUseFullScreenIntent();
        } catch (Throwable ignored) {
            return false;
        }
    }

    protected void wakeScreenForPriority(String type, boolean incomingCall) {
        type = first(type, "").toLowerCase();

        // Promotions, ordinary broadcasts and wallet updates should not wake a
        // sleeping phone. Wake only events that can require an immediate driver
        // response: calls, SOS/emergency, new/updated orders, and customer chat.
        boolean shouldWake = incomingCall
                || "driver_emergency".equals(type)
                || isOrder(type)
                || isChat(type);

        if (!shouldWake) return;

        long timeoutMs = incomingCall ? 12_000L
                : ("driver_emergency".equals(type) ? 10_000L
                : (isOrder(type) ? 8_000L : 5_000L));

        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;

            // ACQUIRE_CAUSES_WAKEUP is intentionally used only for urgent FCM.
            // It is best-effort on newer Android versions/OEM power managers.
            @SuppressWarnings("deprecation")
            PowerManager.WakeLock wakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                            | PowerManager.ACQUIRE_CAUSES_WAKEUP
                            | PowerManager.ON_AFTER_RELEASE,
                    "transiva:urgent_fcm_wake"
            );
            wakeLock.acquire(timeoutMs);
        } catch (Throwable ignored) {
        }
    }

    protected Intent buildOpenIntent(
            String type,
            String orderId,
            String roomId,
            String url,
            Map<String, String> data
    ) {
        if ("webrtc_call".equals(type)) {
            Intent intent = new Intent(this, WebRtcCallActivity.class);
            intent.putExtra("call_id", data != null ? first(data.get("call_id"), "") : "");
            intent.putExtra("order_id", orderId);
            intent.putExtra("source", data != null ? first(data.get("source"), "orders") : "orders");
            intent.putExtra("caller_name", data != null ? first(data.get("caller_name"), "Transiva") : "Transiva");
            intent.putExtra("incoming", data != null && "incoming_call".equalsIgnoreCase(first(data.get("event"), "")));
            return intent;
        }

        if ("driver_global_mention".equals(type)) {
            Intent intent = new Intent(this, DriverGlobalChatActivity.class);
            long messageId = 0L;
            try { messageId = Long.parseLong(data != null ? first(data.get("message_id"), "0") : "0"); } catch (Throwable ignored) { TransivaDiagnostics.error(this,"fcm","NON_FATAL_EXCEPTION",ignored); }
            intent.putExtra("jump_message_id", messageId);
            intent.putExtra("from_fcm", true);
            return intent;
        }

        if ("driver_greeting".equals(type)) {
            Intent intent = new Intent(this, DriverLocationActivity.class);
            intent.putExtra("from_fcm", true);
            if (data != null) {
                for (Map.Entry<String,String> entry : data.entrySet()) intent.putExtra(entry.getKey(), entry.getValue());
            }
            return intent;
        }

        if ("driver_emergency".equals(type)) {
            Intent intent = new Intent(this, DriverEmergencyActivity.class);
            if (data != null) {
                for (Map.Entry<String,String> entry : data.entrySet()) intent.putExtra(entry.getKey(), entry.getValue());
            }
            intent.putExtra("order_id", orderId);
            intent.putExtra("from_fcm", true);
            return intent;
        }

        if (isMerchantDriverChat(type, data)) {
            Intent intent = new Intent(this, DriverMerchantChatActivity.class);
            intent.putExtra("order_id", orderId);
            intent.putExtra("order_db_id", data != null ? first(data.get("order_db_id"), "") : "");
            intent.putExtra("merchant_name", data != null ? first(data.get("restaurant_name"), data.get("merchant_name"), "Merchant") : "Merchant");
            intent.putExtra("from_fcm", true);
            if (data != null) for (Map.Entry<String,String> entry : data.entrySet()) intent.putExtra(entry.getKey(), entry.getValue());
            return intent;
        }

        if (isChat(type)) {
            Intent intent = new Intent(this, DriverChatActivity.class);
            intent.putExtra("room_id", first(roomId, orderId));
            intent.putExtra("order_id", orderId);
            intent.putExtra("from_fcm", true);
            return intent;
        }

        if (isWallet(type)) {
            Intent intent = new Intent(this, DriverTopUpActivity.class);
            intent.putExtra("from_fcm", true);
            return intent;
        }

        Intent intent = new Intent(this, DriverDashboardActivity.class);
        intent.putExtra("order_id", orderId);
        intent.putExtra("from_fcm", true);
        intent.putExtra("notif_type", type);
        if (data != null) {
            for (Map.Entry<String,String> entry : data.entrySet()) {
                intent.putExtra(entry.getKey(), entry.getValue());
            }
        }
        return intent;
    }

    protected void createChannels() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }

        createNewOrderChannel();

        createChannel(
                CH_ORDER,
                "Update Order Transiva",
                "Pembaruan status order yang sedang berjalan",
                NotificationManager.IMPORTANCE_HIGH
        );

        createChannel(
                CH_OPPORTUNITY,
                "Peluang Order di Sekitar",
                "Ajakan online saat permintaan tinggi atau driver online sedang sibuk",
                NotificationManager.IMPORTANCE_HIGH
        );

        createChannel(
                CH_WALLET,
                "Financial Transiva",
                "Saldo, deposit, dan penarikan",
                NotificationManager.IMPORTANCE_HIGH
        );

        createChannel(
                CH_CALL,
                "Panggilan Transiva",
                "Panggilan suara Driver dan Customer",
                NotificationManager.IMPORTANCE_HIGH
        );

        createChannel(
                CH_CHAT,
                "Chat Transiva",
                "Pesan customer dan driver",
                NotificationManager.IMPORTANCE_HIGH
        );

        createChannel(
                CH_PROMO,
                "Promo Transiva",
                "Promo dan penawaran Transiva",
                NotificationManager.IMPORTANCE_HIGH
        );

        createChannel(
                CH_BROADCAST,
                "Broadcast Admin",
                "Pengumuman admin Transiva",
                NotificationManager.IMPORTANCE_HIGH
        );

        createChannel(
                CH_GENERAL,
                "Transiva",
                "Notifikasi umum",
                NotificationManager.IMPORTANCE_DEFAULT
        );
    }


    protected void createNewOrderChannel() {
        if (Build.VERSION.SDK_INT < 26) return;

        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        NotificationChannel existing = manager.getNotificationChannel(CH_NEW_ORDER);
        if (existing != null) return;

        NotificationChannel channel = new NotificationChannel(
                CH_NEW_ORDER,
                "Order Baru Transiva",
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription("Suara khusus ketika driver menerima penawaran order baru");
        channel.enableVibration(true);
        channel.setVibrationPattern(new long[]{0L, 350L, 180L, 350L, 180L, 650L});
        channel.enableLights(true);
        channel.setLightColor(0xFF0B7CFF);
        channel.setSound(
                orderSoundUri(),
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
        );
        manager.createNotificationChannel(channel);
    }

    protected Uri orderSoundUri() {
        return Uri.parse(
                "android.resource://" + getPackageName() + "/" + R.raw.order_new
        );
    }

    protected boolean isNewIncomingOrder(String type, Map<String, String> data) {
        String t = first(type, "").toLowerCase();
        String event = data == null ? "" : first(data.get("event"), "").toLowerCase();
        String screen = data == null ? "" : first(data.get("screen"), "").toLowerCase();

        if ("new_order".equals(event) || "order_new".equals(event)) return true;
        if (t.contains("new_order") || t.contains("order_new")) return true;

        // Backend Transiva saat ini memakai type=transiva_order + screen=driver_order.
        // Jadikan fallback hanya jika event kosong agar update status tidak ikut berbunyi.
        return event.isEmpty()
                && "transiva_order".equals(t)
                && (screen.isEmpty() || "driver_order".equals(screen));
    }

    protected void createChannel(
            String id,
            String name,
            String description,
            int importance
    ) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }

        NotificationManager manager =
                (NotificationManager)
                        getSystemService(
                                Context.NOTIFICATION_SERVICE
                        );

        if (manager == null) {
            return;
        }

        NotificationChannel existing =
                manager.getNotificationChannel(id);

        if (existing != null) {
            // Importance channel tidak bisa dinaikkan setelah dibuat.
            // Hapus channel promo lama agar dibuat ulang HIGH.
            if (
                    CH_PROMO.equals(id)
                            && existing.getImportance()
                            < NotificationManager.IMPORTANCE_HIGH
            ) {
                manager.deleteNotificationChannel(id);
            } else {
                return;
            }
        }

        NotificationChannel channel =
                new NotificationChannel(
                        id,
                        name,
                        importance
                );

        channel.setDescription(description);
        channel.enableVibration(DriverAppSettings.isVibrationEnabled(this));
        channel.enableLights(true);

        if (CH_CALL.equals(id)) {
            // Call ringing is owned by IncomingCallAlertManager. Keeping this channel
            // silent prevents duplicate ringtone/vibration while still allowing heads-up UI.
            channel.setSound(null, null);
            channel.enableVibration(false);
        } else {
            channel.setSound(
                    android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
            );
        }

        manager.createNotificationChannel(channel);
    }

    protected boolean isDriverRealtimeType(String type) {
        String t = type == null ? "" : type.toLowerCase();
        return t.contains("order")
                || t.contains("offer")
                || t.contains("trip")
                || t.contains("wallet")
                || t.contains("deposit")
                || t.contains("withdraw")
                || t.contains("dispatch");
    }

    protected String channelForType(String type) {
        type = first(type, "general").toLowerCase();

        if ("webrtc_call".equals(type)) {
            return CH_CALL;
        }
        if ("driver_emergency".equals(type)) return CH_ORDER;

        if (isChat(type)) {
            return CH_CHAT;
        }

        if (isWallet(type)) {
            return CH_WALLET;
        }

        if ("driver_opportunity".equals(type)) {
            return CH_OPPORTUNITY;
        }

        if (isOrder(type)) {
            return CH_ORDER;
        }

        if (type.contains("promo")) {
            return CH_PROMO;
        }

        if (
                type.contains("broadcast")
                        || type.contains("admin")
        ) {
            return CH_BROADCAST;
        }

        return CH_GENERAL;
    }

    protected int priorityForType(String type) {
        type = first(type, "").toLowerCase();

        if (
                "webrtc_call".equals(type)
                        || "driver_emergency".equals(type)
                        || isChat(type)
                        || isOrder(type)
                        || "driver_opportunity".equals(type)
                        || isWallet(type)
                        || type.contains("broadcast")
                        || type.contains("promo")
        ) {
            return NotificationCompat.PRIORITY_HIGH;
        }

        return NotificationCompat.PRIORITY_DEFAULT;
    }
}
