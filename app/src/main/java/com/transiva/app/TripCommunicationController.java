package com.transiva.app;

import androidx.core.content.ContextCompat;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.widget.Button;

import org.json.JSONObject;

/** Customer/merchant chat and native-navigation launcher for DriverTripActivity. */
public final class TripCommunicationController {
    private final DriverTripActivity host;
    private final JSONObject order;
    private final String driverUsername;
    private final Button chatButton;
    private boolean registered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refreshUnread(); }
    };

    public TripCommunicationController(DriverTripActivity host, JSONObject order,
                                       String driverUsername, Button chatButton) {
        this.host = host;
        this.order = order == null ? new JSONObject() : order;
        this.driverUsername = clean(driverUsername);
        this.chatButton = chatButton;
        refreshUnread();
    }

    public void onStart() {
        if (registered) { refreshUnread(); return; }
        try {
            IntentFilter f = new IntentFilter(TransivaFirebaseService.ACTION_DRIVER_DATA_CHANGED);
            ContextCompat.registerReceiver(host, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED);
            registered = true;
        } catch (Throwable t) {
            TransivaDiagnostics.error(host, "message", "TRIP_UNREAD_RECEIVER_REGISTER_FAILED", t);
        }
        refreshUnread();
    }

    public void onStop() {
        if (!registered) return;
        try { host.unregisterReceiver(receiver); }
        catch (Throwable t) { TransivaDiagnostics.error(host, "message", "TRIP_UNREAD_RECEIVER_UNREGISTER_FAILED", t); }
        registered = false;
    }

    public void openCustomerChat() {
        try {
            String oid = orderId();
            String rid = roomId();
            String customer = first(order.optString("participant_name"), order.optString("customer_name"),
                    order.optString("customer"), order.optString("username"), "Customer");
            String source = source();
            String service = first(order.optString("service_name"), order.optString("order_type"), host.tripIsPickupOrder() ? "TransSend" : "Order");

            DriverMessageUnreadRepository.markRead(host, oid, rid);
            host.getSharedPreferences("transiva", Context.MODE_PRIVATE).edit()
                    .putString("active_order_id", oid)
                    .putString("active_chat_order_id", oid)
                    .putString("active_chat_room_id", rid)
                    .putString("active_chat_driver_name", driverUsername)
                    .putString("active_chat_customer_name", customer)
                    .putString("active_chat_order_status", host.tripStatus())
                    .apply();

            Intent i = new Intent(host, DriverChatRoomActivity.class);
            i.putExtra("order_id", oid);
            i.putExtra("order_db_id", host.tripInternalId());
            i.putExtra("id", host.tripInternalId());
            i.putExtra("room_id", rid);
            i.putExtra("participant_name", customer);
            i.putExtra("customer_name", customer);
            i.putExtra("order_type", service);
            i.putExtra("order_status", host.tripStatus());
            i.putExtra("order_source", source);
            i.putExtra("source", source);
            i.putExtra("read_only", false);
            host.startActivity(i);
            refreshUnread();
        } catch (Throwable t) {
            TransivaDiagnostics.error(host, "message", "TRIP_CHAT_OPEN_FAILED", t);
            host.tripInfo("Chat", "Gagal membuka chat order ini.");
        }
    }

    public void openMerchantChat() {
        if (!host.tripIsFoodOrder()) return;
        Intent i = new Intent(host, DriverMerchantChatActivity.class);
        i.putExtra("order_id", orderId());
        i.putExtra("order_db_id", host.tripInternalId());
        i.putExtra("merchant_name", first(order.optString("restaurant_name"), host.tripPickupAddress(), "Merchant"));
        host.startActivity(i);
    }

    public void openNavigation(boolean pickup, double driverLat, double driverLng) {
        double lat = pickup ? host.tripCoord("pickup_lat", "user_lat") : host.tripCoord("delivery_lat", "destination_lat");
        double lng = pickup ? host.tripCoord("pickup_lng", "user_lng") : host.tripCoord("delivery_lng", "destination_lng");
        if (!host.tripValid(lat, lng)) { host.tripInfo("Lokasi", "Koordinat belum tersedia."); return; }
        String mode = pickup ? "pickup" : "delivery";
        try { host.tripFitNativeOverview(); }
        catch (Throwable t) { TransivaDiagnostics.error(host, "navigation", "TRIP_OVERVIEW_FIT_FAILED", t); }

        try {
            host.getSharedPreferences("transiva", Context.MODE_PRIVATE).edit()
                    .putString("driver_navigation_order_json", order.toString())
                    .putString("driver_navigation_target", mode)
                    .putString("driver_navigation_lat", String.valueOf(lat))
                    .putString("driver_navigation_lng", String.valueOf(lng))
                    .putString("driver_navigation_driver_lat", String.valueOf(driverLat))
                    .putString("driver_navigation_driver_lng", String.valueOf(driverLng)).apply();
        } catch (Throwable t) { TransivaDiagnostics.error(host, "navigation", "TRIP_NAV_PREFS_FAILED", t); }

        Intent nativeNav = new Intent(host, DriverNavigationActivity.class);
        nativeNav.putExtra("order_json", order.toString());
        nativeNav.putExtra("target", mode);
        nativeNav.putExtra("target_lat", lat);
        nativeNav.putExtra("target_lng", lng);
        nativeNav.putExtra("driver_lat", driverLat);
        nativeNav.putExtra("driver_lng", driverLng);
        try {
            NavigationDiagnostics.event(host, "NAV_OPEN_CLICK", null);
            host.startActivity(nativeNav);
            return;
        } catch (Throwable t) { NavigationDiagnostics.error(host, "NAV_ACTIVITY_OPEN_FAILED", t); }

        try {
            Intent fallback = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + lat + "," + lng + "&mode=d"));
            fallback.setPackage("com.google.android.apps.maps");
            host.startActivity(fallback);
            NavigationDiagnostics.event(host, "NAV_OPEN_FALLBACK", null);
        } catch (Throwable t) {
            NavigationDiagnostics.error(host, "NAV_OPEN_FALLBACK_FAILED", t);
            host.tripInfo("Navigasi", pickup ? "Rute diarahkan ke titik penjemputan." : "Rute diarahkan ke titik pengantaran.");
        }
    }

    public void refreshUnread() {
        if (chatButton == null) return;
        int count = DriverMessageUnreadRepository.unreadCount(host, orderId(), roomId());
        chatButton.setText(count > 0 ? "💬 Chat (" + count + ")" : "💬 Chat");
        if (count > 0) chatButton.setTextColor(Color.WHITE);
    }

    private String orderId() { return first(order.optString("order_id"), order.optString("id"), "-"); }
    private String roomId() { return first(order.optString("room_id"), order.optString("chat_room_id"), "ROOM-" + orderId()); }
    private String source() { return first(order.optString("source"), order.optString("_transiva_table"), host.tripIsPickupOrder() ? "pickup_orders" : "orders"); }
    private static String clean(String s) { return s == null ? "" : s.trim(); }
    private static String first(String... v) { if (v != null) for (String s : v) if (s != null && !s.trim().isEmpty() && !"null".equalsIgnoreCase(s.trim())) return s.trim(); return ""; }
}
