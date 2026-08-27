package com.transiva.app;

import android.content.Context;
import android.content.SharedPreferences;

public final class DriverGlobalChatStore {
    private static final String PREF = "driver_global_chat";
    private static final String KEY_UNREAD = "unread_mentions";
    private static final String KEY_LAST_MENTION = "last_mention_id";

    private DriverGlobalChatStore() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static int getUnreadMentions(Context c) { return Math.max(0, prefs(c).getInt(KEY_UNREAD, 0)); }
    public static long getLastMentionId(Context c) { return Math.max(0L, prefs(c).getLong(KEY_LAST_MENTION, 0L)); }

    public static void setUnreadMentions(Context c, int count) {
        prefs(c).edit().putInt(KEY_UNREAD, Math.max(0,count)).apply();
        DriverGlobalChatBubble.refreshCurrent();
    }

    public static void onMentionPush(Context c, long messageId) {
        SharedPreferences p = prefs(c);
        int next = Math.min(99, p.getInt(KEY_UNREAD,0) + 1);
        p.edit().putInt(KEY_UNREAD,next).putLong(KEY_LAST_MENTION,Math.max(0,messageId)).apply();
        DriverGlobalChatBubble.refreshCurrent();
    }
}
