package com.transiva.app;

import android.content.Context;
import com.transiva.app.driver.data.DriverConnectionRepository;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

final class ChatNotificationAck {
    private static final String SCOPE = "driver";
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(1, 2, 15, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), new ThreadPoolExecutor.DiscardPolicy());

    private ChatNotificationAck() {}

    static void posted(Context context, Map<String,String> data, String channelId) {
        if(data==null||!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled())return;
        if(android.os.Build.VERSION.SDK_INT>=26){
            android.app.NotificationManager manager=context.getSystemService(android.app.NotificationManager.class);
            android.app.NotificationChannel channel=manager==null?null:manager.getNotificationChannel(channelId);
            if(channel==null||channel.getImportance()==android.app.NotificationManager.IMPORTANCE_NONE)return;
        }
        String type=data.get("type");
        if(!"chat_message".equals(type)&&!"merchant_driver_chat".equals(type))return;
        String id=data.get("chat_id");
        if(id==null||!id.matches("[1-9][0-9]{0,9}")||data.get("room_id")==null)return;
        Context app=context.getApplicationContext();
        SessionManager session=new SessionManager(app);
        String token=session.getToken();
        if(!session.isLoggedIn()||token==null||token.isEmpty())return;
        String targetSession=data.get("target_session_hash");
        String targetUser=data.get("target_user_id");
        if(targetUser!=null&&!targetUser.isEmpty()&&!targetUser.equals(session.getUserId()))return;
        IO.execute(()->{
            try {
                if(targetSession!=null&&!targetSession.isEmpty()&&!targetSession.equals(hash(token)))return;
                JSONObject payload=new JSONObject().put("chat_id",id).put("type",type)
                        .put("room_id",data.get("room_id"))
                        .put("order_id",data.get("order_id")).put("order_db_id",data.get("order_db_id"))
                        .put("source",data.get("source")!=null?data.get("source"):data.get("order_source"));
                byte[] body=payload.toString().getBytes(StandardCharsets.UTF_8);
                for(int attempt=0;attempt<2;attempt++){
                    if(!session.isLoggedIn()||!token.equals(session.getToken()))return;
                    int status=send(app,token,body);
                    if(status>0&&status<500)return;
                }
            }catch(Exception ignored){}
        });
    }

    static void postedFromPoll(Context context, JSONObject message, String channelId){
        Map<String,String> data=new java.util.HashMap<>();
        data.put("type","chat_message");data.put("chat_id",message.optString("id"));
        for(String key:new String[]{"room_id","order_id","order_db_id","source"})data.put(key,message.optString(key));
        posted(context,data,channelId);
    }

    private static int send(Context context,String token,byte[] body){
        HttpURLConnection connection=null;
        try{
            connection=DriverConnectionRepository.open(DriverApiConfig.endpoint("chat_notification_ack.php"));
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(3000);connection.setReadTimeout(4000);
            connection.setRequestProperty("Authorization","Bearer "+token);
            connection.setRequestProperty("X-Device-UUID",DeviceIdentityManager.getInstallationUuid(context));
            connection.setRequestProperty("X-App-Scope",SCOPE);
            connection.setRequestProperty("Content-Type","application/json; charset=UTF-8");
            connection.setDoOutput(true);connection.setFixedLengthStreamingMode(body.length);
            try(java.io.OutputStream out=connection.getOutputStream()){out.write(body);}
            return connection.getResponseCode();
        }catch(java.io.IOException retry){return 0;}
        finally{if(connection!=null)connection.disconnect();}
    }

    private static String hash(String value)throws Exception{
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex=new StringBuilder();
        for(byte b:bytes)hex.append(Character.forDigit((b>>4)&15,16)).append(Character.forDigit(b&15,16));
        return hex.toString();
    }
}
