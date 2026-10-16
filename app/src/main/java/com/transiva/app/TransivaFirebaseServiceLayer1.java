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
abstract class TransivaFirebaseServiceLayer1 extends FirebaseMessagingService {

    public static final String ACTION_DRIVER_DATA_CHANGED =
            "com.transiva.app.ACTION_DRIVER_DATA_CHANGED";

    public static final String BASE_URL =
            "https://transiva.my.id/server/";

    protected static final String CH_NEW_ORDER =
            "transiva_new_order_channel_v3";
    protected static final String CH_ORDER =
            "transiva_order_channel";
    protected static final String CH_OPPORTUNITY =
            "transiva_driver_opportunity_v1";
    protected static final String CH_WALLET =
            "transiva_wallet_channel";
    protected static final String CH_CHAT =
            "transiva_chat_channel_v2";
    protected static final String CH_CALL =
            "transiva_call_channel_v6";
    protected static final String CH_PROMO =
            "transiva_promo_channel";
    protected static final String CH_BROADCAST =
            "transiva_broadcast_channel";
    protected static final String CH_GENERAL =
            "transiva_general_channel";

    protected String categoryForType(String type) {
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

    protected boolean isMerchantDriverChat(String type, Map<String, String> data) {
        String signal = first(type, "").toLowerCase();
        if (data != null) signal += " " + first(data.get("event"), "").toLowerCase() + " " + first(data.get("screen"), "").toLowerCase();
        return signal.contains("merchant_driver_chat") || signal.contains("driver_merchant_chat")
                || (signal.contains("chat") && signal.contains("merchant") && signal.contains("driver"));
    }

    protected boolean isChat(String type) {
        type = first(type, "").toLowerCase();

        return type.contains("chat") || "driver_global_mention".equals(type)
                || type.contains("message");
    }

    protected boolean isOrder(String type) {
        type = first(type, "").toLowerCase();

        return type.contains("order")
                || type.contains("ride")
                || type.contains("food")
                || type.contains("pickup")
                || type.contains("wisata")
                || type.contains("merchant");
    }

    protected boolean isWallet(String type) {
        type = first(type, "").toLowerCase();

        return type.contains("wallet")
                || type.contains("financial")
                || type.contains("deposit")
                || type.contains("withdraw")
                || type.contains("saldo")
                || type.contains("balance");
    }

    protected int getSmallIcon() {
        try {
            return getApplicationInfo().icon;
        } catch (Exception ignored) {
            return android.R.drawable.ic_dialog_info;
        }
    }

    protected void saveTokenLocal(String token) {
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

    protected void sendTokenToServer(String token) {
        FcmTokenCoordinator.onNewToken(getApplicationContext(), token);
    }

    protected String safe(String value) {
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

    protected String first(String... values) {
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
    // Cross-layer contracts keep the split type-safe without duplicating state.
    protected abstract void publishBubbleEvent(String type, String title, String body, String orderId, String roomId, Map<String, String> data);
    protected abstract void sendCallState(String callId, String status);
    protected abstract void cancelCallNotification(String callId);
    protected abstract void showNotification( String type, String title, String body, String orderId, String roomId, String url, Map<String, String> data );
    protected abstract boolean canUseFullScreenCallIntent();
    protected abstract void wakeScreenForPriority(String type, boolean incomingCall);
    protected abstract Intent buildOpenIntent( String type, String orderId, String roomId, String url, Map<String, String> data );
    protected abstract void createChannels();
    protected abstract void createNewOrderChannel();
    protected abstract Uri orderSoundUri();
    protected abstract boolean isNewIncomingOrder(String type, Map<String, String> data);
    protected abstract void createChannel( String id, String name, String description, int importance );
    protected abstract boolean isDriverRealtimeType(String type);
    protected abstract String channelForType(String type);
    protected abstract int priorityForType(String type);

}
