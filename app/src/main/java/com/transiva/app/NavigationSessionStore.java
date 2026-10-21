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
    // Published route arrays are read-only after parsing. Freeze mutable envelope objects on the caller;
    // encode the large geometry only on IO. One pending envelope coalesces frequent checkpoints.
    private static long version;private static Pending pending;private static boolean draining;
    private static StableRouteEngine.Result cachedResult;private static JSONObject cachedRoute;
    private static JSONObject encodedRoute;private static String encodedBytes;
    private static final class Pending {
        final Context context;final JSONObject value;final long version;
        Pending(Context c,JSONObject x,long v){context=c;value=x;version=v;}
    }
    static JSONObject copy(JSONObject value){try{return value==null?null:new JSONObject(value.toString());}catch(Exception e){return null;}}
    static boolean valid(Context c,JSONObject x){String id=identity(c);long age=x==null?Long.MAX_VALUE:System.currentTimeMillis()-x.optLong("saved");return !id.isEmpty()&&x!=null&&id.equals(x.optString("owner"))&&age>=0&&age<21600000;}
    static JSONObject read(Context c){
        JSONObject x;
        synchronized(NavigationSessionStore.class){
            if(!loaded){try{memory=new JSONObject(c.getSharedPreferences("nav_checkpoint_v2",0).getString("route",""));}catch(Exception ignored){}loaded=true;}
            x=memory;
        }
        return valid(c,x)?copy(x):null;
    }
    private static JSONObject envelope(JSONObject input)throws JSONException{
        JSONObject out=new JSONObject();java.util.Iterator<String> fields=input.keys();
        while(fields.hasNext()){String field=fields.next();Object value=input.opt(field);
            if("route".equals(field)&&value instanceof JSONObject){
                // Cache-produced route body is immutable. Other envelopes get a shallow body snapshot.
                JSONObject body=(JSONObject)value;
                if(body!=cachedRoute){JSONObject frozen=new JSONObject();java.util.Iterator<String> names=body.keys();while(names.hasNext()){String name=names.next();frozen.put(name,body.opt(name));}value=frozen;}
            }
            out.put(field,value);
        }return out;
    }
    static void save(Context c,JSONObject x){
        if(stopped||!valid(c,x))return;
        try{
            Context app=c.getApplicationContext();
            synchronized(NavigationSessionStore.class){
                if(stopped)return;memory=envelope(x);loaded=true;pending=new Pending(app,memory,++version);schedule();
            }
        }catch(JSONException e){TransivaDiagnostics.error(c,"navigation","CHECKPOINT_FREEZE_FAILED",e);}
    }
    private static void schedule(){if(!draining){draining=true;IO.execute(NavigationSessionStore::drain);}}
    private static void drain(){
        while(true){
            Pending task;synchronized(NavigationSessionStore.class){task=pending;pending=null;if(task==null){draining=false;return;}}
            try{
                if(task.value==null){task.context.getSharedPreferences("nav_checkpoint_v2",0).edit().remove("route").commit();encodedRoute=null;encodedBytes=null;continue;}
                if(!valid(task.context,task.value))continue;
                JSONObject header=new JSONObject();java.util.Iterator<String> fields=task.value.keys();while(fields.hasNext()){String field=fields.next();if(!"route".equals(field))header.put(field,task.value.opt(field));}
                JSONObject route=task.value.optJSONObject("route");String bytes=header.toString();
                if(route!=null){
                    if(encodedRoute!=route){encodedBytes=route.toString();encodedRoute=route;}
                    if(encodedBytes.length()<=1200000)bytes=bytes.substring(0,bytes.length()-1)+(bytes.length()>2?",":"")+"\"route\":"+encodedBytes+"}";
                    else{header.put("needs_route",true);bytes=header.toString();}
                }
                if(bytes.length()>1500000)continue;
                synchronized(NavigationSessionStore.class){if(task.version!=version)continue;}
                // Clear/new save can supersede this write. The same serial drain applies that newer state next.
                task.context.getSharedPreferences("nav_checkpoint_v2",0).edit().putString("route",bytes).commit();
            }catch(Exception e){TransivaDiagnostics.error(task.context,"navigation","CHECKPOINT_WRITE_FAILED",e);}
        }
    }
    static void clear(Context c){
        synchronized(NavigationSessionStore.class){stopped=true;loaded=true;memory=null;pending=new Pending(c.getApplicationContext(),null,++version);cachedResult=null;cachedRoute=null;schedule();}
    }
    static JSONObject compactOrder(JSONObject order)throws JSONException{
        JSONObject out=new JSONObject();for(String field:new String[]{"id","order_id","source","source_table","status","room_id","chat_room_id","participant_name","customer_name","customer","username","service_name","order_type","pickup_lat","pickup_lng","user_lat","user_lng","delivery_lat","delivery_lng","destination_lat","destination_lng","order_kind","ecosystem","destination_extension"})if(order.has(field))out.put(field,order.opt(field));return out;
    }
    static synchronized JSONObject route(StableRouteEngine.Result r)throws JSONException{if(cachedResult==r&&cachedRoute!=null)return cachedRoute;cachedResult=r;cachedRoute=new JSONObject().put("points",r.latLngPoints).put("maneuvers",r.maneuvers).put("meters",r.distanceMeters).put("seconds",r.durationSeconds).put("summary",r.summary);return cachedRoute;}
    static StableRouteEngine.Result route(JSONObject x){try{JSONObject v=x.getJSONObject("route");JSONArray p=v.getJSONArray("points");double m=v.getDouble("meters"),s=v.getDouble("seconds");if(p.length()<2||!Double.isFinite(m)||!Double.isFinite(s)||m<0||s<0)return null;return new StableRouteEngine.Result(p,m,s,v.optJSONArray("maneuvers"),v.optString("summary"));}catch(Exception e){return null;}}
}
