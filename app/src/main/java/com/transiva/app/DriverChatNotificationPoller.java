package com.transiva.app;

import android.Manifest; import android.app.Activity; import android.app.NotificationChannel; import android.app.NotificationManager; import android.app.PendingIntent; import android.content.Context; import android.content.Intent; import android.content.SharedPreferences; import android.content.pm.PackageManager; import android.os.Build; import android.os.Handler; import android.os.Looper; import androidx.core.app.NotificationCompat; import androidx.core.content.ContextCompat; import org.json.JSONArray; import org.json.JSONObject; import java.util.concurrent.atomic.AtomicBoolean;

public final class DriverChatNotificationPoller {
 private static final String CHANNEL_ID="transiva_driver_chat_messages",PREFS="transiva_driver_chat_notification_state",KEY_LAST_ID="last_customer_chat_id"; private static final Handler H=new Handler(Looper.getMainLooper()); private static final AtomicBoolean IN_FLIGHT=new AtomicBoolean(); private static Context app; private static SessionManager session; private static volatile boolean running; private static volatile String openRoom="";private static DriverSessionOwner owner;private static long generation;
 private DriverChatNotificationPoller(){}
 public static void start(Context c){synchronized(SessionManager.class){synchronized(DriverChatNotificationPoller.class){
  if(c==null)return;
  if(running&&owner!=null&&owner.current())return;
  stop();app=c.getApplicationContext();session=new SessionManager(app);owner=DriverSessionOwner.capture(app);
  if(!owner.current())return;createChannel(app);running=true;H.post(run);
 }}}
 public static synchronized void stop(){generation++;running=false;if(owner!=null)owner.close();owner=null;IN_FLIGHT.set(false);openRoom="";H.removeCallbacksAndMessages(null);session=null;app=null;}
 private static boolean current(long g,DriverSessionOwner o){if(!o.current())return false;synchronized(DriverChatNotificationPoller.class){return running&&g==generation&&o==owner;}}
 public static void setOpenRoom(String r){openRoom=norm(r);} public static void clearOpenRoom(String r){if(openRoom.equals(norm(r)))openRoom="";}
 public static void requestPermission(Activity a){if(a!=null&&Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(a,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)a.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},7302);}
 private static final Runnable run=new Runnable(){public void run(){if(!running||app==null||session==null)return;if(!session.isLoggedIn()){stop();return;}check();if(running)H.postDelayed(this,WaveLoadGuard.jitter(DriverPollingCoordinator.interval(app,20000L)));}};
 private static void check(){
  final Context c;final SessionManager s;final DriverSessionOwner captured;final long g;
  synchronized(SessionManager.class){synchronized(DriverChatNotificationPoller.class){
   c=app;s=session;captured=owner;g=generation;
   if(c==null||s==null||captured==null||!captured.current()||!IN_FLIGHT.compareAndSet(false,true))return;
  }}
  final SharedPreferences p=c.getSharedPreferences(PREFS+"_"+captured.userId(),Context.MODE_PRIVATE);final int last=p.getInt(KEY_LAST_ID,0);
  DriverNetworkExecutor.execute(()->{try{
   if(!current(g,captured))return;
   JSONObject r=DriverMessageApi.get(s,"https://transiva.my.id/server/get_driver_chat_updates.php?last_id="+last+"&_="+System.currentTimeMillis(),captured);
   if(!r.optBoolean("success",false))return;
   captured.runIfCurrent(()->{synchronized(DriverChatNotificationPoller.class){
    if(!running||g!=generation||captured!=owner)return;
     JSONArray a=r.optJSONArray("messages");if(a!=null)for(int i=0;i<a.length();i++){JSONObject m=a.optJSONObject(i);if(m!=null&&m.optInt("id",0)>last&&!norm(m.optString("room_id","")).equals(openRoom))show(c,m);}
     int newest=r.optInt("last_id",last);if(newest>last)p.edit().putInt(KEY_LAST_ID,newest).apply();
   }});
  }catch(Exception e){if(current(g,captured))TransivaDriverCrashReporter.nonFatal("chat_poller",e);}
  finally{synchronized(DriverChatNotificationPoller.class){if(g==generation)IN_FLIGHT.set(false);}}});
 }
 private static void show(Context c,JSONObject m){if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(c,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return;String raw=m.optString("message","");String preview=(raw.startsWith("[[IMAGE]]")||raw.startsWith("[[IMAGE2]]"))?"📷 Customer mengirim foto":raw;Intent i=new Intent(c,DriverChatRoomActivity.class);i.putExtra("order_id",m.optString("order_id",""));i.putExtra("order_db_id",m.optString("order_db_id",""));i.putExtra("order_source",m.optString("source","orders"));i.putExtra("room_id",m.optString("room_id",""));i.putExtra("participant_name",m.optString("participant_name","Customer"));i.putExtra("order_type",m.optString("order_type",""));i.putExtra("order_status",m.optString("status",""));i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);int id=Math.max(1,m.optInt("id",1));PendingIntent pi=PendingIntent.getActivity(c,id,i,PendingIntent.FLAG_UPDATE_CURRENT|(Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0));int icon=c.getResources().getIdentifier("ic_notification","drawable",c.getPackageName());if(icon==0)icon=c.getApplicationInfo().icon;NotificationCompat.Builder b=new NotificationCompat.Builder(c,CHANNEL_ID).setSmallIcon(icon).setContentTitle(m.optString("participant_name","Pesan Customer")).setContentText(preview).setStyle(new NotificationCompat.BigTextStyle().bigText(preview)).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_MESSAGE).setContentIntent(pi);NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);if(nm!=null)nm.notify(id,b.build());}
 private static void createChannel(Context c){if(Build.VERSION.SDK_INT<26)return;NotificationManager nm=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);if(nm!=null){NotificationChannel ch=new NotificationChannel(CHANNEL_ID,"Pesan Customer untuk Driver",NotificationManager.IMPORTANCE_HIGH);ch.enableVibration(true);nm.createNotificationChannel(ch);}}
 private static String norm(String s){return s==null?"":s.trim().replace('_','-').toUpperCase();}
}
