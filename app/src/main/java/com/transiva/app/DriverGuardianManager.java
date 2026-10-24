package com.transiva.app;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.SystemClock;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import org.json.JSONObject;
import com.transiva.app.driver.data.DriverApiClient;

/** Local advisory only. Every alert requires a fresh authenticated server approval. */
public final class DriverGuardianManager {
    private static final String CH="transiva_guardian_v1";
    private static final java.util.concurrent.atomic.AtomicLong EPOCH=new java.util.concurrent.atomic.AtomicLong();
    private final DriverGuardianPolicy detector=new DriverGuardianPolicy();
    private final android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
    private String trip="";
    private long epoch=-1,checkedAt=-1,attemptAt=-1;
    private boolean eligible,busy,closed;
    public static void invalidate(Context c){
        synchronized(EPOCH){EPOCH.incrementAndGet();cancel(c);}
    }
    private static void cancel(Context c){
        try{NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);if(nm!=null)nm.cancel(771);}catch(RuntimeException ignored){}
    }
    public void close(Context c){closed=true;invalidate(c);main.removeCallbacksAndMessages(null);}
    private String identity(SessionManager s){
        String id=s.get("current_order_id").trim();
        return !s.isLoggedIn()||!"1".equals(s.get("driver_server_online"))||id.isEmpty()?"":s.getToken()+"|"+s.get("current_order_source")+"|"+id;
    }
    private boolean prepare(Context c,SessionManager s){
        String key=identity(s);long current=EPOCH.get();
        if(current!=epoch||!trip.equals(key)){
            epoch=current;trip=key;eligible=false;checkedAt=attemptAt=-1;detector.reset();cancel(c);
        }
        return !closed&&!trip.isEmpty();
    }
    /** Also called by a service timer, so terminal orders clear even when GPS stops. */
    public void refresh(Context c){
        SessionManager s=new SessionManager(c);
        if(!prepare(c,s)||busy)return;
        long now=SystemClock.elapsedRealtime();
        if(checkedAt>=0 && now-checkedAt<15000L)return;
        request(c,s,null,"");
    }
    public void onLocation(Context c,Location loc){
        SessionManager s=new SessionManager(c);
        if(!prepare(c,s)||loc==null)return;
        refresh(c);
        long now=SystemClock.elapsedRealtime();
        boolean fresh=eligible&&checkedAt>=0&&now-checkedAt<=30000L;
        String type=detector.offer(trip,now,fresh,loc.hasSpeed(),loc.hasSpeed()?loc.getSpeed()*3.6d:0);
        if(!type.isEmpty()&&!busy&&(attemptAt<0||now-attemptAt>=15000L))request(c,s,new Location(loc),type);
    }
    private void request(Context c,SessionManager s,Location loc,String type){
        final String captured=trip,orderId=s.get("current_order_id");
        final String source="pickup_orders".equals(s.get("current_order_source"))?"pickup_orders":"orders";
        final long generation=epoch,started=SystemClock.elapsedRealtime();
        final DriverSessionOwner owner=DriverSessionOwner.capture(c);
        final DriverApiClient api=new DriverApiClient(s);busy=true;if(loc!=null)attemptAt=started;
        boolean queued=DriverNetworkExecutor.execute(()->{
            try{
                JSONObject body=new JSONObject().put("action",loc==null?"state":"event").put("order_id",orderId).put("source",source);
                if(loc!=null)body.put("event_type",type).put("latitude",loc.getLatitude()).put("longitude",loc.getLongitude())
                    .put("location_time",loc.getTime()).put("detail",reason(type));
                JSONObject response=api.post("driver_guardian_event.php",body).body;
                main.post(()->{
                    busy=false;
                    if(closed||!owner.current()||generation!=EPOCH.get()||!captured.equals(identity(new SessionManager(c)))){owner.close();return;}
                    owner.close();
                    // A response delayed by a bad connection is never permission to alert.
                    if(SystemClock.elapsedRealtime()-started>15000L){eligible=false;detector.reset();cancel(c);return;}
                    JSONObject state=response.optJSONObject("guardian");
                    eligible=state!=null&&state.optBoolean("active",false);checkedAt=SystemClock.elapsedRealtime();
                    if(!eligible){detector.reset();cancel(c);return;}
                    if(loc!=null&&response.optBoolean("notify",false)){
                        synchronized(EPOCH){
                            if(generation==EPOCH.get()&&captured.equals(identity(new SessionManager(c)))){
                                detector.warned(checkedAt);notifyDriver(c,reason(type));
                            }
                        }
                    }
                });
            }catch(Exception error){main.post(()->{busy=false;if(generation==EPOCH.get()&&captured.equals(trip)){eligible=false;detector.reset();cancel(c);}owner.close();});}
            finally{api.shutdown();}
        });
        if(!queued){busy=false;owner.close();api.shutdown();}
    }
    private static String reason(String type){return "speed_anomaly".equals(type)?"Kecepatan perjalanan terlihat tidak biasa":"Perjalanan berhenti cukup lama";}
    private void notifyDriver(Context c,String reason){
        if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(c,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return;
        NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);if(nm==null)return;
        if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(new NotificationChannel(CH,"Transiva Guardian",NotificationManager.IMPORTANCE_HIGH));
        Intent i=new Intent(c,DriverDashboardActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi=PendingIntent.getActivity(c,771,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        nm.notify(771,new NotificationCompat.Builder(c,CH).setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("🛡️ Transiva Guardian • Kamu aman?").setContentText(reason+". Ketuk untuk membuka aplikasi.")
            .setStyle(new NotificationCompat.BigTextStyle().bigText(reason+". Guardian hanya memberi peringatan dan tidak mengirim SOS otomatis."))
            .setContentIntent(pi).setAutoCancel(true).setTimeoutAfter(60000L).setPriority(NotificationCompat.PRIORITY_HIGH).build());
    }
}
