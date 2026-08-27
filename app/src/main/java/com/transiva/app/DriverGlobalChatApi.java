package com.transiva.app;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;

public final class DriverGlobalChatApi {
    private static final String ENDPOINT = "https://transiva.my.id/server/driver_global_chat.php";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private DriverGlobalChatApi() {}

    public interface Callback { void onResult(JSONObject json); void onError(String message); }

    public static void get(Context c, int sinceId, Callback cb) {
        request(c,"GET",ENDPOINT+"?action=get&since_id="+Math.max(0,sinceId),null,cb);
    }
    public static void send(Context c, String message, Callback cb) {
        try { JSONObject b=new JSONObject(); b.put("action","send"); b.put("message",message); request(c,"POST",ENDPOINT,b,cb); }
        catch(Exception e){ MAIN.post(() -> cb.onError("Pesan tidak valid.")); }
    }
    public static void readMention(Context c, long messageId, Callback cb) {
        try { JSONObject b=new JSONObject(); b.put("action","read_mention"); b.put("message_id",messageId); request(c,"POST",ENDPOINT,b,cb); }
        catch(Exception e){ MAIN.post(() -> cb.onError("Gagal menandai mention.")); }
    }

    private static void request(Context c,String method,String url,JSONObject body,Callback cb){
        Context app=c.getApplicationContext();
        DriverNetworkExecutor.execute(() -> {
            HttpURLConnection conn=null;
            try{
                conn=(HttpURLConnection)new URL(url).openConnection();
                conn.setConnectTimeout(15000); conn.setReadTimeout(25000); conn.setUseCaches(false);
                conn.setRequestProperty("Accept","application/json"); conn.setRequestProperty("Content-Type","application/json; charset=UTF-8");
                conn.setRequestProperty("X-Transiva-App","Android-Driver"); conn.setRequestProperty("X-App-Scope","driver");
                conn.setRequestProperty("X-Android-SDK",String.valueOf(Build.VERSION.SDK_INT));
                String token=new SessionManager(app).getToken();
                if(token!=null && !token.trim().isEmpty()){
                    conn.setRequestProperty("Authorization","Bearer "+token.trim());
                    conn.setRequestProperty("X-Device-UUID",DeviceIdentityManager.getInstallationUuid(app));
                }
                conn.setRequestMethod(method);
                if("POST".equals(method)){
                    conn.setDoOutput(true); OutputStreamWriter w=new OutputStreamWriter(conn.getOutputStream(),"UTF-8");
                    w.write(body==null?"{}":body.toString()); w.flush(); w.close();
                }
                int status=conn.getResponseCode();
                if(conn instanceof HttpsURLConnection) DriverTlsPinning.verify(app,(HttpsURLConnection)conn);
                InputStream is=status>=200&&status<400?conn.getInputStream():conn.getErrorStream();
                BufferedReader r=new BufferedReader(new InputStreamReader(is,"UTF-8")); StringBuilder s=new StringBuilder(); String line;
                while((line=r.readLine())!=null)s.append(line); r.close();
                JSONObject json=new JSONObject(s.toString());
                if(status>=200&&status<300 && json.optBoolean("success",false)) MAIN.post(() -> cb.onResult(json));
                else { String m=json.optString("message","Gagal terhubung ke chat global."); MAIN.post(() -> cb.onError(m)); }
            }catch(Exception e){ MAIN.post(() -> cb.onError("Koneksi chat global bermasalah.")); }
            finally{ if(conn!=null)try{conn.disconnect();}catch(Exception ignored){} }
        });
    }
}
