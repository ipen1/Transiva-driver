package com.transiva.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.content.ContextCompat;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import org.json.JSONObject;

/** Small bounded check on Firebase's message thread, before badges, bubbles or sound. */
final class DriverChatNotificationGate {
    private DriverChatNotificationGate() {}
    static boolean mayPost(Context c){return Build.VERSION.SDK_INT<33||ContextCompat.checkSelfPermission(c,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;}
    static boolean dispatch(Context c,Map<String,String> data,Runnable verified) {
        DriverSessionOwner owner=DriverSessionOwner.capture(c);
        try {
            if(!owner.current()||!mayPost(c))return false;
            String target=value(data,"target_user_id"),scope=value(data,"target_app_scope");
            if((!target.isEmpty()&&!target.equals(owner.userId()))||(!scope.isEmpty()&&!"driver".equalsIgnoreCase(scope)))return false;
            long id;try{id=Long.parseLong(value(data,"chat_id").isEmpty()?value(data,"message_id"):value(data,"chat_id"));}catch(NumberFormatException e){return false;}
            if(id<=0)return false;
            String url=DriverApiConfig.endpoint("driver_chat_notification_status.php")+"?chat_id="+id;
            for(String key:new String[]{"room_id","order_id","order_db_id","order_source"}) {
                String v=value(data,key);if("order_source".equals(key)&&v.isEmpty())v=value(data,"source");
                url+="&"+key+"="+URLEncoder.encode(v,"UTF-8");
            }
            JSONObject result=check(owner,url);
            if(!result.optBoolean("success")||!result.optBoolean("eligible")||result.optLong("chat_id")!=id)return false;
            final long messageId=id;final boolean[] accepted={false};
            owner.runIfCurrent(()->{
                if(!DriverChatNotificationPoller.isRoomOpen(value(data,"room_id"))&&DriverChatNotificationState.claim(c,owner,messageId)) {
                    verified.run();accepted[0]=true;
                }
            });
            return accepted[0];
        } catch(Exception error) {
            // Unverified alerts never become sound/badges. The unread chat remains on the server.
            return false;
        } finally {owner.close();}
    }
    private static JSONObject check(DriverSessionOwner owner,String url)throws Exception {
        owner.check();HttpURLConnection c=com.transiva.app.driver.data.DriverConnectionRepository.open(url);
        try {
            c.setConnectTimeout(3000);c.setReadTimeout(4000);c.setRequestMethod("GET");c.setRequestProperty("Accept","application/json");owner.pin(c);
            if(c.getResponseCode()!=200)throw new java.io.IOException("Chat notice not verified");
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[1024];int n;
                while((n=in.read(buffer))!=-1){owner.check();if(out.size()+n>65536)throw new java.io.IOException("Chat notice response too large");out.write(buffer,0,n);}
                owner.check();return new JSONObject(new String(out.toByteArray(),java.nio.charset.StandardCharsets.UTF_8));
            }
        } finally {owner.release(c);c.disconnect();}
    }
    private static String value(Map<String,String> data,String key){return DriverChatNotificationPolicy.clean(data.get(key));}
}
