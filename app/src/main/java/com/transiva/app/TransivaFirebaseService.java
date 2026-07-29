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
import android.os.Build;
import android.provider.Settings;
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

    public static final String BASE_URL =
            "https://transiva.my.id/server/";

    private static final String CH_ORDER =
            "transiva_order_channel";
    private static final String CH_WALLET =
            "transiva_wallet_channel";
    private static final String CH_CHAT =
            "transiva_chat_channel";
    private static final String CH_CALL =
            "transiva_call_channel_v3";
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

        if ("webrtc_call".equals(type)) {
            final String event = first(data.get("event"), "").toLowerCase();
            final String callId = first(data.get("call_id"), "");

            // Only a genuinely new incoming call is allowed to open the call UI.
            // All state/signaling events are consumed here so they cannot launch
            // WebRtcCallActivity again through a PendingIntent/full-screen intent.
            if ("call_accepted".equals(event) || "accepted".equals(event)) {
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

    private void sendCallState(String callId, String status) {
        if (callId == null || callId.trim().isEmpty()) return;
        try {
            Intent state = new Intent(WebRtcCallActivity.ACTION_CALL_STATE);
            state.setPackage(getPackageName());
            state.putExtra(WebRtcCallActivity.EXTRA_CALL_ID, callId);
            state.putExtra(WebRtcCallActivity.EXTRA_CALL_STATUS, status);
            sendBroadcast(state);
        } catch (Throwable t) {
        }
    }

    private void cancelCallNotification(String callId) {
        if (callId == null || callId.trim().isEmpty()) return;
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(Math.abs(("webrtc_call|" + callId).hashCode()));
        } catch (Throwable ignored) {}
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

        String channelId = channelForType(type);

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

        if (incomingCallNotification) {
            // Full-screen is reserved strictly for a new incoming call. Accepted,
            // SDP and ICE events must never relaunch the active call Activity.
            builder.setCategory(NotificationCompat.CATEGORY_CALL)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setOnlyAlertOnce(true)
                    .setSilent(true)
                    .setTimeoutAfter(50_000L)
                    .setFullScreenIntent(pendingIntent, true);
        } else {
            builder.setDefaults(
                    NotificationCompat.DEFAULT_SOUND
                            | NotificationCompat.DEFAULT_VIBRATE
                            | NotificationCompat.DEFAULT_LIGHTS
            );
        }

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

        if ("webrtc_call".equals(type)
                && data != null
                && "incoming_call".equalsIgnoreCase(first(data.get("event"), ""))) {
            openIncomingCallScreen(intent);
        }
    }

    private void openIncomingCallScreen(Intent intent) {
        if (intent == null) return;
        String dbgCall = first(intent.getStringExtra("call_id"), "");
        boolean overlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
        try {
            Intent callIntent = new Intent(intent);
            callIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP
                            | Intent.FLAG_ACTIVITY_NO_USER_ACTION
            );
            startActivity(callIntent);
        } catch (Throwable ignored) {
            // Android 10+ may block a direct background Activity start. In that
            // case the high-priority full-screen call notification above opens it.
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
        return intent;
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }

        createChannel(
                CH_ORDER,
                "Order Transiva",
                "Order baru dan pembaruan status",
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
            // Silent channel; the full-screen Activity plays exactly one ringtone.
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

    private String channelForType(String type) {
        type = first(type, "general").toLowerCase();

        if ("webrtc_call".equals(type)) {
            return CH_CALL;
        }

        if (isChat(type)) {
            return CH_CHAT;
        }

        if (isWallet(type)) {
            return CH_WALLET;
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
                        || isChat(type)
                        || isOrder(type)
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

        if (isOrder(type) || isWallet(type)) {
            return NotificationCompat.CATEGORY_STATUS;
        }

        if (type.contains("promo")) {
            return NotificationCompat.CATEGORY_PROMO;
        }

        return NotificationCompat.CATEGORY_MESSAGE;
    }

    private boolean isChat(String type) {
        type = first(type, "").toLowerCase();

        return type.contains("chat")
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
