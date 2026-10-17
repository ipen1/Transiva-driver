package com.transiva.app;
import android.app.NotificationManager;
import android.content.*;
import org.json.JSONObject;
import java.util.concurrent.*;
/** Reject is local immediately, then sent with the identity captured at the tap. */
public final class IncomingCallActionReceiver extends BroadcastReceiver {
    public static final String ACTION_REJECT="com.transiva.app.action.REJECT_INCOMING_CALL";
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    @Override public void onReceive(Context context,Intent intent){
        if(intent==null||!ACTION_REJECT.equals(intent.getAction()))return;
        Context app=context.getApplicationContext();String call=intent.getStringExtra("call_id");
        if(call==null||call.isEmpty())return;
        IncomingCallAlertManager.resolve(call);IncomingCallNotifier.cancel(app,call);
        if(intent.hasExtra(IncomingCallNotifier.OWNER)&&!IncomingCallNotifier.validOwner(app,intent))return;
        final SessionManager session=new SessionManager(app);final CallSignalSession owner=CallSignalSession.capture(app);
        PendingResult pending=goAsync();
        try{IO.execute(()->{try{
            owner.check(app);WebRtcSignalApi.postCandidate(session,new JSONObject().put("action","reject").put("role","driver").put("call_id",call),owner);
        }catch(Exception ignored){}finally{pending.finish();}});}catch(RejectedExecutionException ignored){pending.finish();}
    }
}
