package com.transiva.app;
import android.content.Context;
import android.content.SharedPreferences;
import androidx.work.*;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
/** WorkManager is the only writer. Work inputs contain no bearer, FCM token, or account ID. */
public final class FcmTokenCoordinator {
    static final Object LOCK=new Object();
    static final String WORK="transiva-fcm-registration-v3",PERIODIC="transiva-fcm-refresh-v3";
    private static boolean installed;
    private FcmTokenCoordinator(){}
    static boolean disabled(){return false;}
    static SharedPreferences prefs(Context c){return c.getApplicationContext().getSharedPreferences("transiva_fcm_registration_v2",Context.MODE_PRIVATE);}
    static String role(Context c){return c.getPackageName().endsWith(".driver")?"driver":"customer";}
    static final class Owner {
        final String bearer,session,uuid,installation,version;
        Owner(String b,String u,String v){bearer=b;session=hash(b);uuid=u;installation=hash(u);version=v;}
        String key(){return session+"|"+installation+"|"+version;}
    }
    static Owner owner(Context c){
        SessionManager s=new SessionManager(c);String token=s.getToken();
        if(!s.isLoggedIn()||token==null||token.isEmpty()||!role(c).equalsIgnoreCase(s.getRole()))return null;
        String version;try{android.content.pm.PackageInfo p=c.getPackageManager().getPackageInfo(c.getPackageName(),0);version=p.versionCode+":"+p.versionName;}catch(Exception e){version="unknown";}
        return new Owner(token,DeviceIdentityManager.getInstallationUuid(c).trim().toLowerCase(Locale.US),version);
    }
    static boolean current(Context c,Owner o){Owner now=owner(c);return now!=null&&now.key().equals(o.key());}
    public static synchronized void install(Context c){
        if(c==null||disabled()||installed)return;
        try{
            SharedPreferences state=prefs(c);
            if(state.getInt("scheduler_protocol",0)!=3){
                if(!state.edit().putInt("scheduler_protocol",3).putBoolean("dirty",true).putLong("revision",state.getLong("revision",0)+1).remove("blocked_owner").commit()){
                    diagnostic(c,"LOCAL_STATE_WRITE_FAILED",0);return;
                }
            }
            Constraints constraints=new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
            PeriodicWorkRequest periodic=new PeriodicWorkRequest.Builder(FcmRegistrationWorker.class,6,TimeUnit.HOURS)
                .setConstraints(constraints).setInputData(new Data.Builder().putBoolean("periodic_trigger",true).build()).build();
            WorkManager.getInstance(c.getApplicationContext()).enqueueUniquePeriodicWork(PERIODIC,ExistingPeriodicWorkPolicy.KEEP,periodic);
            installed=true;
        }catch(RuntimeException error){diagnostic(c,"SCHEDULER_UNAVAILABLE",0);}
    }
    public static void syncIfNeeded(Context c){request(c,false);}
    public static void forceSync(Context c){request(c,true);}
    public static void onNewToken(Context c,String token){if(token!=null&&token.trim().length()>=20)request(c,true);}
    private static void request(Context context,boolean force){
        if(context==null||disabled())return;Context c=context.getApplicationContext();install(c);
        Owner o=owner(c);if(o==null)return;
        synchronized(LOCK){
            SharedPreferences p=prefs(c);String observed=p.getString("observed_owner","");boolean changed=!o.key().equals(observed);
            if(force||changed){
                if(!p.edit().putString("observed_owner",o.key()).putBoolean("dirty",true)
                        .putLong("revision",p.getLong("revision",0)+1).remove("blocked_owner").commit()){
                    diagnostic(c,"LOCAL_STATE_WRITE_FAILED",0);return;
                }
            }
            if(!force&&!changed&&o.key().equals(p.getString("blocked_owner","")))return;
            if(!FcmRegistrationPolicy.due(p.getBoolean("dirty",true),o.key(),p.getString("ack_owner",""),System.currentTimeMillis(),p.getLong("ack_at",0)))return;
            try{
                OneTimeWorkRequest work=new OneTimeWorkRequest.Builder(FcmRegistrationWorker.class)
                    .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build();
                // New token/login supersedes an old retry. Routine resume never resets backoff.
                WorkManager.getInstance(c).enqueueUniqueWork(WORK,(force||changed)?ExistingWorkPolicy.REPLACE:ExistingWorkPolicy.KEEP,work);
            }catch(RuntimeException error){diagnostic(c,"SCHEDULER_UNAVAILABLE",0);}
        }
    }
    static void diagnostic(Context c,String code,int http){
        prefs(c).edit().putString("last_error",code).putInt("last_http",http).putLong("last_attempt_at",System.currentTimeMillis()).apply();
        android.util.Log.w("TransivaFcmRegistration",code+" http="+http);
    }
    public static JSONObject diagnostics(Context c){
        JSONObject j=new JSONObject();try{
            SharedPreferences p=prefs(c);Owner o=owner(c);
            j.put("app",role(c)).put("verification_build",disabled()).put("session_ready",o!=null)
             .put("pending",p.getBoolean("dirty",true)).put("last_attempt_at",p.getLong("last_attempt_at",0))
             .put("last_ack_at",p.getLong("ack_at",0)).put("last_http",p.getInt("last_http",0))
             .put("last_error",p.getString("last_error",""))
             .put("last_call_push",p.getString("last_call_push","Belum ada")).put("last_call_push_at",p.getLong("last_call_push_at",0))
             .put("current_session_acknowledged",o!=null&&!p.getBoolean("dirty",true)&&o.key().equals(p.getString("ack_owner","")))
             .put("token_fingerprint",p.getString("ack_token_hash","").length()>=12?p.getString("ack_token_hash","").substring(0,12):"");
        }catch(Exception ignored){}return j;
    }
    static boolean ackMatches(JSONObject p,JSONObject a,Owner o){
        return a!=null&&a.optLong("client_sequence",-1)==p.optLong("client_sequence",-2)
            &&hash(p.optString("fcm_token")).equals(a.optString("token_hash"))&&o.session.equals(a.optString("session_hash"))
            &&o.installation.equals(a.optString("installation_hash"))&&o.version.equals(a.optString("app_version"))
            &&p.optString("registration_id").equals(a.optString("registration_id"));
    }
    static String hash(String value){
        try{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))out.append(String.format(Locale.US,"%02x",b&255));return out.toString();}
        catch(Exception e){throw new IllegalStateException(e);}
    }
}
