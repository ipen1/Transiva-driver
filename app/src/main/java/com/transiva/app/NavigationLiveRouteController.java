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
    private final NavigationRefreshPolicy refresh=new NavigationRefreshPolicy();
    private final NavigationOrderSync orderSync;
    private boolean evaluation;
    private final NavigationRecoveryPolicy recovery=new NavigationRecoveryPolicy();
    private boolean running,restoring,serviceReady,everResumed,restoredOnly;
    private final NavigationEtaEstimator etaEstimator=new NavigationEtaEstimator();
    private long lastCheckpoint;
    private String guidanceTicket="",guidanceOwner="";
    private int guidanceAttempts;
    private long guidanceRetryAt;
    private void beginGuidance(boolean reset){
        if(closed||!coordinator.valid()||!host.activityResumed)return;
        if(reset)guidanceAttempts=0;
        guidanceAttempts++;guidanceOwner=NavigationSessionStore.identity(host);serviceReady=false;guidanceRetryAt=0;
        guidanceTicket=NavigationGuidanceHealth.begin(guidanceOwner,key(),SystemClock.elapsedRealtime());
        if(!NavigationGuidanceService.start(host,key(),guidanceTicket))guidanceFailure();
    }
    private void guidanceFailure(){
        serviceReady=false;options.guidanceFailure(true);
        if(guidanceRetryAt==0)guidanceRetryAt=SystemClock.elapsedRealtime()+Math.min(30000,2000L*(1L<<Math.min(3,Math.max(0,guidanceAttempts-1))));
    }
    private void checkGuidance(){
        if(!running||!host.activityResumed||!coordinator.valid())return;
        NavigationGuidanceHealth.State state=NavigationGuidanceHealth.status(guidanceTicket,guidanceOwner,key(),SystemClock.elapsedRealtime());
        if(state==NavigationGuidanceHealth.State.READY){serviceReady=true;guidanceRetryAt=0;options.guidanceFailure(false);return;}
        if(state==NavigationGuidanceHealth.State.PENDING)return;
        guidanceFailure();
        if(guidanceAttempts<3&&SystemClock.elapsedRealtime()>=guidanceRetryAt)beginGuidance(false);
    }
    private final String pageId=java.util.UUID.randomUUID().toString();
    private String restoredId="";
    private android.net.ConnectivityManager.NetworkCallback network;
    private final android.os.Handler retryMain=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable retry= new Runnable(){public void run(){if(closed||!running)return;if(NavigationSessionStore.stopped){host.finish();return;}checkGuidance();if(coordinator.valid()&&!coordinator.busy()&&recovery.due(SystemClock.elapsedRealtime()))request(true);retryMain.postDelayed(this,1000);}};
    private final NavigationDeviationDetector deviation=new NavigationDeviationDetector();
    private final NavigationVoiceController voice;
    private final NavigationOptionsController options;
    private StableRouteEngine.RouteSet available;
    private StableRouteEngine.Result current;
    private String profile,targetKey="";
    private long lastBadgeAt,routeGeneration,lastProgressAt=-1000;
    private int selected;
    private double originLat,originLng,availableLat,availableLng;
    private boolean suspect,failed,showChoices,closed,reacquiring;private long lastEvidenceAt;
    NavigationLiveRouteController(DriverNavigationActivityLayer2 h){
        host=h;profile="car".equals(h.vehicleType)?"car":"motor";
        voice=new NavigationVoiceController(h);
        boolean enabled=h.getSharedPreferences("navigation_guidance",0).getBoolean("voice",true);voice.enabled(enabled);voice.resumed(h.activityResumed);
        coordinator=new NavigationRouteCoordinator(h,h.main,new NavigationRouteCoordinator.Callback(){
            public String currentTargetKey(){return key();}
            public void ready(long generation,StableRouteEngine.RouteSet result){
                host.routeInFlight=false;if(host.routeScheduler!=null)host.routeScheduler.endLoading();
                if(NavigationGeometry.meters(originLat,originLng,host.driverLat,host.driverLng)>40&&!containsCurrentPosition(result.routes.get(0))){evaluation=false;recovery.failed(SystemClock.elapsedRealtime());return;}
                long now=SystemClock.elapsedRealtime();refresh.evaluated(now,originLat,originLng);
                if(evaluation&&current!=null){
                    evaluation=false;double length; synchronized(host.routePoints){length=host.routeCumulativeMeters.isEmpty()?0:host.routeCumulativeMeters.get(host.routeCumulativeMeters.size()-1);}
                    NavigationProgress remaining=NavigationProgress.remaining(current,host.lastMatchedProgressMeters,length);
                    recovery.reset();failed=false;
                    if(!refresh.betterRoute(now,current,remaining.seconds,result.routes.get(0))){mapReady();return;}
                }
                evaluation=false;routeGeneration=generation;restoredOnly=false;available=result;availableLat=originLat;availableLng=originLng;failed=false;apply(0);
                mapReady();
                if(showChoices){showChoices=false;alternatives();}
            }
            public void failed(Exception error){
                host.routeInFlight=false;failed=true;if(host.routeScheduler!=null)host.routeScheduler.endLoading();
                recovery.failed(SystemClock.elapsedRealtime());host.routeFailureCount++;NavigationDiagnostics.error(host,"NAV_ROUTE_FAILED",error);
                mapReady();
                if(host.routeBadge!=null)host.routeBadge.setText(current==null?"Rute belum tersedia • mencoba kembali…":"Koneksi rute terputus • panduan tersimpan");
            }
        });
        orderSync=new NavigationOrderSync(h,new NavigationOrderSync.Callback(){
            public org.json.JSONObject currentOrder(){return host.order;}
            public void updated(org.json.JSONObject fresh){applyOrderState(fresh,true);}
        });
        options=new NavigationOptionsController(h,h.navigationRoot,new NavigationOptionsController.Listener(){
            public void profile(String value){if(!closed&&coordinator.valid()&&!value.equals(profile)){profile=value;targetChanged();}}
            public void voice(boolean value){if(closed||!coordinator.valid())return;voice.enabled(value);host.getSharedPreferences("navigation_guidance",0).edit().putBoolean("voice",value).apply();}
            public void alternatives(){NavigationLiveRouteController.this.alternatives();}
        });
        options.voice(enabled);voice.status(ready->{if(!closed&&coordinator.valid())options.voiceReady(ready);});options.profile(profile,profile);
        h.instructionController.setVoice(new NavigationGuidanceVoice(voice,
                ()->coordinator.valid()&&!suspect&&host.activityResumed&&SystemClock.elapsedRealtime()-host.lastFixRealtimeMs<5500&&host.lastGpsAccuracyM<=35&&NavigationGuidanceVoice.fresh(host.lastLocation),
                ()->host.currentSpeedKmh/3.6));
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
    private String base(){return NavigationTargetPolicy.id(host.order)+"|"+NavigationTargetPolicy.source(host.order)+"|"+host.targetMode+"|"+host.targetLat()+"|"+host.targetLng();}
    private String key(){return base()+"|"+profile;}
    void request(boolean force){
        if(closed||!running||!coordinator.valid()||!host.valid(host.driverLat,host.driverLng)||!host.valid(host.targetLat(),host.targetLng())||host.lastFixRealtimeMs<=0||SystemClock.elapsedRealtime()-host.lastFixRealtimeMs>12000)return;
        String key=key();if(coordinator.busy()&&key.equals(targetKey))return;
        if(key.equals(targetKey)&&recovery.needed()&&!recovery.due(SystemClock.elapsedRealtime()))return;
        if(!force&&current!=null&&key.equals(targetKey))return;
        recovery.require(SystemClock.elapsedRealtime());
        targetKey=key;originLat=host.driverLat;originLng=host.driverLng;
        host.lastRouteRequestAt=System.currentTimeMillis();host.routeInFlight=true;
        if(host.routeScheduler!=null)host.routeScheduler.beginLoading(4);
        double heading=host.currentSpeedKmh>=9&&host.lastGpsAccuracyM<=25?host.currentBearing:Double.NaN;
        coordinator.request(key,originLat,originLng,host.targetLat(),host.targetLng(),profile,heading,force);
        host.routeInFlight=coordinator.busy();
    }
    void targetChanged(){
        if(closed||!coordinator.valid())return;
        orderSync.refresh();coordinator.invalidate();recovery.reset();refresh.reset();evaluation=false;voice.route("target-"+SystemClock.elapsedRealtime());deviation.reset();available=null;current=null;selected=0;suspect=false;failed=false;
        host.setRoutePoints(new JSONArray());host.routeManeuvers=new JSONArray();host.pendingRouteGeoJson=host.emptyFeatureCollection();host.updateRemainingRouteLine(true);
        host.instructionController.setDestination(host.targetMode);options.profile(profile,profile);targetKey=key();checkpoint();if(running)beginGuidance(true);request(true);
    }
    void fix(Location raw){
        if(closed||!running||!coordinator.valid()||raw==null)return;
        long now=SystemClock.elapsedRealtime();
        if(lastEvidenceAt>0&&(now<lastEvidenceAt||now-lastEvidenceAt>12000))reacquiring=true;lastEvidenceAt=now;
        if(!key().equals(targetKey)){targetChanged();return;}
        if(current==null){if(!coordinator.busy()&&(!recovery.needed()||recovery.due(SystemClock.elapsedRealtime())))request(false);return;}
        if(!refresh.initialized())refresh.evaluated(SystemClock.elapsedRealtime(),raw.getLatitude(),raw.getLongitude());
        NavigationGeometry.Projection projection;
        synchronized(host.routePoints){projection=NavigationGeometry.project(host.routePoints,host.routeCumulativeMeters,raw.getLatitude(),raw.getLongitude(),host.lastMatchedProgressMeters,raw.hasBearing()?raw.getBearing():Double.NaN,raw.hasAccuracy()?raw.getAccuracy():50,raw.hasSpeed()?raw.getSpeed():host.currentSpeedKmh/3.6);}
        double etaLength; synchronized(host.routePoints){etaLength=host.routeCumulativeMeters.isEmpty()?0:host.routeCumulativeMeters.get(host.routeCumulativeMeters.size()-1);}
        etaEstimator.offer(SystemClock.elapsedRealtime(),raw.hasSpeed()?raw.getSpeed():host.currentSpeedKmh/3.6,raw.hasAccuracy()?raw.getAccuracy():50,projection.distance<=20,NavigationProgress.expectedSpeed(current,projection.progress,etaLength));
        double accuracy=raw.hasAccuracy()?Math.max(1,raw.getAccuracy()):50;
        double speed=raw.hasSpeed()?Math.max(0,raw.getSpeed()):host.currentSpeedKmh/3.6;
        double bearing=raw.hasBearing()?raw.getBearing():Double.NaN;
        double angle=Double.isFinite(bearing)&&Double.isFinite(projection.bearing)?Math.abs(((bearing-projection.bearing+540)%360)-180):0;
        suspect=projection.distance>Math.max(12,accuracy*1.8)||(angle>125&&speed>=2.5&&accuracy<=25);
        if(reacquiring&&accuracy<=35){
            if(suspect&&projection.distance>Math.max(30,accuracy*1.8)){reacquiring=false;evaluation=false;recovery.require(now);request(true);return;}
            if(!suspect)reacquiring=false;
        }
        NavigationDeviationDetector.Reason reason=deviation.offer(SystemClock.elapsedRealtime(),raw.getLatitude(),raw.getLongitude(),accuracy,speed,bearing,projection.bearing,projection.distance);
        if(reason!=NavigationDeviationDetector.Reason.NONE){NavigationDiagnostics.event(host,"NAV_REROUTE_"+reason.name(),null);evaluation=false;recovery.require(SystemClock.elapsedRealtime());request(true);}
        else if(!suspect&&!recovery.needed()&&!coordinator.busy()&&refresh.due(SystemClock.elapsedRealtime(),raw.getLatitude(),raw.getLongitude(),speed,accuracy,current.distanceMeters-Math.max(0,projection.progress))){evaluation=true;request(true);}
    }
    private void apply(int index){
        if(closed||available==null||index<0||index>=available.routes.size())return;
        if(!restoring)restoredId=java.util.UUID.randomUUID().toString();selected=index;current=available.routes.get(index);reacquiring=restoring;recovery.reset();etaEstimator.reset();suspect=false;deviation.routeChanged();
        try{host.pendingRouteGeoJson=host.routeGeoJson(current.pointsJson());}catch(Exception e){NavigationDiagnostics.error(host,"NAV_GEOMETRY_FAILED",e);return;}host.setRoutePoints(current.latLngPoints);host.routeManeuvers=current.maneuvers;host.instructionController.setTarget(host.targetMode,host.targetLat(),host.targetLng());
        host.pendingRouteKm=current.distanceMeters/1000;host.pendingRouteSeconds=current.durationSeconds;host.lastRouteSuccessAt=System.currentTimeMillis();host.routeFailureCount=0;
        host.lastRenderedRouteIndex=-1;host.lastRenderedRouteProgressMeters=Double.NaN;
        voice.route(restoredId+"-"+index);options.profile(profile,available.usedProfile);
        boolean firstPose=!host.displayInitialized;
        DriverNavigationActivityLayer1.SnapPoint match=host.snapToRoute(host.driverLat,host.driverLng);
        if(!host.displayInitialized){host.displayLat=match.lat;host.displayLng=match.lng;host.displayInitialized=true;}if(firstPose&&match.onRoute)host.snappedBearing=match.bearing;
        if(host.activityResumed||host.inPictureInPicture){host.updateNativePosition(firstPose);host.updateRemainingRouteLine(true);}lastBadgeAt=0;lastProgressAt=-1000;progress(match.progressMeters);
        if(!restoring)checkpoint();
        NavigationDiagnostics.event(host,"NAV_ROUTE_READY",null);NavigationHealthTelemetry.mark(host,"route");
    }
    void progress(double geometryProgress){
        if(closed||current==null)return;
        long tick=SystemClock.elapsedRealtime();if(running&&tick-lastCheckpoint>10000)checkpoint();if(tick-lastProgressAt<250)return;lastProgressAt=tick;
        if(!coordinator.valid()){voice.resumed(false);return;}
        if(!Double.isFinite(geometryProgress))geometryProgress=Double.isFinite(host.lastMatchedProgressMeters)?host.lastMatchedProgressMeters:0;
        double length; synchronized(host.routePoints){length=host.routeCumulativeMeters.isEmpty()?0:host.routeCumulativeMeters.get(host.routeCumulativeMeters.size()-1);}
        NavigationProgress left=NavigationProgress.remaining(current,geometryProgress,length);
        double along=current.distanceMeters-left.meters;
        boolean arrived=left.meters<=25&&host.lastGpsAccuracyM<=30&&NavigationGeometry.meters(host.driverLat,host.driverLng,host.targetLat(),host.targetLng())<=25;
        if(!suspect)host.instructionController.update(host.routeManeuvers,along,arrived);
        long now=SystemClock.elapsedRealtime();if(now-lastBadgeAt<1000||coordinator.busy())return;lastBadgeAt=now;
        if(host.lastFixRealtimeMs<=0||now-host.lastFixRealtimeMs>12000){host.routeBadge.setText("Menunggu GPS • perkiraan tersimpan");return;}
        if(suspect){host.routeBadge.setText("Memeriksa arah/jalur • panduan sedang diperbarui");return;}
        double targetGap=NavigationGeometry.meters(host.driverLat,host.driverLng,host.targetLat(),host.targetLng());
        if(left.meters<=20&&targetGap>35){host.routeBadge.setText("Ujung rute jalan\nTujuan masih ±"+Math.round(targetGap)+" m");return;}
        double seconds=etaEstimator.seconds(now,left.meters,left.seconds,current.distanceMeters,current.durationSeconds);
        String eta=new SimpleDateFormat("HH.mm",new Locale("id","ID")).format(new Date(System.currentTimeMillis()+(long)(seconds*1000)));
        int mins=(int)Math.ceil(seconds/60);
        String badge=arrived?"Tiba di "+NavigationInstructionController.destinationLabel(host.targetMode):String.format(Locale.US,"Sisa %.1f km • ±%d menit\nTiba %s%s",left.meters/1000,mins,eta,failed?" • rute tersimpan":"");
        host.routeBadge.setText(badge+(!arrived&&!current.comfortableRoads?"\nKualitas jalan belum terverifikasi":""));
    }
    private void alternatives(){
        if(closed||!coordinator.valid()||!host.activityResumed)return;
        if(coordinator.busy()){showChoices=true;return;}
        if(restoredOnly||available==null||NavigationGeometry.meters(availableLat,availableLng,host.driverLat,host.driverLng)>40){showChoices=true;request(true);return;}
        StableRouteEngine.RouteSet snapshot=available;
        options.choices(snapshot.routes,selected,index->{if(!coordinator.valid()||snapshot!=available)return;if(NavigationGeometry.meters(availableLat,availableLng,host.driverLat,host.driverLng)>40){showChoices=true;request(true);}else {refresh.manual(SystemClock.elapsedRealtime());apply(index);}});
    }
    void resumed(boolean value){
        if(value&&NavigationTargetPolicy.terminal(host.order)){NavigationSessionStore.clear(host);NavigationGuidanceService.stop(host);host.finish();return;}
        if(value&&NavigationSessionStore.stopped&&everResumed){host.finish();return;}
        if(value){NavigationSessionStore.foregroundOwner=pageId;NavigationSessionStore.foreground=true;}
        else if(pageId.equals(NavigationSessionStore.foregroundOwner))NavigationSessionStore.foreground=false;
        if(value){NavigationSessionStore.stopped=false;everResumed=true;adoptCheckpointOrder();if(host.isFinishing()||NavigationSessionStore.stopped)return;restore();checkpoint();beginGuidance(true);watchNetwork();}
        else {checkpoint();unwatchNetwork();}
        running=value;orderSync.active(value);voice.resumed(value);retryMain.removeCallbacks(retry);
        if(value){retryMain.post(retry);}else{coordinator.invalidate();if(evaluation)recovery.reset();evaluation=false;host.routeInFlight=false;if(host.routeScheduler!=null)host.routeScheduler.endLoading();}
    }
    private void checkpoint(){
        if(closed||restoring||!coordinator.valid()||!pageId.equals(NavigationSessionStore.foregroundOwner))return;
        try{org.json.JSONObject x=new org.json.JSONObject().put("owner",NavigationSessionStore.identity(host)).put("saved",System.currentTimeMillis()).put("base",base()).put("key",key()).put("profile",profile).put("used",available==null?profile:available.usedProfile).put("order",NavigationSessionStore.compactOrder(host.order).toString()).put("mode",host.targetMode).put("lat",host.targetLat()).put("lng",host.targetLng()).put("manual_until",System.currentTimeMillis()+refresh.manualRemaining(SystemClock.elapsedRealtime())).put("needs_route",recovery.needed()).put("route_id",restoredId.isEmpty()?routeGeneration+"-"+selected:restoredId);
            if(current!=null){org.json.JSONObject savedRoute=NavigationSessionStore.route(current);x.put("route",savedRoute);}
            NavigationSessionStore.save(host,x);lastCheckpoint=SystemClock.elapsedRealtime();
        }catch(Exception e){NavigationDiagnostics.error(host,"NAV_CHECKPOINT_FAILED",e);}
    }
    private void restore(){
        org.json.JSONObject x=NavigationSessionStore.read(host);if(x==null||!base().equals(x.optString("base")))return;
        StableRouteEngine.Result result=NavigationSessionStore.route(x);if(result==null)return;
        String storedProfile=x.optString("profile","car");if(!storedProfile.equals("car")&&!storedProfile.equals("motor"))return;
        String id=x.optString("route_id");if(current!=null&&id.equals(restoredId))return;
        coordinator.invalidate();profile=storedProfile;targetKey=key();restoredId=id;restoring=true;restoredOnly=true;
        try{String used=x.optString("used","car");available=new StableRouteEngine.RouteSet(java.util.Collections.singletonList(result),profile,used,!profile.equals(used));availableLat=host.driverLat;availableLng=host.driverLng;apply(0);}
        finally{restoring=false;}
        refresh.hold(SystemClock.elapsedRealtime(),x.optLong("manual_until")-System.currentTimeMillis());
        if(result.roadPolicy<2||x.optBoolean("needs_route")||System.currentTimeMillis()-x.optLong("saved")>120000)recovery.require(SystemClock.elapsedRealtime());
    }
    private void adoptCheckpointOrder(){
        org.json.JSONObject x=NavigationSessionStore.read(host);if(x==null)return;
        try{org.json.JSONObject fresh=new org.json.JSONObject(x.optString("order"));if(NavigationTargetPolicy.same(host.order,fresh))applyOrderState(fresh,false);}
        catch(org.json.JSONException e){NavigationDiagnostics.error(host,"NAV_RESTORE_ORDER_FAILED",e);}
    }
    private void applyOrderState(org.json.JSONObject fresh,boolean notify){
        if(closed||!coordinator.valid()||!NavigationTargetPolicy.same(host.order,fresh))return;
        if(NavigationTargetPolicy.terminal(fresh)){coordinator.invalidate();NavigationSessionStore.clear(host);NavigationGuidanceService.stop(host);host.finish();return;}
        String before=key();
        try{org.json.JSONObject merged=new org.json.JSONObject(host.order.toString());java.util.Iterator<String> fields=fresh.keys();while(fields.hasNext()){String field=fields.next();merged.put(field,fresh.opt(field));}host.order=merged;}
        catch(org.json.JSONException e){NavigationDiagnostics.error(host,"NAV_ORDER_APPLY_FAILED",e);return;}
        NavigationTargetPolicy.Target target=NavigationTargetPolicy.resolve(host.order,host.targetMode,host.explicitTargetLat,host.explicitTargetLng);
        host.targetMode=target.mode;host.explicitTargetLat=target.lat;host.explicitTargetLng=target.lng;
        if(!before.equals(key())){
            if(host.markerController!=null)host.markerController.clear(host.map);host.markerController=new NavigationMarkerController(host,host.order);host.installMarkers();
            if(notify)targetChanged();
            else{coordinator.invalidate();current=null;available=null;targetKey=key();host.setRoutePoints(new JSONArray());host.routeManeuvers=new JSONArray();host.pendingRouteGeoJson=host.emptyFeatureCollection();host.updateRemainingRouteLine(true);host.instructionController.setDestination(host.targetMode);refresh.reset();recovery.require(SystemClock.elapsedRealtime());}
        }
    }
    private void watchNetwork(){
        if(network!=null)return;
        try{android.net.ConnectivityManager cm=(android.net.ConnectivityManager)host.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            network=new android.net.ConnectivityManager.NetworkCallback(){@Override public void onAvailable(android.net.Network n){retryMain.post(()->{if(running&&recovery.needed())recovery.networkAvailable(SystemClock.elapsedRealtime());});}};cm.registerDefaultNetworkCallback(network);
        }catch(RuntimeException e){network=null;NavigationDiagnostics.error(host,"NAV_NETWORK_WATCH_FAILED",e);}
    }
    private void unwatchNetwork(){if(network==null)return;try{((android.net.ConnectivityManager)host.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)).unregisterNetworkCallback(network);}catch(RuntimeException ignored){}network=null;}
    @Override public void close(){closed=true;unwatchNetwork();
        if(host.isFinishing()&&pageId.equals(NavigationSessionStore.foregroundOwner)){NavigationSessionStore.clear(host);NavigationGuidanceService.stop(host);}
        orderSync.close();retryMain.removeCallbacksAndMessages(null);coordinator.close();voice.close();host.routeInFlight=false;}
}
