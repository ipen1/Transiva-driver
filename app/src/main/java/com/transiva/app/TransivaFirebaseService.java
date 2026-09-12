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
public class TransivaFirebaseService extends TransivaFirebaseServiceLayer2 {

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

    protected void publishBubbleEvent(String type, String title, String body, String orderId, String roomId, Map<String, String> data) {
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
            // FCM dapat menghidupkan proses ketika UI sudah dibuang dari Recent Apps.
            // Jika driver masih ONLINE, pastikan foreground owner + bubble diminta hidup
            // sebelum event dipublikasikan. Event sendiri di-queue oleh overlay service.
            if (DriverBubbleController.driverOnline(this)) {
                DriverServiceController.start(this);
                DriverBubbleController.start(this);
            }
            DriverBubbleOverlayService.publish(this, first(type, "general").toLowerCase(), text, orderId, roomId, mentionId, newOrder);
        } catch (Throwable ignored) {}
    }

    protected void sendCallState(String callId, String status) {
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

    protected void cancelCallNotification(String callId) {
        if (callId == null || callId.trim().isEmpty()) return;
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(Math.abs(("webrtc_call|" + callId).hashCode()));
        } catch (Throwable ignored) { TransivaDiagnostics.error(this,"fcm","NON_FATAL_EXCEPTION",ignored); }
    }

    protected void showNotification(
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

            // Prefer the platform call style, but never let an OEM-specific
            // Notification/CallStyle implementation crash the FCM process. If the
            // style cannot be constructed, explicit Answer/Reject actions provide a
            // stable fallback while preserving CATEGORY_CALL behavior.
            try {
                Person caller = new Person.Builder()
                        .setName(data != null ? first(data.get("caller_name"), "Customer") : "Customer")
                        .setImportant(true)
                        .build();
                builder.setStyle(NotificationCompat.CallStyle.forIncomingCall(
                                caller, rejectPendingIntent, acceptPendingIntent))
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
            } catch (Throwable callStyleError) {
                TransivaDiagnostics.error(this, "call", "CALL_STYLE_FAILED", callStyleError);
                builder.addAction(android.R.drawable.ic_menu_close_clear_cancel,
                                "Tolak", rejectPendingIntent)
                        .addAction(android.R.drawable.sym_action_call,
                                "Terima", acceptPendingIntent)
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
            }

            if (canUseFullScreenCallIntent()) {
                // Use a dedicated immutable Activity PendingIntent for the lock-screen
                // full-screen surface. Android 13+ intentionally keeps this as an
                // expanded heads-up notification while the device is actively in use.
                Intent fullScreenIntent = buildOpenIntent(type, orderId, roomId, url, data);
                fullScreenIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                PendingIntent fullScreenPendingIntent = PendingIntent.getActivity(
                        this, requestCode + 3, fullScreenIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                builder.setFullScreenIntent(fullScreenPendingIntent, true);
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

        try {
            NotificationManagerCompat
                    .from(this)
                    .notify(requestCode, builder.build());
        } catch (Throwable notificationError) {
            // A malformed/OEM-specific incoming-call notification must never crash
            // FirebaseMessagingService and therefore the driver process. Record the
            // failure and fall back to a minimal high-priority notification.
            TransivaDiagnostics.error(this, "call", "INCOMING_NOTIFICATION_FAILED", notificationError);
            if (incomingCallNotification) {
                try {
                    NotificationCompat.Builder fallback = new NotificationCompat.Builder(this, CH_CALL)
                            .setSmallIcon(getSmallIcon())
                            .setContentTitle(first(title, "Panggilan Transiva"))
                            .setContentText(first(body, "Customer memanggil Anda"))
                            .setCategory(NotificationCompat.CATEGORY_CALL)
                            .setPriority(NotificationCompat.PRIORITY_MAX)
                            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                            .setOngoing(true)
                            .setAutoCancel(false)
                            .setContentIntent(pendingIntent);
                    NotificationManagerCompat.from(this).notify(requestCode, fallback.build());
                } catch (Throwable fallbackError) {
                    TransivaDiagnostics.error(this, "call", "INCOMING_NOTIFICATION_FALLBACK_FAILED", fallbackError);
                    // Keep the process alive. IncomingCallAlertManager is already
                    // ringing and the next call-state push can still be processed.
                }
            }
        }

        // Do not start an Activity directly from background FCM. The notification
        // is the sole entry point while backgrounded, so calls stay compliant with
        // Android background-start and Google Play full-screen-intent restrictions.
    }
}
