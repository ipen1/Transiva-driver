package com.transiva.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Single source of truth for customer-order unread state across FCM, Trip,
 * native Navigation and DriverChatRoomActivity.
 *
 * The repository intentionally stores counts (not booleans) so every surface
 * can render the same badge and future UI can show exact unread totals.
 */
public final class DriverMessageUnreadRepository {
    private static final String PREFS = "transiva_message_unread_v1";
    private static final String ORDER_PREFIX = "order_";
    private static final String ROOM_PREFIX = "room_";
    private static final int MAX_UNREAD = 99;

    private DriverMessageUnreadRepository() {}

    public static void markUnread(Context context, String orderId, String roomId) {
        if (context == null) return;
        String order = clean(orderId);
        String room = clean(roomId);
        if (order.isEmpty() && room.isEmpty()) return;
        try {
            SharedPreferences p = prefs(context);
            SharedPreferences.Editor e = p.edit();
            if (!order.isEmpty()) e.putInt(ORDER_PREFIX + order, bump(p.getInt(ORDER_PREFIX + order, 0)));
            if (!room.isEmpty()) e.putInt(ROOM_PREFIX + room, bump(p.getInt(ROOM_PREFIX + room, 0)));
            e.apply();
        } catch (Throwable t) {
            TransivaDiagnostics.error(context, "message", "UNREAD_MARK_FAILED", t);
        }
    }

    public static void markRead(Context context, String orderId, String roomId) {
        if (context == null) return;
        String order = clean(orderId);
        String room = clean(roomId);
        try {
            SharedPreferences.Editor e = prefs(context).edit();
            if (!order.isEmpty()) e.remove(ORDER_PREFIX + order);
            if (!room.isEmpty()) e.remove(ROOM_PREFIX + room);
            e.apply();
            // Clean legacy navigation flags during migration.
            SharedPreferences.Editor legacy = context.getSharedPreferences("transiva", Context.MODE_PRIVATE).edit();
            if (!order.isEmpty()) legacy.remove("nav_customer_unread_order_" + order);
            if (!room.isEmpty()) legacy.remove("nav_customer_unread_room_" + room);
            legacy.apply();
        } catch (Throwable t) {
            TransivaDiagnostics.error(context, "message", "UNREAD_CLEAR_FAILED", t);
        }
    }

    /** Remove unread state for an order that is no longer active. */
    public static void clearOrder(Context context, String orderId) {
        if (context == null) return;
        String order = clean(orderId);
        if (order.isEmpty()) return;
        try { prefs(context).edit().remove(ORDER_PREFIX + order).apply(); }
        catch (Throwable t) { TransivaDiagnostics.error(context, "message", "UNREAD_ORDER_CLEAR_FAILED", t); }
    }

    /**
     * Dashboard is authoritative for currently active orders. This removes badge
     * counts belonging to cancelled/finished/expired orders so the main Pesan
     * item can never keep a ghost number such as (3).
     */
    public static boolean retainOnlyActiveOrders(Context context, java.util.Set<String> activeOrderIds) {
        if (context == null) return false;
        java.util.Set<String> active = new java.util.HashSet<>();
        if (activeOrderIds != null) for (String id : activeOrderIds) {
            String clean = clean(id); if (!clean.isEmpty()) active.add(clean);
        }
        try {
            SharedPreferences p = prefs(context);
            SharedPreferences.Editor e = p.edit();
            boolean changed = false;
            for (String key : p.getAll().keySet()) {
                if (!key.startsWith(ORDER_PREFIX)) continue;
                String id = key.substring(ORDER_PREFIX.length());
                if (!active.contains(id)) { e.remove(key); changed = true; }
            }
            if (changed) e.apply();
            return changed;
        } catch (Throwable t) {
            TransivaDiagnostics.error(context, "message", "UNREAD_PRUNE_FAILED", t);
            return false;
        }
    }

    public static int unreadCount(Context context, String orderId, String roomId) {
        if (context == null) return 0;
        String order = clean(orderId);
        String room = clean(roomId);
        try {
            SharedPreferences p = prefs(context);
            int byOrder = order.isEmpty() ? 0 : p.getInt(ORDER_PREFIX + order, 0);
            int byRoom = room.isEmpty() ? 0 : p.getInt(ROOM_PREFIX + room, 0);
            int value = Math.max(byOrder, byRoom);

            // One-way migration from the v2.8.18 boolean flags.
            if (value <= 0) {
                SharedPreferences legacy = context.getSharedPreferences("transiva", Context.MODE_PRIVATE);
                boolean old = (!order.isEmpty() && legacy.getBoolean("nav_customer_unread_order_" + order, false))
                        || (!room.isEmpty() && legacy.getBoolean("nav_customer_unread_room_" + room, false));
                if (old) {
                    value = 1;
                    SharedPreferences.Editor e = p.edit();
                    if (!order.isEmpty()) e.putInt(ORDER_PREFIX + order, 1);
                    if (!room.isEmpty()) e.putInt(ROOM_PREFIX + room, 1);
                    e.apply();
                }
            }
            return Math.max(0, Math.min(MAX_UNREAD, value));
        } catch (Throwable t) {
            TransivaDiagnostics.error(context, "message", "UNREAD_READ_FAILED", t);
            return 0;
        }
    }

    public static boolean isUnread(Context context, String orderId, String roomId) {
        return unreadCount(context, orderId, roomId) > 0;
    }

    public static int totalUnread(Context context) {
        if (context == null) return 0;
        try {
            int total = 0;
            for (java.util.Map.Entry<String, ?> entry : prefs(context).getAll().entrySet()) {
                if (!entry.getKey().startsWith(ORDER_PREFIX)) continue;
                Object value = entry.getValue();
                if (value instanceof Integer) total += Math.max(0, (Integer) value);
                if (total >= MAX_UNREAD) return MAX_UNREAD;
            }
            return Math.min(MAX_UNREAD, total);
        } catch (Throwable t) {
            TransivaDiagnostics.error(context, "message", "UNREAD_TOTAL_FAILED", t);
            return 0;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static int bump(int value) { return Math.min(MAX_UNREAD, Math.max(0, value) + 1); }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
