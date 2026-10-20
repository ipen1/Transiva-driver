package com.transiva.app;

/** Pure rules scoped to customer order chat; other push categories keep their existing route. */
final class DriverChatNotificationPolicy {
    private DriverChatNotificationPolicy() {}
    static boolean guarded(String type) {return "chat_message".equalsIgnoreCase(clean(type));}
    static boolean active(String status) {
        switch(clean(status).toLowerCase(java.util.Locale.US)) {
            case "": case "finished": case "finish": case "completed": case "complete": case "done":
            case "delivered": case "canceled": case "cancelled": case "rejected": case "merchant_rejected": case "expired":return false;
            default:return true;
        }
    }
    static String clean(String s){return s==null?"":s.trim();}
}
