package com.transiva.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.os.Build;
import androidx.core.app.NotificationCompat;

/** P3 Guardian: deteksi lokal non-darurat; tidak pernah mengirim SOS otomatis. */
public final class DriverGuardianManager {
    private static final String CH="transiva_guardian_v1";
    private static final long STOP_WARN_MS=10*60*1000L, COOLDOWN=15*60*1000L;
    private long stoppedSince=0L,lastWarn=0L;
    public void onLocation(Context c, Location loc, boolean activeOrder){
        if(c==null||loc==null||!activeOrder){stoppedSince=0;return;}
        long now=System.currentTimeMillis(); double kmh=loc.hasSpeed()?loc.getSpeed()*3.6d:0d;
        if(kmh<2d){ if(stoppedSince==0) stoppedSince=now; } else stoppedSince=0;
        boolean abnormalSpeed=kmh>110d;
        boolean longStop=stoppedSince>0 && now-stoppedSince>=STOP_WARN_MS;
        if((abnormalSpeed||longStop) && now-lastWarn>=COOLDOWN){
            lastWarn=now;
            String reason=abnormalSpeed?"Kecepatan perjalanan terlihat tidak biasa":"Perjalanan berhenti cukup lama";
            notify(c, reason);
            logEvent(c, loc, abnormalSpeed ? "speed_anomaly" : "long_stop", reason);
        }
    }

    private void logEvent(Context c, Location loc, String type, String detail){
        try {
            SessionManager session=new SessionManager(c);
            com.transiva.app.driver.data.DriverApiClient api=new com.transiva.app.driver.data.DriverApiClient(session);
            api.executor().execute(() -> {
                try {
                    org.json.JSONObject body=new org.json.JSONObject();
                    body.put("event_type",type); body.put("detail",detail);
                    body.put("latitude",loc.getLatitude()); body.put("longitude",loc.getLongitude());
                    body.put("order_id",session.get("current_order_id"));
                    api.post("driver_guardian_event.php",body);
                } catch(Exception ignored) {}
            });
        } catch(Exception ignored) {}
    }
    private void notify(Context c,String reason){
        NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26) nm.createNotificationChannel(new NotificationChannel(CH,"Transiva Guardian",NotificationManager.IMPORTANCE_HIGH));
        Intent i=new Intent(c,DriverDashboardActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi=PendingIntent.getActivity(c,771,i,PendingIntent.FLAG_UPDATE_CURRENT|(Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0));
        nm.notify(771,new NotificationCompat.Builder(c,CH).setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("🛡️ Transiva Guardian • Kamu aman?").setContentText(reason+". Ketuk untuk membuka aplikasi atau gunakan SOS bila perlu.")
                .setStyle(new NotificationCompat.BigTextStyle().bigText(reason+". Guardian hanya memberi peringatan dan tidak mengirim SOS otomatis. Ketuk untuk membuka aplikasi atau gunakan tombol SOS bila perlu."))
                .setContentIntent(pi).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build());
    }
}
