package com.transiva.app;
import java.util.Map;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;
/** Absolute server deadlines plus a bounded legacy fallback; pure, API-23 safe. */
final class PushExpiryPolicy {
    static long remainingMillis(Map<String,String> data,long nowMillis,boolean call){
        long deadline=epoch(data,call?"call_expires_epoch":"offer_expires_epoch");
        if(deadline==0&&!call){
            String date=value(data,"offer_expired_at");
            if(!date.isEmpty())try{SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US);f.setLenient(false);f.setTimeZone(TimeZone.getTimeZone("Asia/Makassar"));deadline=f.parse(date).getTime();}catch(Exception e){return 0;}
        }
        if(deadline<0)return 0;
        long cap=call?45000L:300000L;
        if(deadline==0)return cap;
        if(deadline<=nowMillis)return 0;
        return Math.min(cap,deadline-nowMillis);
    }
    private static long epoch(Map<String,String> data,String key){
        String s=value(data,key);if(s.isEmpty())return 0;
        try{long n=Long.parseLong(s);return n>0&&n<Long.MAX_VALUE/1000?n*1000:-1;}catch(Exception e){return -1;}
    }
    static boolean expiredOrder(Map<String,String> data,long now){
        if(data==null)return false;
        String e=value(data,"event"),t=value(data,"type");
        boolean offer=!value(data,"offer_expires_epoch").isEmpty()||!value(data,"offer_expired_at").isEmpty()||"new_order".equalsIgnoreCase(e)||"order_new".equalsIgnoreCase(e)||t.toLowerCase(Locale.US).contains("new_order")||t.toLowerCase(Locale.US).contains("order_new");
        return offer&&remainingMillis(data,now,false)<=0;
    }
    static String value(Map<String,String> data,String key){String s=data==null?null:data.get(key);return s==null?"":s.trim();}
}
