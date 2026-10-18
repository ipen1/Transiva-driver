package com.transiva.app;
import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.os.*;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.Person;
import androidx.core.content.ContextCompat;
import java.util.Map;
/** One call notification id for FCM, service and user actions. Never starts an Activity directly. */
final class IncomingCallNotifier {
    static final String SILENT="transiva_incoming_call_v5",AUDIBLE="transiva_incoming_call_fallback_v1";
    static final String OWNER="incoming_session_hash",DEADLINE="incoming_deadline_elapsed";
    static int id(String call){return Math.abs(("webrtc_call|"+call).hashCode());}
    static boolean validOwner(Context c,Intent i){
        SessionManager s=new SessionManager(c);String token=s.getToken();
        String role=c.getPackageName().endsWith(".driver")?"driver":"customer";
        return s.isLoggedIn()&&token!=null&&!token.isEmpty()&&role.equalsIgnoreCase(s.getRole())
            &&FcmTokenCoordinator.hash(token).equals(i.getStringExtra(OWNER));
    }
    static boolean allowed(Context c,String channel){
        if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(c,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return false;
        if(!NotificationManagerCompat.from(c).areNotificationsEnabled())return false;
        if(Build.VERSION.SDK_INT>=26){NotificationManager n=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);NotificationChannel ch=n.getNotificationChannel(channel);if(ch!=null&&ch.getImportance()==NotificationManager.IMPORTANCE_NONE)return false;}
        return true;
    }
    static void receive(Context c,Map<String,String> data){
        if(!FcmSessionTarget.accept(c,data))return;
        String call=PushExpiryPolicy.value(data,"call_id");
        long remaining=PushExpiryPolicy.remainingMillis(data,System.currentTimeMillis(),true);
        if(call.isEmpty())return;
        if(remaining<=0){CallDeliveryTrace.record(c,data,"CALL_EXPIRED","DEADLINE_PASSED");return;}
        SessionManager session=new SessionManager(c);
        if(!session.isLoggedIn()||session.getToken()==null||session.getToken().isEmpty())return;
        Intent open=new Intent(c,WebRtcCallActivity.class);
        for(String key:new String[]{"call_id","order_id","source","call_type","caller_name","caller_photo","push_outbox_id","push_event","push_transport","target_session_hash","target_user_id","target_app_scope"})open.putExtra(key,PushExpiryPolicy.value(data,key));
        if(PushExpiryPolicy.value(data,"push_transport").isEmpty())open.putExtra("push_transport","fcm");
        if(PushExpiryPolicy.value(data,"push_event").isEmpty())open.putExtra("push_event","incoming_call");
        String photo=PushExpiryPolicy.value(data,"caller_photo");
        if(photo.isEmpty())photo=PushExpiryPolicy.value(data,"caller_photo_url");
        if(photo.isEmpty())photo=PushExpiryPolicy.value(data,"caller_avatar");
        open.putExtra("peer_photo",photo);
        if(PushExpiryPolicy.value(data,"source").isEmpty())open.putExtra("source","orders");
        open.putExtra("incoming",true).putExtra("remaining_seconds",(int)Math.max(1,(remaining+999)/1000));
        open.putExtra(DEADLINE,SystemClock.elapsedRealtime()+remaining).putExtra(OWNER,FcmTokenCoordinator.hash(session.getToken()));
        if(!validOwner(c,open)){CallDeliveryTrace.record(c,open,"TARGET_REJECTED","SESSION_CHANGED");return;}
        try{channels(c);
            String blocked=CallDeliveryTrace.notificationBlockReason(c,SILENT);
            if(!blocked.isEmpty()){CallDeliveryTrace.record(c,open,"NOTIFICATION_BLOCKED",blocked);return;}
            if(!IncomingCallAlertManager.claimNotification(call)){CallDeliveryTrace.record(c,open,"DUPLICATE_IGNORED","");return;}
        }
        catch(RuntimeException unavailable){CallDeliveryTrace.record(c,open,"NOTIFICATION_FAILED","MANAGER_UNAVAILABLE");android.util.Log.w("TransivaIncoming","Notifications unavailable",unavailable);return;}
        // Keep a usable notification even if FGS admission is denied or delayed.
        if(!post(c,open,false)){IncomingCallAlertManager.releaseNotification(call);return;}
        try{
            Intent ring=new Intent(c,IncomingCallRingingService.class).putExtras(open);
            CallDeliveryTrace.record(c,open,"SERVICE_REQUESTED","");
            ContextCompat.startForegroundService(c,ring);
        }catch(RuntimeException denied){
            CallDeliveryTrace.record(c,open,"SERVICE_DENIED","FGS_START_REJECTED");
            cancel(c,call);if(!post(c,open,!IncomingCallAlertManager.isRinging(call)))IncomingCallAlertManager.releaseNotification(call);
            android.util.Log.w("TransivaIncoming","Ringing service unavailable; notification fallback",denied);
        }
    }
    static void attachVisible(Context c,Intent i){
        String id=i.getStringExtra("call_id");if(id==null||IncomingCallAlertManager.isResolved(id)||IncomingCallRingingService.owns(id))return;
        if(!i.hasExtra(DEADLINE))i.putExtra(DEADLINE,SystemClock.elapsedRealtime()+Math.max(1,Math.min(45,i.getIntExtra("remaining_seconds",45)))*1000L);
        if(!i.hasExtra(OWNER)){String token=new SessionManager(c).getToken();if(token==null||token.isEmpty())return;i.putExtra(OWNER,FcmTokenCoordinator.hash(token));}
        if(remaining(i)<=0||!validOwner(c,i))return;
        try{channels(c);if(allowed(c,SILENT))ContextCompat.startForegroundService(c,new Intent(c,IncomingCallRingingService.class).putExtras(i));}
        catch(RuntimeException denied){android.util.Log.w("TransivaIncoming","Visible alert service unavailable",denied);}
    }
    static long remaining(Intent i){return Math.max(0,Math.min(45000L,i.getLongExtra(DEADLINE,0)-SystemClock.elapsedRealtime()));}
    static Intent callIntent(Context c,Intent source){
        return new Intent(c,WebRtcCallActivity.class).putExtras(source).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }
    static Notification build(Context c,Intent source,boolean sound){
        source=new Intent(source).putExtra("incoming_system_alert",sound);
        String call=source.getStringExtra("call_id");int number=id(call),flags=PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE;
        PendingIntent view=PendingIntent.getActivity(c,number,callIntent(c,source),flags);
        PendingIntent accept=PendingIntent.getActivity(c,number+1,callIntent(c,source).putExtra("auto_accept",true),flags);
        Intent reject=new Intent(c,IncomingCallActionReceiver.class).setAction(IncomingCallActionReceiver.ACTION_REJECT).putExtra("call_id",call).putExtra(OWNER,source.getStringExtra(OWNER)).putExtra("notification_id",number);
        for(String key:new String[]{"push_outbox_id","push_event","push_transport","target_session_hash","target_user_id","target_app_scope"})if(source.hasExtra(key))reject.putExtra(key,source.getStringExtra(key));
        PendingIntent decline=PendingIntent.getBroadcast(c,number+2,reject,flags);
        String name=source.getStringExtra("caller_name");if(name==null||name.trim().isEmpty())name="Transiva";
        NotificationCompat.Builder b=base(c,source,sound,name,view);
        Notification notification;
        try{
            b.setStyle(NotificationCompat.CallStyle.forIncomingCall(new Person.Builder().setName(name).setImportant(true).build(),decline,accept));
            notification=b.build();
        }catch(RuntimeException unavailable){
            // Rebuild without CallStyle: some devices fail only when build() runs.
            b=base(c,source,sound,name,view);
            b.addAction(android.R.drawable.ic_menu_close_clear_cancel,"Tolak",decline).addAction(android.R.drawable.sym_action_call,"Terima",accept);
            notification=b.build();
        }
        if(sound)notification.flags|=Notification.FLAG_INSISTENT;return notification;
    }
    private static NotificationCompat.Builder base(Context c,Intent source,boolean sound,String name,PendingIntent view){
        int icon=c.getResources().getIdentifier("ic_notification_bell","drawable",c.getPackageName());
        if(icon==0)icon=android.R.drawable.sym_action_call;
        NotificationCompat.Builder b=new NotificationCompat.Builder(c,sound?AUDIBLE:SILENT)
            .setSmallIcon(icon).setContentTitle(name)
            .setContentText("video".equals(source.getStringExtra("call_type"))?"Video call masuk":"Panggilan suara masuk")
            .setCategory(NotificationCompat.CATEGORY_CALL).setPriority(NotificationCompat.PRIORITY_MAX)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setOngoing(true).setAutoCancel(false)
            .setOnlyAlertOnce(true).setTimeoutAfter(Math.max(1,remaining(source))).setContentIntent(view);
        if(sound)b.setDefaults(NotificationCompat.DEFAULT_SOUND|(DriverAppSettings.isVibrationEnabled(c)?NotificationCompat.DEFAULT_VIBRATE:0));else b.setSilent(true);
        boolean fullscreen=Build.VERSION.SDK_INT<34;
        if(Build.VERSION.SDK_INT>=34)try{NotificationManager n=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);fullscreen=n.canUseFullScreenIntent();}catch(RuntimeException denied){fullscreen=false;}
        if(fullscreen)b.setFullScreenIntent(view,true);
        return b;
    }
    static boolean post(Context c,Intent i,boolean sound){
        if(remaining(i)<=0||!validOwner(c,i)||IncomingCallAlertManager.isResolved(i.getStringExtra("call_id")))return false;
        try{channels(c);if(!allowed(c,sound?AUDIBLE:SILENT)){CallDeliveryTrace.record(c,i,"NOTIFICATION_BLOCKED",CallDeliveryTrace.notificationBlockReason(c,sound?AUDIBLE:SILENT));return false;}
            // Keep this check at the notify call site so Lint can verify the permission.
            if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(c,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return false;
            NotificationManagerCompat.from(c).notify(id(i.getStringExtra("call_id")),build(c,i,sound));CallDeliveryTrace.record(c,i,"NOTIFICATION_POSTED",sound?"SYSTEM_SOUND_REQUESTED":"SERVICE_SOUND_OWNER");return true;
        }catch(SecurityException denied){
            // Permission can be revoked after the check, before notify reaches the system.
            CallDeliveryTrace.record(c,i,"NOTIFICATION_FAILED","PERMISSION_CHANGED");android.util.Log.w("TransivaIncoming","Call notification permission denied",denied);return false;
        }catch(RuntimeException failure){CallDeliveryTrace.record(c,i,"NOTIFICATION_FAILED","NOTIFY_EXCEPTION");android.util.Log.w("TransivaIncoming","Call notification failed",failure);return false;}
    }
    static void cancel(Context c,String id){try{NotificationManagerCompat.from(c).cancel(id(id));}catch(RuntimeException ignored){}}
    static void channels(Context c){
        if(Build.VERSION.SDK_INT<26)return;
        NotificationManager n=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
        for(String key:new String[]{SILENT,AUDIBLE}){
            if(n.getNotificationChannel(key)!=null)continue;
            NotificationChannel ch=new NotificationChannel(key,key.equals(SILENT)?"Panggilan masuk":"Cadangan panggilan masuk",NotificationManager.IMPORTANCE_HIGH);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            if(key.equals(SILENT)){ch.setSound(null,null);ch.enableVibration(false);}
            else{ch.setSound(android.provider.Settings.System.DEFAULT_RINGTONE_URI,new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build());ch.enableVibration(DriverAppSettings.isVibrationEnabled(c));}
            n.createNotificationChannel(ch);
        }
    }
}
