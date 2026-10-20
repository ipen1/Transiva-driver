package com.transiva.app;

import android.location.Location;
import android.os.SystemClock;
import org.json.JSONArray;
import java.text.SimpleDateFormat;
import java.util.*;

/** Main-thread owner of live geometry, destination generation, progress and route controls. */
final class NavigationLiveRouteController implements AutoCloseable {
    private final DriverNavigationActivityLayer2 host;
    private final NavigationRouteCoordinator coordinator;
    private final NavigationDeviationDetector deviation=new NavigationDeviationDetector();
    private final NavigationVoiceController voice;
    private final NavigationOptionsController options;
    private StableRouteEngine.RouteSet available;
    private StableRouteEngine.Result current;
    private String profile,targetKey="";
    private long lastBadgeAt,routeGeneration,lastProgressAt=-1000;
    private int selected;
    private double originLat,originLng,availableLat,availableLng;
    private boolean suspect,failed,showChoices,closed;
    NavigationLiveRouteController(DriverNavigationActivityLayer2 h){
        host=h;profile="car".equals(h.vehicleType)?"car":"motor";
        voice=new NavigationVoiceController(h);
        boolean enabled=h.getSharedPreferences("navigation_guidance",0).getBoolean("voice",true);voice.enabled(enabled);voice.resumed(h.activityResumed);
        coordinator=new NavigationRouteCoordinator(h,h.main,new NavigationRouteCoordinator.Callback(){
            public String currentTargetKey(){return key();}
            public void ready(long generation,StableRouteEngine.RouteSet result){
                host.routeInFlight=false;if(host.routeScheduler!=null)host.routeScheduler.endLoading();
                if(NavigationGeometry.meters(originLat,originLng,host.driverLat,host.driverLng)>40&&!containsCurrentPosition(result.routes.get(0))){request(true);return;}
                routeGeneration=generation;available=result;availableLat=originLat;availableLng=originLng;failed=false;apply(0);
                mapReady();
                if(showChoices){showChoices=false;alternatives();}
            }
            public void failed(Exception error){
                host.routeInFlight=false;failed=true;if(host.routeScheduler!=null)host.routeScheduler.endLoading();
                host.routeFailureCount++;NavigationDiagnostics.error(host,"NAV_ROUTE_FAILED",error);
                mapReady();
                if(host.routeBadge!=null)host.routeBadge.setText(current==null?"Rute belum tersedia • mencoba kembali…":"Koneksi rute terputus • panduan tersimpan");
            }
        });
        options=new NavigationOptionsController(h,h.navigationRoot,new NavigationOptionsController.Listener(){
            public void profile(String value){if(!closed&&coordinator.valid()&&!value.equals(profile)){profile=value;targetChanged();}}
            public void voice(boolean value){if(closed||!coordinator.valid())return;voice.enabled(value);host.getSharedPreferences("navigation_guidance",0).edit().putBoolean("voice",value).apply();}
            public void alternatives(){NavigationLiveRouteController.this.alternatives();}
        });
        options.voice(enabled);voice.status(ready->{if(!closed&&coordinator.valid())options.voiceReady(ready);});options.profile(profile,profile);
        h.instructionController.setVoice((index,distance,text,arrival)->{
            if(coordinator.valid()&&!suspect&&host.activityResumed&&SystemClock.elapsedRealtime()-host.lastFixRealtimeMs<5500&&host.lastGpsAccuracyM<=35)
                voice.speak(index,distance,text,arrival,host.currentSpeedKmh/3.6);
        });
    }
    private boolean containsCurrentPosition(StableRouteEngine.Result route) {
        java.util.ArrayList<double[]> shape=new java.util.ArrayList<>();java.util.ArrayList<Double> lengths=new java.util.ArrayList<>();double total=0;
        for(int i=0;i<route.latLngPoints.length();i++){
            org.json.JSONArray p=route.latLngPoints.optJSONArray(i);if(p==null||p.length()<2)continue;
            double[] xy={p.optDouble(0),p.optDouble(1)};
            if(!shape.isEmpty()){double[] previous=shape.get(shape.size()-1);total+=NavigationGeometry.meters(previous[0],previous[1],xy[0],xy[1]);}
            shape.add(xy);lengths.add(total);
        }
        return NavigationGeometry.project(shape,lengths,host.driverLat,host.driverLng,Double.NaN).distance<=Math.max(30,Math.min(60,host.lastGpsAccuracyM*1.5));
    }
    void mapReady(){
        if(failed||(available!=null&&available.profileFallback&&"motor".equals(profile))){
            host.showMapFallback(failed?"Rute belum tersedia. Navigasi eksternal tersedia.":"Profil motor khusus belum tersedia.");
            if(host.fallbackButton!=null)host.fallbackButton.setText("motor".equals(profile)?"Google Maps • motor":"Buka Google Maps");
        }else if(host.styleReady)host.hideMapFallback();
    }
    String profile(){return profile;}
    private String key(){return host.order.optString("order_id",host.order.optString("id"))+"|"+host.order.optString("source")+"|"+host.targetMode+"|"+host.targetLat()+"|"+host.targetLng()+"|"+profile;}
    void request(boolean force){
        if(closed||!coordinator.valid()||!host.valid(host.driverLat,host.driverLng)||!host.valid(host.targetLat(),host.targetLng()))return;
        String key=key();if(coordinator.busy()&&!force&&key.equals(targetKey))return;
        if(!force&&current!=null&&key.equals(targetKey))return;
        targetKey=key;originLat=host.driverLat;originLng=host.driverLng;
        host.lastRouteRequestAt=System.currentTimeMillis();host.routeInFlight=true;
        if(host.routeScheduler!=null)host.routeScheduler.beginLoading(4);
        double heading=host.currentSpeedKmh>=9&&host.lastGpsAccuracyM<=25?host.currentBearing:Double.NaN;
        coordinator.request(key,originLat,originLng,host.targetLat(),host.targetLng(),profile,heading,force);
        host.routeInFlight=coordinator.busy();
    }
    void targetChanged(){
        if(closed||!coordinator.valid())return;
        coordinator.invalidate();voice.route("target-"+SystemClock.elapsedRealtime());deviation.reset();available=null;current=null;selected=0;suspect=false;failed=false;
        host.setRoutePoints(new JSONArray());host.routeManeuvers=new JSONArray();host.pendingRouteGeoJson=host.emptyFeatureCollection();host.updateRemainingRouteLine(true);
        host.instructionController.setDestination(host.targetMode);options.profile(profile,profile);targetKey=key();request(true);
    }
    void fix(Location raw){
        if(closed||!coordinator.valid()||raw==null)return;
        if(!key().equals(targetKey)){targetChanged();return;}
        if(current==null){if(!coordinator.busy())request(false);return;}
        NavigationGeometry.Projection projection;
        synchronized(host.routePoints){projection=NavigationGeometry.project(host.routePoints,host.routeCumulativeMeters,raw.getLatitude(),raw.getLongitude(),host.lastMatchedProgressMeters);}
        double accuracy=raw.hasAccuracy()?Math.max(1,raw.getAccuracy()):50;
        double speed=raw.hasSpeed()?Math.max(0,raw.getSpeed()):host.currentSpeedKmh/3.6;
        double bearing=raw.hasBearing()?raw.getBearing():Double.NaN;
        double angle=Double.isFinite(bearing)&&Double.isFinite(projection.bearing)?Math.abs(((bearing-projection.bearing+540)%360)-180):0;
        suspect=projection.distance>Math.max(12,accuracy*1.8)||(angle>125&&speed>=2.5&&accuracy<=25);
        NavigationDeviationDetector.Reason reason=deviation.offer(SystemClock.elapsedRealtime(),raw.getLatitude(),raw.getLongitude(),accuracy,speed,bearing,projection.bearing,projection.distance);
        if(reason!=NavigationDeviationDetector.Reason.NONE){NavigationDiagnostics.event(host,"NAV_REROUTE_"+reason.name(),null);request(true);}
    }
    private void apply(int index){
        if(closed||available==null||index<0||index>=available.routes.size())return;
        selected=index;current=available.routes.get(index);suspect=false;deviation.routeChanged();
        try{host.pendingRouteGeoJson=host.routeGeoJson(current.pointsJson());}catch(Exception e){NavigationDiagnostics.error(host,"NAV_GEOMETRY_FAILED",e);return;}host.setRoutePoints(current.latLngPoints);host.routeManeuvers=current.maneuvers;
        host.pendingRouteKm=current.distanceMeters/1000;host.pendingRouteSeconds=current.durationSeconds;host.lastRouteSuccessAt=System.currentTimeMillis();host.routeFailureCount=0;
        host.lastRenderedRouteIndex=-1;host.lastRenderedRouteProgressMeters=Double.NaN;
        voice.route(routeGeneration+"-"+index);options.profile(profile,available.usedProfile);
        DriverNavigationActivityLayer1.SnapPoint match=host.snapToRoute(host.driverLat,host.driverLng);
        host.displayLat=match.lat;host.displayLng=match.lng;host.displayInitialized=true;if(match.onRoute)host.snappedBearing=match.bearing;
        if(host.activityResumed||host.inPictureInPicture){host.updateNativePosition(true);host.updateRemainingRouteLine(true);}lastBadgeAt=0;lastProgressAt=-1000;progress(match.progressMeters);
        NavigationDiagnostics.event(host,"NAV_ROUTE_READY",null);NavigationHealthTelemetry.mark(host,"route");
    }
    void progress(double geometryProgress){
        if(closed||current==null)return;
        long tick=SystemClock.elapsedRealtime();if(tick-lastProgressAt<250)return;lastProgressAt=tick;
        if(!coordinator.valid()){voice.resumed(false);return;}
        if(!Double.isFinite(geometryProgress))geometryProgress=Double.isFinite(host.lastMatchedProgressMeters)?host.lastMatchedProgressMeters:0;
        double length; synchronized(host.routePoints){length=host.routeCumulativeMeters.isEmpty()?0:host.routeCumulativeMeters.get(host.routeCumulativeMeters.size()-1);}
        NavigationProgress left=NavigationProgress.remaining(current,geometryProgress,length);
        double along=current.distanceMeters-left.meters;
        boolean arrived=left.meters<=25&&host.lastGpsAccuracyM<=30&&NavigationGeometry.meters(host.driverLat,host.driverLng,host.targetLat(),host.targetLng())<=25;
        host.instructionController.update(host.routeManeuvers,along,arrived);
        long now=SystemClock.elapsedRealtime();if(now-lastBadgeAt<1000||coordinator.busy())return;lastBadgeAt=now;
        if(host.lastFixRealtimeMs<=0||now-host.lastFixRealtimeMs>12000){host.routeBadge.setText("Menunggu GPS • perkiraan tersimpan");return;}
        if(suspect){host.routeBadge.setText("Memeriksa arah/jalur • panduan sedang diperbarui");return;}
        double targetGap=NavigationGeometry.meters(host.driverLat,host.driverLng,host.targetLat(),host.targetLng());
        if(left.meters<=20&&targetGap>35){host.routeBadge.setText("Ujung rute jalan\nTujuan masih ±"+Math.round(targetGap)+" m");return;}
        String eta=new SimpleDateFormat("HH.mm",new Locale("id","ID")).format(new Date(System.currentTimeMillis()+(long)(left.seconds*1000)));
        int mins=(int)Math.ceil(left.seconds/60);
        host.routeBadge.setText(arrived?"Tiba di "+NavigationInstructionController.destinationLabel(host.targetMode):String.format(Locale.US,"Sisa %.1f km • ±%d menit\nTiba %s%s",left.meters/1000,mins,eta,failed?" • rute tersimpan":""));
    }
    private void alternatives(){
        if(closed||!coordinator.valid()||!host.activityResumed)return;
        if(coordinator.busy()){showChoices=true;return;}
        if(available==null||NavigationGeometry.meters(availableLat,availableLng,host.driverLat,host.driverLng)>40){showChoices=true;request(true);return;}
        StableRouteEngine.RouteSet snapshot=available;
        options.choices(snapshot.routes,selected,index->{if(!coordinator.valid()||snapshot!=available)return;if(NavigationGeometry.meters(availableLat,availableLng,host.driverLat,host.driverLng)>40){showChoices=true;request(true);}else apply(index);});
    }
    void resumed(boolean value){voice.resumed(value);}
    @Override public void close(){closed=true;coordinator.close();voice.close();host.routeInFlight=false;}
}
