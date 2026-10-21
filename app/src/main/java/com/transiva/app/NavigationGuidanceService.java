package com.transiva.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.*;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import org.json.*;
import java.util.*;

/** User-started navigation only. Does not upload driver location or mutate an order. */
public final class NavigationGuidanceService extends Service implements LocationListener {
    private static final String CHANNEL="driver_navigation_guidance_v1",STOP="navigation.stop";
    private static final int NOTICE=2248;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final NavigationRecoveryPolicy recovery=new NavigationRecoveryPolicy();
    private final NavigationDeviationDetector deviation=new NavigationDeviationDetector();
    private final SmoothLocationEngine smooth=new SmoothLocationEngine(5000);
    private final List<double[]> shape=new ArrayList<>();private final List<Double> lengths=new ArrayList<>();
    private LocationManager locations;private Location last;private long fixAt,savedAt,noticeAt;
    private NavigationOrderSync orderSync;private final NavigationRefreshPolicy refresh=new NavigationRefreshPolicy();private boolean evaluation;
    private NavigationVoiceController voice;private NavigationInstructionController instructions;
    private NavigationRouteCoordinator requests;private DriverSessionOwner owner;
    private JSONObject snapshot;private StableRouteEngine.Result route;private String routeId="",key="";
    private boolean closed,listening,wasForeground=true,suspect;
    private double prior=Double.NaN,total;
    static boolean start(Activity a,String routeKey){
        if(a.isFinishing()||a.isDestroyed()||a.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)return false;
        try{ContextCompat.startForegroundService(a,new Intent(a,NavigationGuidanceService.class).putExtra("route_key",routeKey));return true;}
        catch(RuntimeException e){NavigationDiagnostics.error(a,"NAV_GUIDANCE_SERVICE_START_FAILED",e);return false;}
    }
    static void stop(Context c){c.stopService(new Intent(c,NavigationGuidanceService.class));}
    @Override public void onCreate(){
        super.onCreate();owner=DriverSessionOwner.capture(this);locations=(LocationManager)getSystemService(LOCATION_SERVICE);
        voice=new NavigationVoiceController(this,()->!closed&&owner.current());
        instructions=new NavigationInstructionController(new android.widget.TextView(this));
        instructions.setVoice((index,meters,text,arrival)->{if(!NavigationSessionStore.foreground&&!suspect&&last!=null&&SystemClock.elapsedRealtime()-fixAt<5500&&last.getAccuracy()<=35)voice.speak(index,meters,text,arrival,last.hasSpeed()?last.getSpeed():0);});
        requests=new NavigationRouteCoordinator(this,main,()->!closed&&!NavigationSessionStore.foreground,new NavigationRouteCoordinator.Callback(){
            public String currentTargetKey(){return key;}
            public void ready(long generation,StableRouteEngine.RouteSet set){
                if(last!=null&&NavigationGeometry.distanceTo(set.routes.get(0).latLngPoints,last.getLatitude(),last.getLongitude())>Math.max(30,Math.min(60,last.getAccuracy()*1.5))){evaluation=false;recovery.failed(SystemClock.elapsedRealtime());return;}
                long now=SystemClock.elapsedRealtime();refresh.evaluated(now,last==null?0:last.getLatitude(),last==null?0:last.getLongitude());
                if(evaluation&&route!=null){evaluation=false;NavigationProgress remaining=NavigationProgress.remaining(route,prior,total);if(!refresh.better(now,remaining.seconds,set.routes.get(0).durationSeconds)){recovery.reset();return;}}
                evaluation=false;recovery.reset();try{snapshot.put("route",NavigationSessionStore.route(set.routes.get(0))).put("needs_route",false).put("used",set.usedProfile).put("route_id",UUID.randomUUID().toString()).put("saved",System.currentTimeMillis());NavigationSessionStore.save(NavigationGuidanceService.this,snapshot);install();}
                catch(Exception e){recovery.failed(SystemClock.elapsedRealtime());}
            }
            public void failed(Exception e){recovery.failed(SystemClock.elapsedRealtime());TransivaDiagnostics.error(NavigationGuidanceService.this,"navigation","BACKGROUND_ROUTE_FAILED",e);}
        });
        orderSync=new NavigationOrderSync(this,new NavigationOrderSync.Callback(){
            public JSONObject currentOrder(){try{return snapshot==null?null:new JSONObject(snapshot.optString("order"));}catch(JSONException e){return null;}}
            public void updated(JSONObject fresh){applyOrderState(fresh);}
        });
    }
    private void applyOrderState(JSONObject fresh){
        if(closed||NavigationSessionStore.foreground||snapshot==null)return;
        try{
            JSONObject old=new JSONObject(snapshot.optString("order"));if(!NavigationTargetPolicy.same(old,fresh))return;
            if(NavigationTargetPolicy.terminal(fresh)){NavigationSessionStore.clear(this);stopSelf();return;}
            java.util.Iterator<String> names=fresh.keys();while(names.hasNext()){String field=names.next();old.put(field,fresh.opt(field));}
            NavigationTargetPolicy.Target target=NavigationTargetPolicy.resolve(old,snapshot.optString("mode"),snapshot.optDouble("lat"),snapshot.optDouble("lng"));
            String base=NavigationTargetPolicy.id(old)+"|"+NavigationTargetPolicy.source(old)+"|"+target.mode+"|"+target.lat+"|"+target.lng;
            String nextKey=base+"|"+snapshot.optString("profile","car");
            snapshot.put("order",NavigationSessionStore.compactOrder(old).toString()).put("saved",System.currentTimeMillis());
            if(!nextKey.equals(key)){
                requests.invalidate();recovery.reset();refresh.reset();evaluation=false;key=nextKey;
                snapshot.put("key",key).put("base",base).put("mode",target.mode).put("lat",target.lat).put("lng",target.lng).put("manual_until",0).put("needs_route",true).put("route_id",UUID.randomUUID().toString());snapshot.remove("route");
                install();recovery.require(SystemClock.elapsedRealtime());request();
                getSystemService(NotificationManager.class).notify(NOTICE,notification("Tujuan diperbarui • menyiapkan rute"));
            }
            NavigationSessionStore.save(this,snapshot);
        }catch(JSONException e){TransivaDiagnostics.error(this,"navigation","BACKGROUND_STATE_APPLY_FAILED",e);}
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent!=null&&STOP.equals(intent.getAction())){NavigationSessionStore.clear(this);stopSelf();return START_NOT_STICKY;}
        snapshot=NavigationSessionStore.read(this);
        if(snapshot==null||!owner.current()||intent==null||!snapshot.optString("key").equals(intent.getStringExtra("route_key"))){stopSelf();return START_NOT_STICKY;}
        try{if(Build.VERSION.SDK_INT>=26){NotificationChannel channel=new NotificationChannel(CHANNEL,"Panduan navigasi",NotificationManager.IMPORTANCE_LOW);channel.setSound(null,null);getSystemService(NotificationManager.class).createNotificationChannel(channel);}startForeground(NOTICE,notification("Panduan perjalanan aktif"));}
        catch(RuntimeException e){TransivaDiagnostics.error(this,"navigation","GUIDANCE_FOREGROUND_FAILED",e);stopSelf();return START_NOT_STICKY;}
        requests.invalidate();recovery.reset();evaluation=false;wasForeground=true;routeId="";stopLocation();voice.resumed(false);orderSync.active(false);
        main.removeCallbacks(tick);main.post(tick);return START_NOT_STICKY;
    }
    private Notification notification(String detail){
        Intent open=new Intent(this,DriverNavigationActivity.class).putExtra("order_json",snapshot.optString("order")).putExtra("target_mode",snapshot.optString("mode")).putExtra("target_lat",snapshot.optDouble("lat")).putExtra("target_lng",snapshot.optDouble("lng")).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content=PendingIntent.getActivity(this,NOTICE,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,NOTICE,new Intent(this,NavigationGuidanceService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_directions).setContentTitle("Navigasi Transiva").setContentText(detail).setContentIntent(content).setOngoing(true).setOnlyAlertOnce(true).addAction(0,"Akhiri navigasi",stop).build();
    }
    private final Runnable tick=new Runnable(){public void run(){
        if(closed)return;
        if(NavigationSessionStore.stopped||!owner.current()){stopSelf();return;}
        boolean foreground=NavigationSessionStore.foreground;
        if(foreground){orderSync.active(false);if(!wasForeground){requests.invalidate();if(evaluation)recovery.reset();evaluation=false;stopLocation();voice.resumed(false);}wasForeground=true;}
        else{
            if(wasForeground){snapshot=NavigationSessionStore.read(NavigationGuidanceService.this);if(snapshot==null){stopSelf();return;}key=snapshot.optString("key");install();if(snapshot.optBoolean("needs_route"))recovery.require(SystemClock.elapsedRealtime());wasForeground=false;startLocation();voice.resumed(true);orderSync.active(true);}
            if(recovery.due(SystemClock.elapsedRealtime())&&!requests.busy())request();
            if(SystemClock.elapsedRealtime()-noticeAt>15000){noticeAt=SystemClock.elapsedRealtime();getSystemService(NotificationManager.class).notify(NOTICE,notification(last==null||SystemClock.elapsedRealtime()-fixAt>12000?"Menunggu GPS • buka navigasi untuk detail":"Panduan aktif • ketuk untuk kembali"));}
        }
        main.postDelayed(this,1000);
    }};
    private void install(){
        voice.enabled(getSharedPreferences("navigation_guidance",0).getBoolean("voice",true));
        String id=snapshot.optString("route_id");if(id.equals(routeId)&&route!=null)return;
        route=NavigationSessionStore.route(snapshot);shape.clear();lengths.clear();total=0;prior=Double.NaN;routeId=id;deviation.reset();suspect=false;
        if(route==null){recovery.require(SystemClock.elapsedRealtime());return;}
        for(int i=0;i<route.latLngPoints.length();i++){JSONArray p=route.latLngPoints.optJSONArray(i);if(p==null)continue;double[] xy={p.optDouble(0),p.optDouble(1)};if(!shape.isEmpty()){double[] q=shape.get(shape.size()-1);total+=NavigationGeometry.meters(q[0],q[1],xy[0],xy[1]);}shape.add(xy);lengths.add(total);}
        refresh.hold(SystemClock.elapsedRealtime(),snapshot.optLong("manual_until")-System.currentTimeMillis());instructions.setDestination(snapshot.optString("mode"));voice.route(routeId);voice.enabled(getSharedPreferences("navigation_guidance",0).getBoolean("voice",true));
    }
    @android.annotation.SuppressLint("MissingPermission") private void startLocation(){
        if(listening||locations==null)return;
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){stopSelf();return;}
        try{locations.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,1,this,Looper.getMainLooper());listening=true;}catch(RuntimeException e){TransivaDiagnostics.error(this,"navigation","GUIDANCE_GPS_FAILED",e);}
        try{locations.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,2500,2,this,Looper.getMainLooper());listening=true;}catch(RuntimeException e){TransivaDiagnostics.error(this,"navigation","GUIDANCE_NETWORK_FAILED",e);}
    }
    private void stopLocation(){try{if(locations!=null)locations.removeUpdates(this);}catch(RuntimeException e){TransivaDiagnostics.error(this,"navigation","GUIDANCE_STOP_LOCATION_FAILED",e);}listening=false;}
    private void request(){
        if(last==null||!NavigationTargetPolicy.valid(snapshot.optDouble("lat"),snapshot.optDouble("lng"))||SystemClock.elapsedRealtime()-fixAt>12000||requests.busy()||NavigationSessionStore.foreground)return;
        requests.request(key,last.getLatitude(),last.getLongitude(),snapshot.optDouble("lat"),snapshot.optDouble("lng"),snapshot.optString("profile","car"),last.hasBearing()&&last.hasSpeed()&&last.getSpeed()>=2.5?last.getBearing():Double.NaN,true);
    }
    @Override public void onLocationChanged(Location raw){
        if(closed||NavigationSessionStore.foreground||!owner.current()||raw==null)return;
        if(last!=null&&LocationManager.NETWORK_PROVIDER.equals(raw.getProvider())&&LocationManager.GPS_PROVIDER.equals(last.getProvider())&&SystemClock.elapsedRealtime()-fixAt<5500&&last.getAccuracy()<=45)return;
        SmoothLocationEngine.Fix accepted=smooth.offer(raw);if(accepted==null)return;
        long now=SystemClock.elapsedRealtime();long elapsed=now-fixAt;Location previous=last;if(elapsed>12000)prior=Double.NaN;
        last=new Location(raw);if(!last.hasAccuracy())last.setAccuracy(50);
        if(!last.hasSpeed()&&previous!=null&&elapsed>=300&&elapsed<=15000)last.setSpeed((float)Math.min(50,NavigationGeometry.meters(previous.getLatitude(),previous.getLongitude(),last.getLatitude(),last.getLongitude())/(elapsed/1000d)));
        raw=last;fixAt=now;
        if(route==null){if(!requests.busy()&&(!recovery.needed()||recovery.due(now)))request();return;}
        if(!refresh.initialized())refresh.evaluated(now,raw.getLatitude(),raw.getLongitude());
        NavigationGeometry.Projection p=NavigationGeometry.project(shape,lengths,raw.getLatitude(),raw.getLongitude(),prior);if(p.distance<Math.max(12,raw.getAccuracy()*1.5))prior=p.progress;
        double speed=raw.hasSpeed()?raw.getSpeed():0,bearing=raw.hasBearing()?raw.getBearing():Double.NaN;
        double angle=Double.isFinite(bearing)&&Double.isFinite(p.bearing)?Math.abs(((bearing-p.bearing+540)%360)-180):0;
        suspect=p.distance>Math.max(12,raw.getAccuracy()*1.8)||(angle>125&&speed>=2.5&&raw.getAccuracy()<=25);
        if(deviation.offer(now,raw.getLatitude(),raw.getLongitude(),raw.hasAccuracy()?raw.getAccuracy():50,speed,bearing,p.bearing,p.distance)!=NavigationDeviationDetector.Reason.NONE){evaluation=false;recovery.require(now);request();}
        else if(!suspect&&!recovery.needed()&&!requests.busy()&&refresh.due(now,raw.getLatitude(),raw.getLongitude(),speed,raw.getAccuracy(),NavigationProgress.remaining(route,p.progress,total).meters)){evaluation=true;request();}
        NavigationProgress remaining=NavigationProgress.remaining(route,p.progress,total);
        boolean arrived=remaining.meters<=25&&raw.getAccuracy()<=30&&NavigationGeometry.meters(raw.getLatitude(),raw.getLongitude(),snapshot.optDouble("lat"),snapshot.optDouble("lng"))<=25;
        if(!suspect&&p.distance<=Math.max(12,Math.min(42,raw.getAccuracy()*1.5)))instructions.update(route.maneuvers,route.distanceMeters-remaining.meters,arrived);
        if(now-savedAt>10000){savedAt=now;try{snapshot.put("saved",System.currentTimeMillis()).put("progress",p.progress);NavigationSessionStore.save(this,snapshot);}catch(Exception ignored){}}
    }
    @Override public void onProviderEnabled(String provider){} @Override public void onProviderDisabled(String provider){} @Override public void onStatusChanged(String provider,int status,Bundle extras){}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){closed=true;main.removeCallbacksAndMessages(null);stopLocation();if(orderSync!=null)orderSync.close();if(requests!=null)requests.close();if(voice!=null)voice.close();if(owner!=null)owner.close();stopForeground(true);super.onDestroy();}
}
