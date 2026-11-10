package com.transiva.app;

import android.content.Context;
import android.content.Intent;
import java.util.Map;

/** Only fresh, fully session-bound pushes may reuse the server's eligibility check. */
final class FreshChatPush {
    private static final long MAX_VERIFICATION_AGE_SECONDS = 8L;
    static final String ACTION_ROOM_CHANGED = "com.transiva.app.ORDER_CHAT_ROOM_CHANGED";
    private FreshChatPush() {}

    static boolean verified(Map<String, String> data, String user, String scope, String sessionHash) {
        if (user.isEmpty() || sessionHash.isEmpty()
                || !"chat_message".equals(data.get("type"))
                || !user.equals(data.get("target_user_id"))
                || !scope.equals(data.get("target_app_scope"))
                || !sessionHash.equals(data.get("target_session_hash"))
                || !("driver".equals(scope) ? "customer" : "driver").equals(data.get("sender_role"))) return false;
        try {
            long checkedAt = Long.parseLong(data.get("chat_verified_epoch"));
            long now = System.currentTimeMillis() / 1000L;
            return checkedAt <= now && checkedAt >= now - MAX_VERIFICATION_AGE_SECONDS
                    && Long.parseLong(data.get("chat_expires_epoch")) > now;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    static void refreshRoom(Context context, String room) {
        if (room.isEmpty()) return;
        context.sendBroadcast(new Intent(ACTION_ROOM_CHANGED).setPackage(context.getPackageName())
                .putExtra("room_id", room));
    }

    static boolean sameRoom(String left, String right) {
        return left != null && right != null && !left.trim().isEmpty()
                && left.trim().replace('_', '-').equalsIgnoreCase(right.trim().replace('_', '-'));
    }
}
