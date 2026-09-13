package com.transiva.app;

import java.util.Locale;
import java.util.Map;

/** Pure FCM routing rules so duplicate/session/call behavior can be regression-tested. */
public final class DriverFcmPolicy {
    private DriverFcmPolicy() {}
    public static boolean isTerminalSessionEvent(String type, Map<String,String> data) {
        String t = safe(type).toLowerCase(Locale.US);
        return "force_logout".equals(t) || "device_reset".equals(t) || "device_banned".equals(t)
                || (data != null && "1".equals(safe(data.get("force_logout"))));
    }
    public static boolean shouldLaunchIncomingCall(String type, Map<String,String> data) {
        if (!"webrtc_call".equalsIgnoreCase(safe(type)) || data == null) return false;
        return "incoming_call".equalsIgnoreCase(safe(data.get("event"))) && !safe(data.get("call_id")).isEmpty();
    }
    public static boolean isCallTerminalEvent(Map<String,String> data) {
        if (data == null) return false;
        String e = safe(data.get("event")).toLowerCase(Locale.US);
        return "call_ended".equals(e) || "call_rejected".equals(e) || "call_missed".equals(e)
                || "ended".equals(e) || "rejected".equals(e) || "missed".equals(e);
    }
    public static String dedupeKey(String messageId, Map<String,String> data) {
        String id = safe(messageId);
        if (!id.isEmpty()) return id;
        if (data == null) return "";
        return safe(data.get("type")) + "|" + safe(data.get("event")) + "|" + safe(data.get("order_id"))
                + "|" + safe(data.get("call_id")) + "|" + safe(data.get("message_id"));
    }
    private static String safe(String s){ return s == null ? "" : s.trim(); }
}
