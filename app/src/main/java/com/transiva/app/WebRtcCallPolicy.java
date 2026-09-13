package com.transiva.app;

/** Pure call lifecycle guard used by WebRTC UI and tests. */
public final class WebRtcCallPolicy {
    private WebRtcCallPolicy() {}
    public static boolean sameCall(String activeCallId, String incomingCallId) {
        String a = safe(activeCallId), b = safe(incomingCallId);
        return a.isEmpty() || b.isEmpty() || a.equals(b);
    }
    public static boolean mayAutoAccept(boolean incoming, boolean accepted, boolean ended, String callId) {
        return incoming && !accepted && !ended && !safe(callId).isEmpty();
    }
    public static boolean shouldResume(boolean incoming, boolean accepted, String callId) {
        return !safe(callId).isEmpty() && (!incoming || accepted);
    }
    public static boolean immutableIncoming(boolean hadActiveCall, boolean previousIncoming, boolean requestedIncoming) {
        return hadActiveCall ? previousIncoming : requestedIncoming;
    }
    private static String safe(String s){ return s == null ? "" : s.trim(); }
}
