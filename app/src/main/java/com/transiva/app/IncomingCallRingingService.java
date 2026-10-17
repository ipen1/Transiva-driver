package com.transiva.app;
import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
/** At most 45 seconds of incoming-call alerting. Does not open camera/microphone or start media. */
public final class IncomingCallRingingService extends Service {
    private static volatile IncomingCallRingingService instance;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService io=java.util.concurrent.Executors.newSingleThreadExecutor();
    private boolean checking,destroyed,finishing;private long checkGeneration;
    private String call="";private Intent incoming;private PowerManager.WakeLock wake;
    static boolean owns(String id){IncomingCallRingingService s=instance;return s!=null&&id!=null&&id.equals(s.call)&&!s.finishing;}
    static void stopFor(String id){
        Runnable stop=()->{IncomingCallRingingService s=instance;if(s!=null&&id!=null&&id.equals(s.call))s.finishAlert();};
        if(Looper.myLooper()==Looper.getMainLooper())stop.run();else new Handler(Looper.getMainLooper()).post(stop);
    }
    @Override public void onCreate(){super.onCreate();instance=this;IncomingCallNotifier.channels(this);}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null){stopSelf();return START_NOT_STICKY;}
        String id=intent.getStringExtra("call_id");
        if(id==null||IncomingCallNotifier.remaining(intent)<=0||!IncomingCallNotifier.validOwner(this,intent)||IncomingCallAlertManager.isResolved(id)){
            // Acceptance/cancellation can race an already queued service start.
            if(call.isEmpty())stopSelf();return START_NOT_STICKY;
        }
        if(call.equals(id))return START_NOT_STICKY;
        if(!call.isEmpty()){String previous=call;call="";IncomingCallAlertManager.resolve(previous);IncomingCallNotifier.cancel(this,previous);stopForeground(true);releaseWake();}
        call=id;incoming=new Intent(intent);finishing=false;checking=false;++checkGeneration;
        try{
            Notification n=IncomingCallNotifier.build(this,intent,false);
            if(Build.VERSION.SDK_INT>=34)startForeground(IncomingCallNotifier.id(call),n,ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);
            else startForeground(IncomingCallNotifier.id(call),n);
            long remaining=IncomingCallNotifier.remaining(intent);
            IncomingCallAlertManager.start(this,call,remaining);
            PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
            if(pm!=null){wake=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Transiva:IncomingCall");wake.acquire(remaining+1000);}
            main.removeCallbacksAndMessages(null);main.postDelayed(this::expire,remaining);main.postDelayed(this::checkOwner,1000);main.post(this::checkCall);
        }catch(RuntimeException unavailable){
            IncomingCallAlertManager.stop(call);IncomingCallNotifier.cancel(this,call);
            IncomingCallNotifier.post(this,intent,true);stopSelf();
        }
        return START_NOT_STICKY;
    }
    private void checkCall(){
        if(destroyed||finishing||incoming==null||checking)return;
        checking=true;final long generation=++checkGeneration;final String id=call;final SessionManager session=new SessionManager(this);
        final CallSignalSession owner=CallSignalSession.capture(this);
        try{io.execute(()->{
            String state="";
            try{owner.check(this);state=WebRtcSignalApi.postCandidate(session,new org.json.JSONObject().put("action","heartbeat").put("role",getPackageName().endsWith(".driver")?"driver":"customer").put("call_id",id),owner).optString("status");}catch(WebRtcSignalApi.ServerResponseException rejected){if(rejected.status==401||rejected.status==403||rejected.status==404||rejected.status==409)state="ended";}catch(Exception ignored){}
            final String status=state;
            main.post(()->{if(destroyed||finishing||generation!=checkGeneration)return;checking=false;if(!id.equals(call)){main.post(this::checkCall);return;}
                if("accepted".equals(status)||"ended".equals(status)||"missed".equals(status)||"rejected".equals(status)){
                    IncomingCallAlertManager.resolve(id);
                    if(!"accepted".equals(status))sendBroadcast(new Intent(WebRtcCallActivity.ACTION_CALL_STATE).setPackage(getPackageName()).putExtra("call_id",id).putExtra("call_status",status));
                    finishAlert();
                }else main.postDelayed(this::checkCall,4000);
            });
        });}catch(java.util.concurrent.RejectedExecutionException ignored){checking=false;}
    }
    private void checkOwner(){if(incoming==null||!IncomingCallNotifier.validOwner(this,incoming)||IncomingCallAlertManager.isResolved(call)){finishAlert();return;}main.postDelayed(this::checkOwner,1000);}
    private void expire(){IncomingCallAlertManager.resolve(call);finishAlert();}
    private void finishAlert(){finishing=true;++checkGeneration;checking=false;main.removeCallbacksAndMessages(null);stopForeground(true);IncomingCallNotifier.cancel(this,call);stopSelf();}
    // Short-service timeout callback signatures for Android 14 and Android 15+.
    @Override public void onTimeout(int startId){expire();}
    @Override public void onTimeout(int startId,int fgsType){expire();}
    @Override public void onDestroy(){destroyed=true;io.shutdownNow();if(instance==this)instance=null;main.removeCallbacksAndMessages(null);IncomingCallAlertManager.stop(call);releaseWake();super.onDestroy();}
    private void releaseWake(){try{if(wake!=null&&wake.isHeld())wake.release();}catch(RuntimeException ignored){}finally{wake=null;}}
    @Override public IBinder onBind(Intent i){return null;}
}
