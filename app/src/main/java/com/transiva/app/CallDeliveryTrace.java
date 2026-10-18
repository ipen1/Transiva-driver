package com.transiva.app;
import android.content.*;
import androidx.work.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
/** Small private journal. Receipt uploads never block notification/ringing or change call status. */
final class CallDeliveryTrace {
    static final Object LOCK=new Object();static final String PREF="call_delivery_trace_v1",WORK="transiva-call-receipts-v1";
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    static android.content.SharedPreferences prefs(Context c){return c.getApplicationContext().getSharedPreferences(PREF,0);}
    static Map<String,String> data(Intent i){
        Map<String,String> d=new HashMap<>();if(i==null)return d;
        for(String k:new String[]{"call_id","push_outbox_id","push_event","push_transport","target_session_hash","target_user_id","target_app_scope"}){
            String v=i.getStringExtra(k);if(v!=null)d.put(k,v);
        }
        if(!d.containsKey("push_transport")||value(d,"push_transport").isEmpty())d.put("push_transport","legacy");
        if(!d.containsKey("target_session_hash")&&i.hasExtra(IncomingCallNotifier.OWNER))d.put("target_session_hash",i.getStringExtra(IncomingCallNotifier.OWNER));
        return d;
    }
    static void record(Context c,Intent i,String stage,String reason){record(c,data(i),stage,reason);}
    static void record(Context context,Map<String,String> source,String stage,String reason){
        if(context==null||!CallDeliveryPolicy.stage(stage))return;
        Context c=context.getApplicationContext();Map<String,String>d=source==null?new HashMap<>():new HashMap<>(source);
        try{
        String captured=value(d,"target_session_hash");
        if(captured.isEmpty()){String token=SecureTokenStore.get(c);if(token!=null&&!token.isEmpty())captured=FcmTokenCoordinator.hash(token);}
        d.put("_trace_owner",captured);d.put("_trace_time",Long.toString(System.currentTimeMillis()));
        }catch(RuntimeException unavailable){android.util.Log.w("TransivaCallTrace","TRACE_IDENTITY_UNAVAILABLE");return;}
        try{IO.execute(()->persist(c,d,stage,reason));}catch(RuntimeException ignored){android.util.Log.w("TransivaCallTrace","TRACE_QUEUE_UNAVAILABLE");}
    }
    private static void persist(Context c,Map<String,String>d,String stage,String reason){
        try{
            String call=value(d,"call_id");if(call.isEmpty())return;
            FcmTokenCoordinator.Owner owner=FcmTokenCoordinator.owner(c);String hash=owner==null?"":owner.session;
            String target=value(d,"target_session_hash");
            String captured=d.containsKey("_trace_owner")?value(d,"_trace_owner"):target;
            // Source-map owner is captured before asynchronous persistence; never rebind an old event.

            String transport=value(d,"push_transport");if(transport.isEmpty())transport="fcm";
            String event=value(d,"push_event");if(event.isEmpty())event=value(d,"event");if(event.isEmpty())event="incoming_call";
            long job;try{job=Long.parseLong(value(d,"push_outbox_id"));}catch(Exception absent){job=0;}
            String why=reason==null?"":reason.replaceAll("[^A-Z0-9_]","");if(why.length()>64)why=why.substring(0,64);
            long now=System.currentTimeMillis();long occurred=now;try{occurred=Long.parseLong(value(d,"_trace_time"));}catch(Exception ignored){}
            synchronized(LOCK){
                android.content.SharedPreferences p=prefs(c);
                JSONArray history=json(p.getString("history","[]"));
                if(!call.equals(p.getString("call_id",""))||!captured.equals(p.getString("history_owner","")))history=new JSONArray();
                String key=transport+"|"+stage+"|"+why;
                for(int n=0;n<history.length();n++)if(key.equals(history.getJSONObject(n).optString("key")))return;
                JSONObject entry=new JSONObject().put("key",key).put("at",occurred).put("stage",stage).put("reason",why).put("transport",transport);
                history.put(entry);while(history.length()>32)history=tail(history,1);
                JSONArray queue=json(p.getString("pending","[]")),next=new JSONArray();
                for(int n=0;n<queue.length();n++){JSONObject e=queue.getJSONObject(n);if(CallDeliveryPolicy.fresh(e.optLong("created_at"),now)&&hash.equals(e.optString("owner")))next.put(e);}
                // Wrong-owner pushes are visible locally but cannot report on another account's call.
                boolean upload=job>0&&owner!=null&&hash.equals(captured)&&!stage.equals("TARGET_REJECTED")&&(transport.equals("fcm")||transport.equals("foreground_poll"));
                if(upload)next.put(new JSONObject().put("event_id",UUID.randomUUID().toString()).put("outbox_id",job).put("call_id",call)
                    .put("push_event",event).put("transport",transport).put("stage",stage).put("reason",why).put("created_at",now).put("owner",captured));
                int dropped=0;while(next.length()>128){next=tail(next,1);dropped++;}
                boolean saved=p.edit().putString("call_id",call).putString("history_owner",captured).putString("history",history.toString()).putString("pending",next.toString())
                    .putInt("dropped",p.getInt("dropped",0)+dropped).commit();
                if(saved&&upload&&!FcmTokenCoordinator.disabled())enqueue(c);
            }
        }catch(Exception error){android.util.Log.w("TransivaCallTrace","TRACE_WRITE_FAILED");}
    }
    static void ringing(Context c,Intent intent){
        boolean playing=false;try{playing=IncomingCallAlertManager.isRinging(intent.getStringExtra("call_id"));}catch(RuntimeException ignored){}
        record(c,intent,playing?"RINGING_STARTED":"RINGING_UNCONFIRMED",playing?"":"RINGTONE_NOT_PLAYING");
    }
    static String notificationBlockReason(Context c,String channel){
        try{
            if(android.os.Build.VERSION.SDK_INT>=33&&androidx.core.content.ContextCompat.checkSelfPermission(c,android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)return "POST_NOTIFICATIONS_DENIED";
            if(!androidx.core.app.NotificationManagerCompat.from(c).areNotificationsEnabled())return "NOTIFICATIONS_DISABLED";
            if(android.os.Build.VERSION.SDK_INT>=26){android.app.NotificationManager n=(android.app.NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);android.app.NotificationChannel ch=n==null?null:n.getNotificationChannel(channel);if(ch!=null&&ch.getImportance()==android.app.NotificationManager.IMPORTANCE_NONE)return "CHANNEL_DISABLED";}
            return "";
        }catch(RuntimeException error){return "MANAGER_UNAVAILABLE";}
    }
    static void resume(Context context){
        Context c=context.getApplicationContext();
        IO.execute(()->{if(json(prefs(c).getString("pending","[]")).length()>0&&!FcmTokenCoordinator.disabled())enqueue(c);});
    }
    static boolean enqueue(Context c){
        try{OneTimeWorkRequest w=new OneTimeWorkRequest.Builder(CallDeliveryReceiptWorker.class)
            .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build();
            // Appending prevents a final-drain/enqueue race from losing an event.
            WorkManager.getInstance(c).enqueueUniqueWork(WORK,ExistingWorkPolicy.APPEND_OR_REPLACE,w);return true;
        }catch(RuntimeException error){FcmTokenCoordinator.Owner owner=FcmTokenCoordinator.owner(c);prefs(c).edit().putString("receipt_error","SCHEDULER_UNAVAILABLE").putString("receipt_error_owner",owner==null?"":owner.session).apply();return false;}
    }
    static JSONArray json(String s){try{return new JSONArray(s);}catch(Exception bad){return new JSONArray();}}
    static JSONArray tail(JSONArray a,int start)throws JSONException{JSONArray b=new JSONArray();for(int i=start;i<a.length();i++)b.put(a.get(i));return b;}
    static String value(Map<String,String>d,String k){String v=d.get(k);return v==null?"":v.trim();}
    static String report(Context c){
        FcmTokenCoordinator.Owner current=FcmTokenCoordinator.owner(c);String owner=current==null?"":current.session;
        android.content.SharedPreferences p=prefs(c);StringBuilder out=new StringBuilder();JSONArray h=json(p.getString("history","[]"));
        for(int i=0;i<h.length();i++)try{JSONObject e=h.getJSONObject(i);out.append(java.text.DateFormat.getTimeInstance().format(new Date(e.optLong("at")))).append(" ").append(e.optString("transport")).append(" / ").append(e.optString("stage"));if(!e.optString("reason").isEmpty())out.append(" (").append(e.optString("reason")).append(")");out.append("\n");}catch(Exception ignored){}
        int pending=0;JSONArray queue=json(p.getString("pending","[]"));for(int i=0;i<queue.length();i++)if(owner.equals(queue.optJSONObject(i)==null?"":queue.optJSONObject(i).optString("owner")))pending++;
        out.append("Receipt tertunda: ").append(pending).append("\nReceipt terkirim terakhir: ");
        long t=owner.equals(p.getString("receipt_ack_owner",""))?p.getLong("receipt_ack_at",0):0;out.append(t>0?java.text.DateFormat.getDateTimeInstance().format(new Date(t)):"Belum ada");
        out.append("\nKesalahan receipt: ").append(owner.equals(p.getString("receipt_error_owner",""))?p.getString("receipt_error",""):"");return out.toString();
    }
}
