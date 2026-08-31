package com.transiva.app;

import androidx.core.content.ContextCompat;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.view.Gravity;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Keeps customer chat/call actions usable while native navigation is open.
 * Incoming chat FCM events for this order/room turn the message button amber
 * until the driver opens the room.
 */
public final class NavigationCommunicationController {
    private final Activity activity;
    private final JSONObject order;
    private final TextView messageButton;
    private final TextView callButton;
    private final String orderId;
    private final String roomId;
    private final String participantName;
    private final String source;
    private final String service;
    private final String status;
    private boolean registered;
    private boolean unread;
    private String messageLabel = "💬 Pesan";
    private String messageUnreadLabel = "💬 Pesan • Baru";
    private String callLabel = "☎ Telepon";
    private String messageColor = "#D90B63CE";
    private String messageUnreadColor = "#E69A5A00";
    private String callColor = "#D90A8F55";

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String type = clean(intent.getStringExtra("type")).toLowerCase(Locale.US);
            if (!(type.contains("chat") || type.contains("message"))) return;
            if (type.contains("merchant") || type.contains("global") || type.contains("mention")) return;
            String incomingOrder = clean(intent.getStringExtra("order_id"));
            String incomingRoom = clean(intent.getStringExtra("room_id"));
            boolean sameOrder = !orderId.isEmpty() && orderId.equalsIgnoreCase(incomingOrder);
            boolean sameRoom = !roomId.isEmpty() && roomId.equalsIgnoreCase(incomingRoom);
            if (sameOrder || sameRoom) {
                unread = DriverMessageUnreadRepository.isUnread(activity, orderId, roomId);
                renderMessageState();
                NavigationDiagnostics.event(activity, "NAV_CUSTOMER_MESSAGE_UNREAD", null);
            }
        }
    };

    public NavigationCommunicationController(Activity activity, JSONObject order,
                                             TextView messageButton, TextView callButton) {
        this.activity = activity;
        this.order = order == null ? new JSONObject() : order;
        this.messageButton = messageButton;
        this.callButton = callButton;
        this.orderId = first(this.order.optString("order_id"), this.order.optString("id"));
        this.roomId = first(this.order.optString("room_id"), this.order.optString("chat_room_id"),
                orderId.isEmpty() ? "" : "ROOM-" + orderId);
        this.participantName = first(this.order.optString("participant_name"),
                this.order.optString("customer_name"), this.order.optString("customer"),
                this.order.optString("username"), "Customer");
        this.source = first(this.order.optString("source"), this.order.optString("_transiva_table"), "orders");
        this.service = first(this.order.optString("service_name"), this.order.optString("order_type"), "Order");
        this.status = first(this.order.optString("status"), "taken");
        this.unread = DriverMessageUnreadRepository.isUnread(activity, orderId, roomId);
        loadResourceConfig();
        bind();
    }

    private void bind() {
        if (messageButton != null) {
            messageButton.setOnClickListener(v -> openChat());
            renderMessageState();
        }
        if (callButton != null) {
            callButton.setText(callLabel);
            callButton.setTextColor(Color.WHITE);
            callButton.setTextSize(14);
            callButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            callButton.setGravity(Gravity.CENTER);
            callButton.setBackground(DriverNavigationUi.roundRect(Color.parseColor(callColor), 18));
            callButton.setOnClickListener(v -> openCall());
        }
    }

    public void onStart() {
        if (registered) return;
        try {
            IntentFilter filter = new IntentFilter(TransivaFirebaseService.ACTION_DRIVER_DATA_CHANGED);
            ContextCompat.registerReceiver(activity, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
            registered = true;
        } catch (Throwable t) {
            TransivaDiagnostics.error(activity, "navigation", "NAV_CHAT_RECEIVER_REGISTER_FAILED", t);
        }
    }

    public void onStop() {
        if (!registered) return;
        try { activity.unregisterReceiver(receiver); }
        catch (Throwable t) { TransivaDiagnostics.error(activity, "navigation", "NAV_CHAT_RECEIVER_UNREGISTER_FAILED", t); }
        registered = false;
    }

    public void onPictureInPictureModeChanged(boolean pip) {
        if (messageButton != null) messageButton.setVisibility(pip ? TextView.GONE : TextView.VISIBLE);
        if (callButton != null) callButton.setVisibility(pip ? TextView.GONE : TextView.VISIBLE);
    }

    private void openChat() {
        try {
            unread = false;
            renderMessageState();
            DriverMessageUnreadRepository.markRead(activity, orderId, roomId);
            activity.getSharedPreferences("transiva", Context.MODE_PRIVATE).edit()
                    .putString("active_order_id", orderId)
                    .putString("active_chat_order_id", orderId)
                    .putString("active_chat_room_id", roomId)
                    .putString("active_chat_customer_name", participantName)
                    .putString("active_chat_order_status", status)
                    .apply();
            Intent i = new Intent(activity, DriverChatRoomActivity.class);
            i.putExtra("order_id", orderId);
            i.putExtra("order_db_id", first(order.optString("id"), orderId));
            i.putExtra("id", first(order.optString("id"), orderId));
            i.putExtra("room_id", roomId);
            i.putExtra("participant_name", participantName);
            i.putExtra("customer_name", participantName);
            i.putExtra("order_type", service);
            i.putExtra("order_status", status);
            i.putExtra("order_source", source);
            i.putExtra("source", source);
            i.putExtra("read_only", false);
            activity.startActivity(i);
            NavigationDiagnostics.event(activity, "NAV_CHAT_OPEN", null);
        } catch (Throwable t) {
            TransivaDiagnostics.error(activity, "navigation", "NAV_CHAT_OPEN_FAILED", t);
        }
    }

    private void openCall() {
        if (orderId.isEmpty()) return;
        try {
            Intent call = new Intent(activity, WebRtcCallActivity.class);
            call.putExtra("order_id", orderId);
            call.putExtra("source", source);
            call.putExtra("peer_name", participantName);
            call.putExtra("incoming", false);
            activity.startActivity(call);
            NavigationDiagnostics.event(activity, "NAV_CALL_OPEN", null);
        } catch (Throwable t) {
            TransivaDiagnostics.error(activity, "navigation", "NAV_CALL_OPEN_FAILED", t);
        }
    }

    private void renderMessageState() {
        if (messageButton == null) return;
        int count = DriverMessageUnreadRepository.unreadCount(activity, orderId, roomId);
        unread = count > 0;
        messageButton.setText(unread ? (count > 1 ? messageUnreadLabel + " (" + count + ")" : messageUnreadLabel) : messageLabel);
        messageButton.setTextColor(Color.WHITE);
        messageButton.setTextSize(14);
        messageButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        messageButton.setGravity(Gravity.CENTER);
        int color = Color.parseColor(unread ? messageUnreadColor : messageColor);
        messageButton.setBackground(DriverNavigationUi.roundRect(color, 18));
    }

    private void loadResourceConfig() {
        try {
            JSONObject cfg = ResourceUpdateManager.loadJsonOverride(activity, "config/navigation_actions.json");
            if (cfg == null) return;
            messageLabel = first(cfg.optString("message_label"), messageLabel);
            messageUnreadLabel = first(cfg.optString("message_unread_label"), messageUnreadLabel);
            callLabel = first(cfg.optString("call_label"), callLabel);
            messageColor = safeColor(cfg.optString("message_color"), messageColor);
            messageUnreadColor = safeColor(cfg.optString("message_unread_color"), messageUnreadColor);
            callColor = safeColor(cfg.optString("call_color"), callColor);
        } catch (Throwable t) {
            TransivaDiagnostics.error(activity, "navigation", "NAV_ACTION_RESOURCE_CONFIG_FAILED", t);
        }
    }

    private static String safeColor(String candidate, String fallback) {
        String value = clean(candidate);
        if (!value.matches("#[0-9A-Fa-f]{6,8}")) return fallback;
        return value;
    }

    private static String clean(String s) { return s == null ? "" : s.trim(); }
    private static String first(String... values) {
        if (values != null) for (String v : values) if (v != null && !v.trim().isEmpty() && !"null".equalsIgnoreCase(v.trim())) return v.trim();
        return "";
    }
}
