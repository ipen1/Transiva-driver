package com.transiva.app;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.google.firebase.messaging.FirebaseMessaging;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One registration writer per process; requests survive network loss and overlapping token events. */
public final class FcmTokenCoordinator {
    private static FcmTokenCoordinator instance;
    private final Context app;
    private final String role;
    private final SharedPreferences prefs;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Object diskLock=new Object();
    private boolean busy,startupCheck=true;
    private volatile long attempt;
    private long due;
    private int failures;
    private volatile HttpURLConnection connection;
    private static final long PERIOD=6L*60L*60L*1000L;
    private static final long WATCHDOG=30000L;
    private static final class Owner {
        final String bearer,session,uuid,installation,version;
        Owner(String bearer,String uuid,String version){this.bearer=bearer;this.session=hash(bearer);this.uuid=uuid;
            this.installation=hash(uuid);this.version=version;}
        String key(){return session+"|"+installation+"|"+version;}
    }
    private FcmTokenCoordinator(Context c){
        app=c.getApplicationContext();role=app.getPackageName().endsWith(".driver")?"driver":"customer";
        prefs=app.getSharedPreferences("transiva_fcm_registration_v2",Context.MODE_PRIVATE);
    }
    public static synchronized void install(Context c){if(c!=null&&instance==null)instance=new FcmTokenCoordinator(c);}
    private static FcmTokenCoordinator get(Context c){install(c);return instance;}
    /** Cheap foreground check. A version/session/installation change bypasses the periodic interval. */
    public static void syncIfNeeded(Context c){if(c==null)return;FcmTokenCoordinator r=get(c);r.main.post(()->r.pump());}
    public static void forceSync(Context c){if(c==null)return;FcmTokenCoordinator r=get(c);r.main.post(()->{r.dirty();r.pump();});}
    /** Never upload the callback argument directly: obtain the SDK's current token when the writer is free. */
    public static void onNewToken(Context c,String token){
        if(c==null||token==null||token.trim().length()<20)return;
        FcmTokenCoordinator r=get(c);r.main.post(()->{r.dirty();r.pump();});
    }
    private void dirty(){
        prefs.edit().putLong("revision",prefs.getLong("revision",0)+1).putBoolean("dirty",true).apply();
        due=0;
    }
    private Owner owner(){
        SessionManager s=new SessionManager(app);String bearer=s.getToken();
        if(!s.isLoggedIn()||bearer==null||bearer.trim().isEmpty()||!role.equalsIgnoreCase(s.getRole()))return null;
        String uuid=DeviceIdentityManager.getInstallationUuid(app).trim().toLowerCase(Locale.US);
        String version;
        try{android.content.pm.PackageInfo p=app.getPackageManager().getPackageInfo(app.getPackageName(),0);
            version=p.versionCode+":"+p.versionName;}catch(Exception e){version="unknown";}
        return new Owner(bearer,uuid,version);
    }
    private boolean current(Owner o){Owner now=owner();return now!=null&&now.key().equals(o.key());}
    private void pump(){
        
        Owner o=owner();if(o==null)return;
        if(startupCheck){prefs.edit().putBoolean("dirty",true).apply();startupCheck=false;}
        boolean changed=!o.key().equals(prefs.getString("observed_owner",""));
        if(changed){prefs.edit().putString("observed_owner",o.key()).putBoolean("dirty",true).apply();due=0;failures=0;}
        if(busy||SystemClock.elapsedRealtime()<due)return;
        long age=System.currentTimeMillis()-prefs.getLong("ack_at",0);
        if(!prefs.getBoolean("dirty",true)&&o.key().equals(prefs.getString("ack_owner",""))&&age>=0&&age<PERIOD)return;
        busy=true;final long id=++attempt,revision=prefs.getLong("revision",0);
        main.postDelayed(()->{
            if(id!=attempt||!busy)return;
            HttpURLConnection c=connection;if(c!=null)c.disconnect();
            finish(id,o,revision,false,null);
        },WATCHDOG);
        try{FirebaseMessaging.getInstance().getToken()
            .addOnSuccessListener(token->{
                if(id!=attempt||!busy)return;
                if(!current(o)||token==null||token.trim().length()<20){finish(id,o,revision,false,null);return;}
                final String latest=token.trim();
                io.execute(()->{
                    boolean success=false;JSONObject reply=null;
                    try{
                        if(id!=attempt||!current(o))throw new IllegalStateException("Sesi berubah");
                        JSONObject payload=prepare(id,o,latest);
                        reply=upload(id,o,payload);
                        JSONObject ack=reply.optJSONObject("registration_ack");
                        success=ackMatches(payload,ack,o);
                    }catch(Exception ignored){ }
                    final boolean ok=success;final JSONObject result=reply;
                    main.post(()->finish(id,o,revision,ok,result));
                });
            }).addOnFailureListener(e->finish(id,o,revision,false,null));
        }catch(RuntimeException e){finish(id,o,revision,false,null);}
    }
    private JSONObject prepare(long id,Owner o,String token)throws Exception {
        synchronized(diskLock){
            if(id!=attempt||!current(o))throw new IllegalStateException("Request diganti");
            String tokenHash=hash(token),binding=o.key()+"|"+tokenHash;
            long seq=prefs.getLong("sequence",0);String request=prefs.getString("request_id","");
            if(!binding.equals(prefs.getString("pending_binding",""))||request.isEmpty()){
                seq=Math.max(seq,prefs.getLong("server_sequence",0))+1;request=UUID.randomUUID().toString();
                // Persist BEFORE HTTP so a process restart retries the same sequence/receipt.
                if(!prefs.edit().putLong("sequence",seq).putString("request_id",request)
                        .putString("pending_binding",binding).putBoolean("dirty",true).commit())
                    throw new IllegalStateException("Registrasi belum tersimpan");
            }
            app.getSharedPreferences("transiva_fcm",Context.MODE_PRIVATE).edit().putString("fcm_token",token).apply();
            new SessionManager(app).saveFcmToken(token);
            return new JSONObject().put("registration_protocol",2).put("fcm_token",token)
                .put("client_sequence",seq).put("registration_id",request).put("session_hash",o.session)
                .put("installation_uuid",o.uuid).put("app_version",o.version).put("app_scope",role);
        }
    }
    static boolean ackMatches(JSONObject p,JSONObject ack,Owner o){
        return ack!=null&&ack.optLong("client_sequence",-1)==p.optLong("client_sequence",-2)
            &&hash(p.optString("fcm_token")).equals(ack.optString("token_hash"))
            &&o.session.equals(ack.optString("session_hash"))&&o.installation.equals(ack.optString("installation_hash"))
            &&o.version.equals(ack.optString("app_version"))&&p.optString("registration_id").equals(ack.optString("registration_id"));
    }
    private JSONObject upload(long id,Owner o,JSONObject p)throws Exception {
        HttpURLConnection c=com.transiva.app.driver.data.DriverConnectionRepository.open(DriverApiConfig.endpoint("save_fcm_token.php"));
        connection=c;
        try{
            if(id!=attempt||!current(o))throw new IllegalStateException("Sesi berubah");
            c.setInstanceFollowRedirects(false);c.setRequestMethod("POST");c.setDoOutput(true);c.setUseCaches(false);
            c.setConnectTimeout(8000);c.setReadTimeout(10000);
            c.setRequestProperty("Authorization","Bearer "+o.bearer);c.setRequestProperty("X-App-Scope",role);
            c.setRequestProperty("X-Device-UUID",o.uuid);c.setRequestProperty("Content-Type","application/json; charset=UTF-8");
            byte[] body=p.toString().getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(body.length);
            try(OutputStream out=c.getOutputStream()){out.write(body);}
            int status=c.getResponseCode();InputStream stream=status>=200&&status<300?c.getInputStream():c.getErrorStream();
            if(stream==null)throw new IllegalStateException("Respons kosong");
            JSONObject result;
            try(InputStream in=stream;ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                byte[] buf=new byte[2048];int n;while((n=in.read(buf))!=-1){if(bytes.size()+n>32768)throw new IllegalStateException("Respons besar");bytes.write(buf,0,n);}
                result=new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
            }
            if(status==409&&id==attempt&&current(o)&&"FCM_REGISTRATION_STALE".equals(result.optString("code"))){
                synchronized(diskLock){prefs.edit().putLong("server_sequence",result.optLong("server_sequence",0))
                    .remove("pending_binding").remove("request_id").commit();}
            }
            if(status<200||status>=300||!result.optBoolean("success",false))throw new IllegalStateException("Registrasi ditolak");
            return result;
        }finally{c.disconnect();if(connection==c)connection=null;}
    }
    private void finish(long id,Owner o,long revision,boolean success,JSONObject reply){
        if(id!=attempt||!busy)return;
        busy=false;++attempt;
        if(!current(o)){due=0;main.post(this::pump);return;}
        if(success&&revision==prefs.getLong("revision",0)){
            JSONObject ack=reply.optJSONObject("registration_ack");
            prefs.edit().putBoolean("dirty",false).putString("ack_owner",o.key())
                .putString("ack_token_hash",ack.optString("token_hash")).putLong("ack_at",System.currentTimeMillis())
                .remove("pending_binding").remove("request_id").apply();failures=0;due=0;
        }else if(success){
            // An event arrived during upload. It must be drained, never hidden by this older success.
            prefs.edit().putBoolean("dirty",true).apply();due=0;main.post(this::pump);
        }else{
            prefs.edit().putBoolean("dirty",true).apply();failures=Math.min(failures+1,5);
            long delay=Math.min(60000L,5000L<<(failures-1));due=SystemClock.elapsedRealtime()+delay;
            main.postDelayed(this::pump,delay);
        }
    }
    static String hash(String value){
        try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.US,"%02x",b&255));return out.toString();
        }catch(Exception e){throw new IllegalStateException(e);}
    }
}
