package com.transiva.app;

import android.content.Context;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.*;

/** Private, atomic route checkpoint; never persists bearer tokens or resumes another login. */
final class NavigationSessionStore {
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"nav-checkpoint");t.setDaemon(true);return t;});
    private static volatile JSONObject memory;private static boolean loaded;
    static volatile boolean foreground;static String foregroundOwner="";
    static volatile boolean stopped;
    static String identity(Context c){
        try{synchronized(SessionManager.class){SessionManager s=new SessionManager(c);if(!s.isLoggedIn())return "";
            String raw=s.getId()+"|"+s.getUserId()+"|"+s.getToken()+"|"+DeviceIdentityManager.getInstallationUuid(c);
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:hash)out.append(String.format(java.util.Locale.US,"%02x",b&255));return out.toString();
        }}catch(Exception e){return "";}
    }
    static JSONObject copy(JSONObject value){try{return value==null?null:new JSONObject(value.toString());}catch(Exception e){return null;}}
    static boolean valid(Context c,JSONObject x){String id=identity(c);long age=x==null?Long.MAX_VALUE:System.currentTimeMillis()-x.optLong("saved");return !id.isEmpty()&&x!=null&&id.equals(x.optString("owner"))&&age>=0&&age<21600000;}
    static synchronized JSONObject read(Context c){JSONObject x=memory;if(!loaded)try{x=new JSONObject(c.getSharedPreferences("nav_checkpoint_v2",0).getString("route",""));}catch(Exception ignored){}loaded=true;memory=x;if(!valid(c,x))return null;return copy(x);}
    static synchronized void save(Context c,JSONObject x){
        if(stopped||!valid(c,x))return;
        final String bytes=x.toString();if(bytes.length()>1500000)return;loaded=true;memory=copy(x);Context app=c.getApplicationContext();
        IO.execute(()->{try{JSONObject value=new JSONObject(bytes);if(valid(app,value)&&!stopped)app.getSharedPreferences("nav_checkpoint_v2",0).edit().putString("route",bytes).commit();}catch(Exception e){TransivaDiagnostics.error(app,"navigation","CHECKPOINT_WRITE_FAILED",e);}});
    }
    static synchronized void clear(Context c){stopped=true;loaded=true;memory=null;Context app=c.getApplicationContext();IO.execute(()->app.getSharedPreferences("nav_checkpoint_v2",0).edit().remove("route").commit());}
    static JSONObject compactOrder(JSONObject order)throws JSONException{
        JSONObject out=new JSONObject();for(String field:new String[]{"id","order_id","source","source_table","status","room_id","chat_room_id","participant_name","customer_name","customer","username","service_name","order_type","pickup_lat","pickup_lng","user_lat","user_lng","delivery_lat","delivery_lng","destination_lat","destination_lng"})if(order.has(field))out.put(field,order.opt(field));return out;
    }
    static JSONObject route(StableRouteEngine.Result r)throws JSONException{return new JSONObject().put("points",r.latLngPoints).put("maneuvers",r.maneuvers).put("meters",r.distanceMeters).put("seconds",r.durationSeconds).put("summary",r.summary);}
    static StableRouteEngine.Result route(JSONObject x){try{JSONObject v=x.getJSONObject("route");JSONArray p=v.getJSONArray("points");double m=v.getDouble("meters"),s=v.getDouble("seconds");if(p.length()<2||!Double.isFinite(m)||!Double.isFinite(s)||m<0||s<0)return null;return new StableRouteEngine.Result(p,m,s,v.optJSONArray("maneuvers"),v.optString("summary"));}catch(Exception e){return null;}}
}
