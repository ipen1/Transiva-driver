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

public class TransivaFirebaseService extends FirebaseMessagingService {

    public static final String ACTION_DRIVER_DATA_CHANGED =
            "com.transiva.app.ACTION_DRIVER_DATA_CHANGED";

    public static final String BASE_URL =
            "https://transiva.my.id/server/";

    private static final String CH_NEW_ORDER =
            "transiva_new_order_channel_v3";
    private static final String CH_ORDER =
            "transiva_order_channel";
    private static final String CH_OPPORTUNITY =
            "transiva_driver_opportunity_v1";
    private static final String CH_WALLET =
            "transiva_wallet_channel";
    private static final String CH_CHAT =
            "transiva_chat_channel_v2";
    private static final String CH_CALL =
            "transiva_call_channel_v5";
    private static final String CH_PROMO =
            "transiva_promo_channel";
    private static final String CH_BROADCAST =
            "transiva_broadcast_channel";
    private static final String CH_GENERAL =
            "transiva_general_channel";

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
    }

    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);

        String cleanToken = safe(token);

        if (cleanToken.isEmpty()) {
            return;
        }

        saveTokenLocal(cleanToken);
        sendTokenToServer(cleanToken);
    }

    @Override
    public void onMessageReceived(
            RemoteMessage remoteMessage
    ) {
        super.onMessageReceived(remoteMessage);
        createChannels();

        Map<String, String> data =
                remoteMessage.getData();

        if (DriverFcmDeduplicator.isDuplicate(this, remoteMessage.getMessageId(), data)) {
            return;
        }

        if (data == null || data.isEmpty()) {
            String title =
                    remoteMessage.getNotification() != null
                            ? remoteMessage
                                    .getNotification()
                                    .getTitle()
                            : "Transiva";

            String body =
                    remoteMessage.getNotification() != null
                            ? remoteMessage
                                    .getNotification()
                                    .getBody()
                            : "Notifikasi baru";

            showNotification(
                    "general",
                    first(title, "Transiva"),
                    first(body, "Notifikasi baru"),
                    "",
                    "",
                    "",
                    data
            );
            return;
        }

        String type = first(
                data.get("type"),
                data.get("notif_type"),
                data.get("category"),
                "general"
        ).toLowerCase();

        if ("driver_global_mention".equals(type)) {
            try {
                long mentionId = Long.parseLong(first(data.get("message_id"), "0"));
                DriverGlobalChatStore.onMentionPush(this, mentionId);
            } catch (Throwable t) {
                TransivaDiagnostics.error(this,"fcm","MENTION_PUSH_PARSE_FAILED",t);
                DriverGlobalChatStore.onMentionPush(this, 0L);
            }
        }

        if ("webrtc_call".equals(type)) {
            final String event = first(data.get("event"), "").toLowerCase();
            final String callId = first(data.get("call_id"), "");

            // Only a genuinely new incoming call is allowed to open the call UI.
            // All state/signaling events are consumed here so they cannot launch
            // WebRtcCallActivity again through a PendingIntent/full-screen intent.
            if ("call_accepted".equals(event) || "accepted".equals(event)) {
                IncomingCallAlertManager.stop(callId);
                sendCallState(callId, "accepted");
                cancelCallNotification(callId);
                return;
            }

            if ("call_ended".equals(event)
                    || "call_rejected".equals(event)
                    || "call_missed".equals(event)
                    || "ended".equals(event)
                    || "rejected".equals(event)
                    || "missed".equals(event)) {
                String status;
                if (event.contains("reject")) status = "rejected";
                else if (event.contains("miss")) status = "missed";
                else status = "ended";
                IncomingCallAlertManager.stop(callId);
                sendCallState(callId, status);
                cancelCallNotification(callId);
                return;
            }

            // SDP/candidate/ringing/update pushes are not UI launches. The active
            // call Activity already polls signaling from the backend.
            if (!"incoming_call".equals(event)) {
                return;
            }
        }


        if ("security_policy_changed".equals(type)
                || "driver_security_policy_changed".equals(type)) {
            // FCM hanya trigger. Source of truth tetap database/server.
            DriverSecurityPolicy.invalidate(this);
            TransivaDriverApplication.onSecurityPolicyChanged();
            return;
        }

        if (type.equals("force_logout")
                || type.equals("device_reset")
                || type.equals("device_banned")
                || "1".equals(data.get("force_logout"))) {
            String reason = first(
                    data.get("reason"),
                    data.get("code"),
                    type.equals("device_banned") ? "DEVICE_BANNED" : "DEVICE_RESET"
            );
            ForceLogoutManager.execute(this, reason);
            return;
        }

        String title = first(
                data.get("title"),
                "Transiva"
        );

        String body = first(
                data.get("body"),
                data.get("message"),
                "Notifikasi baru"
        );

        String orderId = first(
                data.get("order_id"),
                data.get("id_order"),
                data.get("orderId"),
                ""
        );

        String roomId = first(
                data.get("room_id"),
                data.get("chat_room"),
                ""
        );

        String url = first(
                data.get("url"),
                data.get("link"),
                ""
        );

        // Persist customer-chat unread state so navigation can recover the indicator
        // even if it was briefly covered by the call screen/backgrounded. Merchant/global
        // chat must not light the customer-message action.
        if (isChat(type) && !isMerchantDriverChat(type, data) && !orderId.isEmpty()) {
            DriverMessageUnreadRepository.markUnread(this, orderId, roomId);
        }

        // FCM adalah jalur utama real-time. Saat dashboard sedang terbuka,
        // kirim sinyal lokal agar data langsung refresh tanpa menunggu polling berikutnya.
        if (isDriverRealtimeType(type) || isChat(type)) {
            try {
                Intent changed = new Intent(ACTION_DRIVER_DATA_CHANGED);
                changed.setPackage(getPackageName());
                changed.putExtra("type", type);
                changed.putExtra("order_id", orderId);
                changed.putExtra("room_id", roomId);
                sendBroadcast(changed);
            } catch (Throwable ignored) {
                // Push tetap diproses walau refresh lokal gagal.
            }
        }

        publishBubbleEvent(type, title, body, orderId, roomId, data);

        showNotification(
                type,
                title,
                body,
                orderId,
                roomId,
                url,
                data
        );
    }

    private void publishBubbleEvent(String type, String title, String body, String orderId, String roomId, Map<String, String> data) {
        try {
            if (!DriverBubbleController.enabled(this) || !DriverBubbleController.canOverlay(this)) return;
            boolean newOrder = isNewIncomingOrder(type, data);
            boolean mention = "driver_global_mention".equalsIgnoreCase(first(type, ""));
            boolean customerChat = isChat(type) && !isMerchantDriverChat(type, data) && !mention;
            if (!newOrder && !mention && !customerChat) return;

            long mentionId = 0L;
            if (mention) {
                try { mentionId = Long.parseLong(data != null ? first(data.get("message_id"), "0") : "0"); } catch (Throwable ignored) {}
            }

            String text;
            if (newOrder) {
                text = "Orderan baru diterima";
            } else if (mention) {
                String sender = data != null ? first(data.get("sender_name"), data.get("name"), "") : "";
                text = sender.isEmpty() ? first(body, "Nama kamu disebut di chat driver") : sender + " menyebut nama kamu";
            } else {
                String sender = data != null ? first(data.get("sender_name"), data.get("customer_name"), data.get("name"), "Customer") : "Customer";
                String msg = first(body, data != null ? first(data.get("message"), "Pesan baru") : "Pesan baru");
                text = sender + ": " + msg;
            }
            DriverBubbleOverlayService.publish(this, first(type, "general").toLowerCase(), text, orderId, roomId, mentionId, newOrder);
        } catch (Throwable ignored) {}
    }

    private void sendCallState(String callId, String status) {
        if (callId == null || callId.trim().isEmpty()) return;
        try {
            Intent state = new Intent(WebRtcCallActivity.ACTION_CALL_STATE);
            state.setPackage(getPackageName());
            state.putExtra(WebRtcCallActivity.EXTRA_CALL_ID, callId);
            state.putExtra(WebRtcCallActivity.EXTRA_CALL_STATUS, status);
            sendBroadcast(state);
        } catch (Throwable t) {
            TransivaDiagnostics.error(this,"fcm","CALL_STATE_BROADCAST_FAILED",t);
        }
    }

    private void cancelCallNotification(String callId) {
        if (callId == null || callId.trim().isEmpty()) return;
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(Math.abs(("webrtc_call|" + callId).hashCode()));
        } catch (Throwable ignored) { TransivaDiagnostics.error(this,"fcm","NON_FATAL_EXCEPTION",ignored); }
    }

    private void showNotification(
            String type,
            String title,
            String body,
            String orderId,
            String roomId,
            String url,
            Map<String, String> data
    ) {
        TransivaNotificationStore.add(
                this,
                type,
                title,
                body,
                orderId,
                roomId,
                url
        );

        boolean newIncomingOrder = isNewIncomingOrder(type, data);
        String channelId = newIncomingOrder ? CH_NEW_ORDER : channelForType(type);

        Intent intent = buildOpenIntent(
                type,
                orderId,
                roomId,
                url,
                data
        );

        intent.addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
        );

        String callNotificationId =
                data != null ? first(data.get("call_id"), "") : "";

        int requestCode;
        if ("webrtc_call".equals(type) && !callNotificationId.isEmpty()) {
            requestCode = Math.abs(("webrtc_call|" + callNotificationId).hashCode());
        } else {
            requestCode = Math.abs(
                    (
                            type
                                    + "|"
                                    + first(orderId, "")
                                    + "|"
                                    + first(roomId, "")
                                    + "|"
                                    + System.currentTimeMillis()
                    ).hashCode()
            );
        }

        PendingIntent pendingIntent =
                PendingIntent.getActivity(
                        this,
                        requestCode,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT
                                | PendingIntent.FLAG_IMMUTABLE
                );

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(
                        this,
                        channelId
                )
                        .setSmallIcon(getSmallIcon())
                        .setContentTitle(first(title, "Transiva"))
                        .setContentText(
                                first(body, "Notifikasi baru")
                        )
                        .setStyle(
                                new NotificationCompat.BigTextStyle()
                                        .bigText(
                                                first(
                                                        body,
                                                        "Notifikasi baru"
                                                )
                                        )
                        )
                        .setAutoCancel(true)
                        .setContentIntent(pendingIntent)
                        .setPriority(priorityForType(type))
                        .setCategory(categoryForType(type))
                        .setVisibility(
                                NotificationCompat.VISIBILITY_PUBLIC
                        );

        boolean incomingCallNotification = "webrtc_call".equals(type)
                && data != null
                && "incoming_call".equalsIgnoreCase(first(data.get("event"), ""));

        boolean merchantDriverChatNotification = isMerchantDriverChat(type, data);

        if (incomingCallNotification) {
            // Start the audible/vibration alert immediately from the incoming-call push.
            // This keeps ringing even when Android denies full-screen special access.
            IncomingCallAlertManager.start(this, callNotificationId);

            // Full-screen intent is reserved EXCLUSIVELY for a real incoming WebRTC call.
            // Android 14+ treats it as special app access. If unavailable, we gracefully
            // fall back to the same high-priority heads-up call notification.
            builder.setCategory(NotificationCompat.CATEGORY_CALL)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setOnlyAlertOnce(true)
                    .setTimeoutAfter(50_000L);

            // Accept is a user-initiated Activity launch and auto-answers once the call UI opens.
            Intent acceptIntent = buildOpenIntent(type, orderId, roomId, url, data);
            acceptIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            acceptIntent.putExtra("auto_accept", true);
            PendingIntent acceptPendingIntent = PendingIntent.getActivity(
                    this, requestCode + 1, acceptIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Intent rejectIntent = new Intent(this, IncomingCallActionReceiver.class);
            rejectIntent.setAction(IncomingCallActionReceiver.ACTION_REJECT);
            rejectIntent.putExtra("call_id", callNotificationId);
            rejectIntent.putExtra("notification_id", requestCode);
            PendingIntent rejectPendingIntent = PendingIntent.getBroadcast(
                    this, requestCode + 2, rejectIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            builder.addAction(0, "Tolak", rejectPendingIntent)
                    .addAction(0, "Terima", acceptPendingIntent);

            if (canUseFullScreenCallIntent()) {
                builder.setFullScreenIntent(pendingIntent, true);
            }
        } else {
            if (newIncomingOrder) {
                // Android 8+ mengambil suara dari NotificationChannel.
                // Android 7 dan lebih lama mengambil suara langsung dari Builder.
                if (Build.VERSION.SDK_INT < 26) {
                    builder.setSound(orderSoundUri())
                            .setVibrate(new long[]{0L, 350L, 180L, 350L, 180L, 650L})
                            .setLights(0xFF0B7CFF, 700, 700);
                }
                builder.setPriority(NotificationCompat.PRIORITY_MAX)
                        .setCategory(NotificationCompat.CATEGORY_STATUS);
            } else {
                builder.setDefaults(
                        NotificationCompat.DEFAULT_SOUND
                                | NotificationCompat.DEFAULT_VIBRATE
                                | NotificationCompat.DEFAULT_LIGHTS
                );
            }
            if (merchantDriverChatNotification) {
                // Chat may be high priority, but it must remain a normal heads-up
                // notification. Full-screen intent is reserved for incoming calls.
                builder.setCategory(NotificationCompat.CATEGORY_MESSAGE)
                        .setPriority(NotificationCompat.PRIORITY_HIGH);
            }
        }

        // For operationally urgent pushes, briefly wake the display so the
        // heads-up notification can be noticed. Full-screen UI remains reserved
        // for genuine incoming calls, matching modern Android restrictions.
        wakeScreenForPriority(type, incomingCallNotification);

        if (
                Build.VERSION.SDK_INT >= 33
                        && ContextCompat.checkSelfPermission(
                                this,
                                Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
        ) {
            return;
        }

        NotificationManagerCompat
                .from(this)
                .notify(requestCode, builder.build());

        // Do not start an Activity directly from background FCM. The notification
        // is the sole entry point while backgrounded, so calls stay compliant with
        // Android background-start and Google Play full-screen-intent restrictions.
    }


    /**
     * Android 14+ special-access gate. Pre-14 the manifest permission is sufficient.
     * Never use this helper for orders, chat, promos, wallet events, or broadcasts.
     */
    private boolean canUseFullScreenCallIntent() {
        if (Build.VERSION.SDK_INT < 34) return true;
        try {
            NotificationManager manager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            return manager != null && manager.canUseFullScreenIntent();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void wakeScreenForPriority(String type, boolean incomingCall) {
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

    private Intent buildOpenIntent(
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

    private void createChannels() {
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


    private void createNewOrderChannel() {
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

    private Uri orderSoundUri() {
        return Uri.parse(
                "android.resource://" + getPackageName() + "/" + R.raw.order_new
        );
    }

    private boolean isNewIncomingOrder(String type, Map<String, String> data) {
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

    private void createChannel(
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

    private boolean isDriverRealtimeType(String type) {
        String t = type == null ? "" : type.toLowerCase();
        return t.contains("order")
                || t.contains("offer")
                || t.contains("trip")
                || t.contains("wallet")
                || t.contains("deposit")
                || t.contains("withdraw")
                || t.contains("dispatch");
    }

    private String channelForType(String type) {
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

    private int priorityForType(String type) {
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

    private String categoryForType(String type) {
        type = first(type, "").toLowerCase();

        if ("webrtc_call".equals(type)) {
            return NotificationCompat.CATEGORY_CALL;
        }

        if (isChat(type)) {
            return NotificationCompat.CATEGORY_MESSAGE;
        }

        if (isOrder(type) || isWallet(type) || "driver_opportunity".equals(type)) {
            return NotificationCompat.CATEGORY_STATUS;
        }

        if (type.contains("promo")) {
            return NotificationCompat.CATEGORY_PROMO;
        }

        return NotificationCompat.CATEGORY_MESSAGE;
    }

    private boolean isMerchantDriverChat(String type, Map<String, String> data) {
        String signal = first(type, "").toLowerCase();
        if (data != null) signal += " " + first(data.get("event"), "").toLowerCase() + " " + first(data.get("screen"), "").toLowerCase();
        return signal.contains("merchant_driver_chat") || signal.contains("driver_merchant_chat")
                || (signal.contains("chat") && signal.contains("merchant") && signal.contains("driver"));
    }

    private boolean isChat(String type) {
        type = first(type, "").toLowerCase();

        return type.contains("chat") || "driver_global_mention".equals(type)
                || type.contains("message");
    }

    private boolean isOrder(String type) {
        type = first(type, "").toLowerCase();

        return type.contains("order")
                || type.contains("ride")
                || type.contains("food")
                || type.contains("pickup")
                || type.contains("wisata")
                || type.contains("merchant");
    }

    private boolean isWallet(String type) {
        type = first(type, "").toLowerCase();

        return type.contains("wallet")
                || type.contains("financial")
                || type.contains("deposit")
                || type.contains("withdraw")
                || type.contains("saldo")
                || type.contains("balance");
    }

    private int getSmallIcon() {
        try {
            return getApplicationInfo().icon;
        } catch (Exception ignored) {
            return android.R.drawable.ic_dialog_info;
        }
    }

    private void saveTokenLocal(String token) {
        String cleanToken = safe(token);

        getSharedPreferences(
                "transiva_fcm",
                MODE_PRIVATE
        )
                .edit()
                .putString("fcm_token", cleanToken)
                .putLong(
                        "fcm_token_saved_at",
                        System.currentTimeMillis()
                )
                .apply();

        getSharedPreferences(
                "transiva_native_session",
                MODE_PRIVATE
        )
                .edit()
                .putString("fcm_token", cleanToken)
                .putLong(
                        "fcm_token_saved_at",
                        System.currentTimeMillis()
                )
                .apply();
    }

    private void sendTokenToServer(String token) {
        new Thread(() -> {
            HttpURLConnection connection = null;

            try {
                SharedPreferences session =
                        getSharedPreferences(
                                "transiva_native_session",
                                MODE_PRIVATE
                        );

                SharedPreferences fcm =
                        getSharedPreferences(
                                "transiva_fcm",
                                MODE_PRIVATE
                        );

                JSONObject rawUser = new JSONObject(
                        session.getString(
                                "raw_user",
                                "{}"
                        )
                );

                String userId = first(
                        session.getString("user_id", ""),
                        session.getString("id", ""),
                        rawUser.optString("user_id", ""),
                        rawUser.optString("id", ""),
                        String.valueOf(
                                fcm.getInt("user_id", 0)
                        )
                );

                if ("0".equals(userId)) {
                    userId = "";
                }

                String username = first(
                        session.getString("username", ""),
                        rawUser.optString("username", ""),
                        fcm.getString("username", "")
                );

                String role = first(
                        session.getString("role", ""),
                        rawUser.optString("role", ""),
                        fcm.getString("role", ""),
                        "customer"
                );

                // Token boleh disimpan lokal saat logout,
                // tetapi jangan upload tanpa identitas.
                if (
                        userId.isEmpty()
                                && username.isEmpty()
                ) {
                    return;
                }

                JSONObject payload = new JSONObject();
                payload.put("token", token);
                payload.put("fcm_token", token);
                payload.put("user_id", userId);
                payload.put("id", userId);
                payload.put("username", username);
                payload.put("role", role);
                payload.put(
                        "platform",
                        "android_native"
                );

                URL url = new URL(
                        BASE_URL + "save_fcm_token.php"
                );

                connection =
                        (HttpURLConnection)
                                url.openConnection();

                connection.setRequestMethod("POST");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                connection.setDoOutput(true);
                connection.setUseCaches(false);
                connection.setRequestProperty(
                        "Content-Type",
                        "application/json; charset=UTF-8"
                );
                connection.setRequestProperty(
                        "Accept",
                        "application/json"
                );

                SessionManager secureSession = new SessionManager(this);
                String authToken = safe(secureSession.getToken());
                if (!authToken.isEmpty()) {
                    connection.setRequestProperty(
                            "Authorization",
                            "Bearer " + authToken
                    );
                    connection.setRequestProperty(
                            "X-Device-UUID",
                            DeviceIdentityManager.getInstallationUuid(this)
                    );
                    connection.setRequestProperty(
                            "X-App-Scope",
                            "driver"
                    );
                }

                try (
                        OutputStream output =
                                connection.getOutputStream()
                ) {
                    output.write(
                            payload
                                    .toString()
                                    .getBytes(
                                            StandardCharsets.UTF_8
                                    )
                    );
                }

                connection.getResponseCode();

            } catch (Exception ignored) {
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).start();
    }

    private String safe(String value) {
        if (value == null) {
            return "";
        }

        value = value.trim();

        if (
                value.isEmpty()
                        || "null".equalsIgnoreCase(value)
                        || "undefined".equalsIgnoreCase(value)
        ) {
            return "";
        }

        return value;
    }

    private String first(String... values) {
        if (values == null) {
            return "";
        }

        for (String value : values) {
            value = safe(value);

            if (!value.isEmpty()) {
                return value;
            }
        }

        return "";
    }
}
