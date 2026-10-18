package com.transiva.app;
import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.messaging.FirebaseMessaging;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
/** Bounded synchronous work; retry scheduling survives process death and reboot. */
public final class FcmRegistrationWorker extends Worker {
    private volatile HttpURLConnection connection;
    public FcmRegistrationWorker(@NonNull Context c,@NonNull WorkerParameters p){super(c,p);}
    @NonNull @Override public Result doWork(){
        Context c=getApplicationContext();if(FcmTokenCoordinator.disabled())return Result.success();
        if(getInputData().getBoolean("periodic_trigger",false)){FcmTokenCoordinator.syncIfNeeded(c);return Result.success();}
        FcmTokenCoordinator.Owner o=FcmTokenCoordinator.owner(c);if(o==null)return Result.success();
        final SharedPreferences p=FcmTokenCoordinator.prefs(c);final long revision;
        synchronized(FcmTokenCoordinator.LOCK){revision=p.getLong("revision",0);}
        try{
            String token=Tasks.await(FirebaseMessaging.getInstance().getToken(),20,TimeUnit.SECONDS);
            if(token==null||token.trim().length()<20)throw new IOException("TOKEN_NOT_READY");
            if(isStopped()||!FcmTokenCoordinator.current(c,o))return Result.success();
            final JSONObject payload;
            synchronized(FcmTokenCoordinator.LOCK){
                if(isStopped()||!FcmTokenCoordinator.current(c,o))return Result.success();
                String binding=o.key()+"|"+FcmTokenCoordinator.hash(token.trim());
                long seq=p.getLong("sequence",0);String request=p.getString("request_id","");
                if(!binding.equals(p.getString("pending_binding",""))||request.isEmpty()){
                    long greatest=Math.max(seq,p.getLong("server_sequence",0));
                    if(greatest>=Long.MAX_VALUE-1){FcmTokenCoordinator.diagnostic(c,"SEQUENCE_EXHAUSTED",0);return Result.failure();}
                    seq=greatest+1;request=UUID.randomUUID().toString();
                    if(!p.edit().putLong("sequence",seq).putString("request_id",request).putString("pending_binding",binding).putBoolean("dirty",true).commit())throw new IOException("LOCAL_STATE_WRITE_FAILED");
                }
                payload=new JSONObject().put("registration_protocol",2).put("fcm_token",token.trim()).put("client_sequence",seq)
                    .put("registration_id",request).put("session_hash",o.session).put("installation_uuid",o.uuid)
                    .put("app_version",o.version).put("app_scope",FcmTokenCoordinator.role(c));
                c.getSharedPreferences("transiva_fcm",Context.MODE_PRIVATE).edit().putString("fcm_token",token.trim()).apply();
                new SessionManager(c).saveFcmToken(token.trim());
                p.edit().putLong("last_attempt_at",System.currentTimeMillis()).putInt("work_attempt",getRunAttemptCount()+1).apply();
            }
            HttpURLConnection http=open(c);connection=http;
            try{
                if(isStopped()||!FcmTokenCoordinator.current(c,o))return Result.success();
                http.setRequestMethod("POST");http.setInstanceFollowRedirects(false);http.setUseCaches(false);http.setDoOutput(true);
                http.setConnectTimeout(8000);http.setReadTimeout(10000);
                http.setRequestProperty("Authorization","Bearer "+o.bearer);http.setRequestProperty("X-App-Scope",FcmTokenCoordinator.role(c));
                http.setRequestProperty("X-Device-UUID",o.uuid);http.setRequestProperty("Content-Type","application/json; charset=UTF-8");
                byte[] body=payload.toString().getBytes(StandardCharsets.UTF_8);http.setFixedLengthStreamingMode(body.length);
                try(OutputStream out=http.getOutputStream()){out.write(body);}
                int status=http.getResponseCode();String raw=read(status>=200&&status<300?http.getInputStream():http.getErrorStream());
                JSONObject reply;try{reply=new JSONObject(raw);}catch(Exception malformed){
                    return fail(c,o,"INVALID_SERVER_RESPONSE",status,true);
                }
                if(status<200||status>=300||!reply.optBoolean("success",false)){
                    String code=reply.optString("code","REGISTRATION_REJECTED");
                    if(!code.matches("[A-Z0-9_]{1,80}"))code="REGISTRATION_REJECTED";
                    if(status==409&&"FCM_REGISTRATION_STALE".equals(code))synchronized(FcmTokenCoordinator.LOCK){
                        if(!isStopped()&&FcmTokenCoordinator.current(c,o))p.edit().putLong("server_sequence",reply.optLong("server_sequence",0)).remove("pending_binding").remove("request_id").commit();
                    }
                    return fail(c,o,code,status,FcmRegistrationPolicy.retryable(status,code));
                }
                JSONObject ack=reply.optJSONObject("registration_ack");
                if(!FcmTokenCoordinator.ackMatches(payload,ack,o))return fail(c,o,"ACK_MISMATCH",status,true);
                synchronized(FcmTokenCoordinator.LOCK){
                    FcmTokenCoordinator.Owner now=FcmTokenCoordinator.owner(c);
                    if(!FcmRegistrationPolicy.mayAcknowledge(o.key(),now==null?"":now.key(),revision,p.getLong("revision",0),isStopped()))return Result.success();
                    if(!p.edit().putBoolean("dirty",false).putString("ack_owner",o.key()).putString("ack_token_hash",ack.optString("token_hash"))
                        .putLong("ack_at",System.currentTimeMillis()).putInt("last_http",status).putString("last_error","")
                        .remove("blocked_owner").remove("pending_binding").remove("request_id").commit())throw new IOException("LOCAL_STATE_WRITE_FAILED");
                }
                return Result.success();
            }finally{http.disconnect();if(connection==http)connection=null;}
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();return Result.retry();}
        catch(Exception error){return fail(c,o,"REGISTRATION_IO_FAILED",0,true);}
    }
    private Result fail(Context c,FcmTokenCoordinator.Owner o,String code,int status,boolean retry){
        synchronized(FcmTokenCoordinator.LOCK){
            if(isStopped()||!FcmTokenCoordinator.current(c,o))return Result.success();
            FcmTokenCoordinator.diagnostic(c,code,status);
            FcmTokenCoordinator.prefs(c).edit().putBoolean("dirty",true).putString("blocked_owner",retry?"":o.key()).apply();
        }
        return retry?Result.retry():Result.failure();
    }
    private static String read(InputStream stream)throws IOException{
        if(stream==null)return "{}";
        try(InputStream in=stream;ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            byte[] b=new byte[2048];int n;while((n=in.read(b))!=-1){if(bytes.size()+n>32768)throw new IOException("RESPONSE_TOO_LARGE");bytes.write(b,0,n);}
            return new String(bytes.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    private static HttpURLConnection open(Context c)throws IOException{return com.transiva.app.driver.data.DriverConnectionRepository.open(DriverApiConfig.endpoint("save_fcm_token.php"));}
    @Override public void onStopped(){HttpURLConnection http=connection;if(http!=null)http.disconnect();super.onStopped();}
}
