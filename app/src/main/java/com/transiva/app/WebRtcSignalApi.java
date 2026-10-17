package com.transiva.app;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
/** Bounded signaling transport, pinned to the original call session. */
public final class WebRtcSignalApi {
    private WebRtcSignalApi(){}
    static final class ServerResponseException extends Exception {final int status;ServerResponseException(int status,String message){super(message);this.status=status;}}
    public static JSONObject post(SessionManager session,JSONObject payload)throws Exception{return post(session,payload,CallSignalSession.capture(session.getContext()));}
    static JSONObject post(SessionManager session,JSONObject payload,CallSignalSession owner)throws Exception{return request(session,payload,owner,20000);}
    static JSONObject postCandidate(SessionManager session,JSONObject payload,CallSignalSession owner)throws Exception{return request(session,payload,owner,6000);}
    private static JSONObject request(SessionManager session,JSONObject payload,CallSignalSession owner,int timeout)throws Exception{
        android.content.Context c=session.getContext();owner.check(c);
        HttpURLConnection connection=com.transiva.app.driver.data.DriverConnectionRepository.open(DriverApiConfig.endpoint("webrtc_call.php"));
        try{
            connection.setInstanceFollowRedirects(false);connection.setRequestMethod("POST");connection.setConnectTimeout(timeout);connection.setReadTimeout(timeout);connection.setUseCaches(false);connection.setDoOutput(true);
            connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("Content-Type","application/json; charset=UTF-8");owner.pin(c,connection);
            byte[] bytes=payload.toString().getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(bytes.length);
            try(OutputStream out=connection.getOutputStream()){owner.check(c);out.write(bytes);}
            int status=connection.getResponseCode();InputStream stream=status>=200&&status<300?connection.getInputStream():connection.getErrorStream();
            if(stream==null)throw new IOException("Respons signaling kosong");
            JSONObject json;
            try(InputStream in=stream;ByteArrayOutputStream body=new ByteArrayOutputStream()){
                byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1){owner.check(c);if(body.size()+n>524288)throw new IOException("Respons signaling terlalu besar");body.write(buffer,0,n);}
                json=new JSONObject(new String(body.toByteArray(),StandardCharsets.UTF_8));
            }
            owner.check(c);
            if(status<200||status>=300||!json.optBoolean("success",false))throw new ServerResponseException(status,json.optString("message","Signaling gagal"));
            return json;
        }finally{connection.disconnect();}
    }
}
