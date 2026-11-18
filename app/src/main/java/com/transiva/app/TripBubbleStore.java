package com.transiva.app;
import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;

/** Last incoming message survives screen handoff; reading never advances order state. */
final class TripBubbleStore {
    static final class Message {
        final long id,at;final String raw;final boolean merchant,ack;
        Message(JSONObject o){id=o.optLong("id");at=o.optLong("at");raw=o.optString("raw");merchant=o.optBoolean("merchant");ack=o.optBoolean("ack");}
        String key(){return (merchant?"m":"c")+id;}
    }
    private static String key(Context c,JSONObject o,boolean merchant){return NavigationSessionStore.identity(c)+"|"+NavigationTargetPolicy.identity(o)+(merchant?"|m":"|c");}
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("trip_bubble_last_v1",0);}
    static synchronized Message get(Context c,JSONObject o,boolean merchant){
        try{String saved=prefs(c).getString(key(c,o,merchant),"");return saved.isEmpty()?null:new Message(new JSONObject(saved));}catch(JSONException ignored){return null;}
    }
    static synchronized void offer(Context c,JSONObject o,long id,String raw,boolean merchant,boolean read){
        if(id<=0||raw.trim().isEmpty())return;
        Message old=get(c,o,merchant);if(old!=null&&id<old.id)return;
        if(old!=null&&id==old.id){if(read&&!old.ack)ack(c,o,old);return;}
        if(read&&old==null)return; // Do not surface old read history when first enabling previews.
        try{JSONObject value=new JSONObject().put("id",id).put("raw",raw).put("merchant",merchant).put("ack",read).put("at",System.currentTimeMillis());
            prefs(c).edit().putString(key(c,o,merchant),value.toString()).apply();
        }catch(JSONException ignored){}
    }
    static synchronized Message latest(Context c,JSONObject o){
        Message a=get(c,o,false),b=FoodTripPresentation.isFood(o)?get(c,o,true):null;
        return a==null?b:b==null?a:a.id>=b.id?a:b;
    }
    static synchronized void ack(Context c,JSONObject o,Message message){
        Message current=get(c,o,message.merchant);if(current==null||current.id!=message.id||current.ack)return;
        try{JSONObject value=new JSONObject(prefs(c).getString(key(c,o,message.merchant),"{}"));value.put("ack",true);prefs(c).edit().putString(key(c,o,message.merchant),value.toString()).apply();}catch(JSONException ignored){}
    }
}
