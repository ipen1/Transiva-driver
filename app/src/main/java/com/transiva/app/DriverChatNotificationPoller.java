package com.transiva.app;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import org.json.JSONArray;
import org.json.JSONObject;

public final class DriverChatNotificationPoller {
    private static final String CHANNEL_ID="transiva_driver_chat_messages";
    private static final String PREFS="transiva_driver_chat_notification_state";
    private static final String KEY_LAST_ID="last_customer_chat_id";
    private static final Handler HANDLER=new Handler(Looper.getMainLooper());
    private static Context appContext; private static SessionManager session; private static boolean running; private static String openRoom="";
    private DriverChatNotificationPoller(){}
    public static void start(Context context){if(context==null)return;appContext=context.getApplicationContext();session=new SessionManager(appContext);createChannel(appContext);if(running)return;running=true;HANDLER.post(checkRunnable);}
    public static void setOpenRoom(String room){openRoom=normalize(room);} public static void clearOpenRoom(String room){if(openRoom.equals(normalize(room)))openRoom="";}
    public static void requestPermission(Activity activity){if(activity!=null&&Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(activity,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},7302);}
    private static final Runnable checkRunnable=new Runnable(){@Override public void run(){if(!running||appContext==null||session==null)return;checkNow();HANDLER.postDelayed(this,8000L);}};
    private static void checkNow(){Context context=appContext;SharedPreferences p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);int last=p.getInt(KEY_LAST_ID,0);new Thread(()->{try{JSONObject r=DriverMessageApi.get(session,"https://transiva.my.id/server/get_driver_chat_updates.php?last_id="+last+"&_="+System.currentTimeMillis());if(!r.optBoolean("success",false))return;JSONArray a=r.optJSONArray("messages");if(a!=null)for(int i=0;i<a.length();i++){JSONObject m=a.optJSONObject(i);if(m!=null&&m.optInt("id",0)>last&&!normalize(m.optString("room_id","")).equals(openRoom))show(context,m);}int newest=r.optInt("last_id",last);if(newest>last)p.edit().putInt(KEY_LAST_ID,newest).apply();}catch(Exception ignored){}}).start();}
    private static void show(Context c,JSONObject m){if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(c,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return;String raw=m.optString("message","");String preview=(raw.startsWith("[[IMAGE]]")||raw.startsWith("[[IMAGE2]]"))?"📷 Customer mengirim foto":raw;Intent i=new Intent(c,DriverChatRoomActivity.class);i.putExtra("order_id",m.optString("order_id",""));i.putExtra("order_db_id",m.optString("order_db_id",""));i.putExtra("order_source",m.optString("source","orders"));i.putExtra("room_id",m.optString("room_id",""));i.putExtra("participant_name",m.optString("participant_name","Customer"));i.putExtra("order_type",m.optString("order_type",""));i.putExtra("order_status",m.optString("status",""));i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);int id=Math.max(1,m.optInt("id",1));PendingIntent pi=PendingIntent.getActivity(c,id,i,PendingIntent.FLAG_UPDATE_CURRENT|(Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0));int icon=c.getResources().getIdentifier("ic_notification","drawable",c.getPackageName());if(icon==0)icon=c.getApplicationInfo().icon;NotificationCompat.Builder b=new NotificationCompat.Builder(c,CHANNEL_ID).setSmallIcon(icon).setContentTitle(m.optString("participant_name","Pesan Customer")).setContentText(preview).setStyle(new NotificationCompat.BigTextStyle().bigText(preview)).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_MESSAGE).setContentIntent(pi);NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);if(nm!=null)nm.notify(id,b.build());}
    private static void createChannel(Context c){if(Build.VERSION.SDK_INT<26)return;NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);if(nm!=null){NotificationChannel ch=new NotificationChannel(CHANNEL_ID,"Pesan Customer untuk Driver",NotificationManager.IMPORTANCE_HIGH);ch.enableVibration(true);nm.createNotificationChannel(ch);}}
    private static String normalize(String s){return s==null?"":s.trim().replace('_','-').toUpperCase();}
}
