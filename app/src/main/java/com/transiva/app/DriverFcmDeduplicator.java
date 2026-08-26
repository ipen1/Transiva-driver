package com.transiva.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Map;

/** P0: menahan push identik/retry FCM dalam jendela pendek. */
public final class DriverFcmDeduplicator {
    private static final String PREF="driver_fcm_dedupe_v1";
    private static final long TTL=120000L;
    private DriverFcmDeduplicator(){}
    public static synchronized boolean isDuplicate(Context c, String messageId, Map<String,String> data){
        String key=messageId==null?"":messageId.trim();
        if(key.isEmpty() && data!=null){
            key=(safe(data.get("type"))+"|"+safe(data.get("event"))+"|"+safe(data.get("order_id"))+"|"+safe(data.get("call_id"))+"|"+safe(data.get("message_id")));
        }
        if(key.isEmpty()) return false;
        String hash=Integer.toHexString(key.hashCode()); long now=System.currentTimeMillis();
        SharedPreferences p=c.getSharedPreferences(PREF,Context.MODE_PRIVATE); long prev=p.getLong(hash,0L);
        p.edit().putLong(hash,now).apply();
        return prev>0 && now-prev<TTL;
    }
    private static String safe(String s){return s==null?"":s.trim();}
}
