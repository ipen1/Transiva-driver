package com.transiva.app;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import androidx.core.content.ContextCompat;

/** Owns the complete WebRTC session independently of navigation and UI recreation. */
public final class WebRtcCallForegroundService extends Service {
    static final String CONNECT="connect",END="end";
    private static volatile WebRtcCallForegroundService instance;
    private WebRtcSessionEngine engine;
    private static final int ID=7821;
    private final BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){
        if(engine==null)return;
        if(WebRtcSessionEngine.CHANGED.equals(i.getAction())){if(engine.active())notification();}
        else engine.terminal(i.getStringExtra("call_id"),i.getStringExtra("call_status"));
    }};
    static WebRtcSessionEngine current(){WebRtcCallForegroundService s=instance;return s==null?null:s.engine;}
    @Override public void onCreate(){super.onCreate();instance=this;
        if(Build.VERSION.SDK_INT>=26){NotificationChannel channel=new NotificationChannel("transiva_active_voice_call","Panggilan Transiva",NotificationManager.IMPORTANCE_LOW);getSystemService(NotificationManager.class).createNotificationChannel(channel);}
        IntentFilter f=new IntentFilter(WebRtcSessionEngine.CHANGED);f.addAction(WebRtcCallActivity.ACTION_CALL_STATE);ContextCompat.registerReceiver(this,receiver,f,ContextCompat.RECEIVER_NOT_EXPORTED);
    }
    @Override public int onStartCommand(Intent i,int flags,int id){
        if(i==null){stopSelf();return START_NOT_STICKY;}
        if(END.equals(i.getAction())){if(engine!=null&&(i.getStringExtra("call_id")==null||engine.callId.equals(i.getStringExtra("call_id"))))engine.end(true,"Panggilan berakhir");else if(engine==null)stopSelf();return START_NOT_STICKY;}
        if(engine!=null){if(engine.active())notification();else sendBroadcast(new Intent(WebRtcSessionEngine.CHANGED).setPackage(getPackageName()).putExtra("error","Panggilan sebelumnya sedang diakhiri. Coba kembali sebentar lagi."));return START_NOT_STICKY;}
        engine=new WebRtcSessionEngine(this,i,()->{stopForeground(true);stopSelf();});
        try{notification();engine.start();}catch(RuntimeException e){engine.destroy();engine=null;stopForeground(true);stopSelf();sendBroadcast(new Intent(WebRtcSessionEngine.CHANGED).setPackage(getPackageName()).putExtra("error","Panggilan tidak dapat dimulai. Periksa izin mikrofon/kamera dan buka aplikasi kembali."));}
        return START_NOT_STICKY;
    }
    static Intent open(Context c){Intent i=new Intent(c,WebRtcCallActivity.class);i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);return i;}
    private void notification(){if(engine==null)return;
        Intent open=open(this);open.putExtra("order_id",engine.orderId).putExtra("source",engine.source).putExtra("peer_name",engine.peer).putExtra("call_id",engine.callId).putExtra("call_type",engine.video?"video":"audio").putExtra("incoming",engine.incoming);
        int immutable=Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0;
        PendingIntent view=PendingIntent.getActivity(this,ID,open,PendingIntent.FLAG_UPDATE_CURRENT|immutable);
        Intent end=new Intent(this,WebRtcCallForegroundService.class).setAction(END).putExtra("call_id",engine.callId);
        PendingIntent hangup=PendingIntent.getService(this,ID+1,end,PendingIntent.FLAG_UPDATE_CURRENT|immutable);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,"transiva_active_voice_call"):new Notification.Builder(this);
        Notification n=b.setSmallIcon(getApplicationInfo().icon).setContentTitle(engine.video?"Video Call Transiva":"Panggilan suara Transiva").setContentText(engine.peer+" • "+engine.status).setContentIntent(view).setOngoing(true).setCategory(Notification.CATEGORY_CALL).setOnlyAlertOnce(true).addAction(android.R.drawable.ic_menu_close_clear_cancel,"Akhiri",hangup).build();
        if(Build.VERSION.SDK_INT>=30){int type=ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;if(engine.video&&Build.VERSION.SDK_INT>=30)type|=ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;startForeground(ID,n,type);}else startForeground(ID,n);
    }
    @Override public void onDestroy(){if(engine!=null)engine.destroy();engine=null;if(instance==this)instance=null;try{unregisterReceiver(receiver);}catch(RuntimeException ignored){}sendBroadcast(new Intent(WebRtcSessionEngine.CHANGED).setPackage(getPackageName()));super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
