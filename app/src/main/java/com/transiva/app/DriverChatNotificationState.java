package com.transiva.app;

import android.content.Context;
import android.content.SharedPreferences;

/** A shared, durable message-ID receipt for FCM and polling, isolated by account. */
final class DriverChatNotificationState {
    private DriverChatNotificationState() {}
    static synchronized boolean claim(Context c,DriverSessionOwner owner,long id) {
        if(id<=0||!owner.current())return false;
        SharedPreferences p=c.getApplicationContext().getSharedPreferences("driver_chat_notice_v2_"+owner.userId(),Context.MODE_PRIVATE);
        String key="message_"+id;
        if(p.contains(key))return false;
        long now=System.currentTimeMillis();SharedPreferences.Editor edit=p.edit();
        for(java.util.Map.Entry<String,?> row:p.getAll().entrySet()) {
            if(row.getValue() instanceof Long && now-(Long)row.getValue()>86400000L)edit.remove(row.getKey());
        }
        // Persist before emitting: a recreated process cannot replay a notification already claimed.
        return edit.putLong(key,now).commit();
    }
}
