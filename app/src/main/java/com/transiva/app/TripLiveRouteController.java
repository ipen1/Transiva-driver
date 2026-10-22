package com.transiva.app;
import android.location.Location;
import android.os.*;
import org.json.*;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.*;
import java.util.*;

/** Live road route to the same target as full-screen navigation. Fare snapshot remains read-only. */
final class TripLiveRouteController implements AutoCloseable {
    private final DriverTripActivityLayer2 host;private final NavigationRouteCoordinator requests;
    private final NavigationRecoveryPolicy recovery=new NavigationRecoveryPolicy();private final NavigationDeviationDetector deviation=new NavigationDeviationDetector();
    private final NavigationRefreshPolicy refresh=new NavigationRefreshPolicy();private final NavigationEtaEstimator eta=new NavigationEtaEstimator();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final List<double[]> shape=new ArrayList<>();private final List<Double> lengths=new ArrayList<>();
    private StableRouteEngine.Result route;private NavigationTargetPolicy.Target target;private Location last;
    private GoogleMap drawnMap;private Polyline reference;private String key="",used="car",referenceKey="";
    private boolean active,closed,suspect,evaluation,reacquiring;private long fixAt;private double total,prior=Double.NaN,originLat,originLng;
    TripLiveRouteController(DriverTripActivityLayer2 h){host=h;requests=new NavigationRouteCoordinator(h,main,new NavigationRouteCoordinator.Callback(){
        public String currentTargetKey(){return currentKey();}
        public void ready(long generation,StableRouteEngine.RouteSet set){
            if(last!=null&&NavigationGeometry.meters(originLat,originLng,last.getLatitude(),last.getLongitude())>40&&NavigationGeometry.distanceTo(set.routes.get(0).latLngPoints,last.getLatitude(),last.getLongitude())>Math.max(30,Math.min(60,last.hasAccuracy()?last.getAccuracy()*1.5:50))){recovery.failed(SystemClock.elapsedRealtime());evaluation=false;return;}
            long now=SystemClock.elapsedRealtime();refresh.evaluated(now,host.lastDriverLat,host.lastDriverLng);recovery.reset();
            if(evaluation&&route!=null){NavigationProgress p=NavigationProgress.remaining(route,prior,total);evaluation=false;if(!refresh.betterRoute(now,route,p.seconds,set.routes.get(0))){render();return;}}
            evaluation=false;route=set.routes.get(0);used=set.usedProfile;eta.reset();deviation.routeChanged();suspect=false;prior=Double.NaN;install();render();if(!host.overviewMapApplied){host.fitNativeOverview();host.overviewMapApplied=true;}
        }
        public void failed(Exception e){recovery.failed(SystemClock.elapsedRealtime());summary("Rute belum diperbarui • mencoba kembali");}
    });}
    private String profile(){return "car".equals(host.resolveDriverTypeFromOrder())?"car":"motor";}
    private NavigationTargetPolicy.Target currentTarget(){return NavigationTargetPolicy.resolve(host.order,host.routeTargetMode(),Double.NaN,Double.NaN);}
    private String currentKey(){return currentTarget().key(host.order)+"|"+profile();}
    void active(boolean value){if(closed)return;active=value;main.removeCallbacks(tick);if(!value){requests.invalidate();if(evaluation)recovery.reset();evaluation=false;}else main.post(tick);}
    private final Runnable tick=new Runnable(){public void run(){if(closed||!active||!requests.valid())return;if(recovery.due(SystemClock.elapsedRealtime())&&!requests.busy())request(true);render();main.postDelayed(this,1000);}};
    void fix(Location raw){
        if(closed||raw==null)return;
        long now=SystemClock.elapsedRealtime();boolean reacquire=fixAt>0&&(now<fixAt||now-fixAt>12000);
        if(reacquire){reacquiring=true;prior=Double.NaN;suspect=false;deviation.reset();eta.reset();}
        last=new Location(raw);fixAt=now;
        if(!currentKey().equals(key)){request(true);return;}if(route==null){request(false);return;}
        NavigationGeometry.Projection p=NavigationGeometry.project(shape,lengths,raw.getLatitude(),raw.getLongitude(),prior,raw.hasBearing()?raw.getBearing():Double.NaN,raw.hasAccuracy()?raw.getAccuracy():50,raw.hasSpeed()?raw.getSpeed():host.currentSpeedKmh/3.6);
        double acc=raw.hasAccuracy()?Math.max(1,raw.getAccuracy()):50,speed=raw.hasSpeed()?raw.getSpeed():host.currentSpeedKmh/3.6,bearing=raw.hasBearing()?raw.getBearing():Double.NaN;
        double angle=Double.isFinite(bearing)&&Double.isFinite(p.bearing)?Math.abs(((bearing-p.bearing+540)%360)-180):0;
        suspect=p.distance>Math.max(12,acc*1.8)||(angle>125&&speed>=2.5&&acc<=25);
        if(!suspect&&(!reacquiring||acc<=35)&&p.distance<=Math.max(12,Math.min(42,acc*1.5)))prior=p.progress;
        eta.offer(fixAt,speed,acc,!suspect&&p.distance<=20,NavigationProgress.expectedSpeed(route,p.progress,total));
        // A stationary driver can return off the old route after a tunnel/background gap.
        // Rebuild immediately from a fresh accurate fix; ordinary jitter still needs evidence.
        if(reacquiring&&suspect&&acc<=35&&p.distance>Math.max(30,acc*1.8)){
            reacquiring=false;evaluation=false;recovery.require(fixAt);request(true);render();return;
        }
        if(reacquiring&&!suspect&&acc<=35)reacquiring=false;
        if(deviation.offer(fixAt,raw.getLatitude(),raw.getLongitude(),acc,speed,bearing,p.bearing,p.distance)!=NavigationDeviationDetector.Reason.NONE){evaluation=false;recovery.require(fixAt);request(true);}
        else if(!suspect&&!recovery.needed()&&!requests.busy()&&refresh.due(fixAt,raw.getLatitude(),raw.getLongitude(),speed,acc,NavigationProgress.remaining(route,prior,total).meters)){evaluation=true;request(true);}
        render();
    }
    void request(boolean force){
        if(closed||!active||!requests.valid())return;
        if(NavigationTargetPolicy.terminal(host.order)){requests.invalidate();route=null;clearLines();summary("Navigasi berakhir • pesanan selesai/dibatalkan");return;}
        NavigationTargetPolicy.Target next=currentTarget();String nextKey=next.key(host.order)+"|"+profile();
        if(!nextKey.equals(key)){requests.invalidate();key=nextKey;target=next;route=null;visualRevision++;shape.clear();lengths.clear();deviation.reset();recovery.reset();refresh.reset();eta.reset();prior=Double.NaN;evaluation=false;clearLines();}
        target=next;if(!target.valid()||last==null||SystemClock.elapsedRealtime()-fixAt>12000||!NavigationTargetPolicy.valid(host.lastDriverLat,host.lastDriverLng)){summary("Menunggu GPS/tujuan yang valid");return;}
        if(requests.busy()||(recovery.needed()&&!recovery.due(SystemClock.elapsedRealtime())))return;
        render();if(!force&&route!=null)return;
        originLat=host.lastDriverLat;originLng=host.lastDriverLng;recovery.require(SystemClock.elapsedRealtime());
        double heading=last.hasBearing()&&last.hasSpeed()&&last.getSpeed()>=2.5&&last.hasAccuracy()&&last.getAccuracy()<=25?last.getBearing():Double.NaN;
        requests.request(key,originLat,originLng,target.lat,target.lng,profile(),heading,true);
    }
    private long visualRevision;
    long visualRevision(){return visualRevision;}
    NavigationGeometry.Point visualPoint(double progress){return NavigationGeometry.at(shape,lengths,progress);}
    NavigationMotionInterpolator.Pose visualPose(Location fix){
        if(route==null||suspect||shape.size()<2)return null;
        NavigationGeometry.Projection p=NavigationGeometry.project(shape,lengths,fix.getLatitude(),fix.getLongitude(),prior,fix.hasBearing()?fix.getBearing():Double.NaN,fix.hasAccuracy()?fix.getAccuracy():50,fix.hasSpeed()?fix.getSpeed():host.currentSpeedKmh/3.6);double accuracy=fix.hasAccuracy()?fix.getAccuracy():50;
        if(p.distance>Math.max(12,Math.min(35,accuracy*1.5)))return null;NavigationGeometry.Point point=visualPoint(p.progress);if(point==null)return null;
        return new NavigationMotionInterpolator.Pose(point.lat,point.lng,point.bearing,p.progress,fix.hasSpeed()?fix.getSpeed():0,true);
    }
    private void install(){reacquiring=false;visualRevision++;shape.clear();lengths.clear();total=0;for(int i=0;i<route.latLngPoints.length();i++){JSONArray p=route.latLngPoints.optJSONArray(i);if(p==null)continue;double[] xy={p.optDouble(0),p.optDouble(1)};if(!shape.isEmpty()){double[] q=shape.get(shape.size()-1);total+=NavigationGeometry.meters(q[0],q[1],xy[0],xy[1]);}shape.add(xy);lengths.add(total);}drawnMap=null;}
    void render(){
        try{renderMap();}catch(RuntimeException e){NavigationDiagnostics.error(host,"TRIP_LIVE_MAP_FAILED",e);summary("Peta belum siap • panduan rute tetap tersimpan");}
    }
    private void renderMap(){
        if(closed||host.googleMap==null||!host.mapReady)return;
        if(NavigationTargetPolicy.terminal(host.order)){summary("Navigasi berakhir • pesanan selesai/dibatalkan");return;}
        if(route!=null&&drawnMap!=host.googleMap){
            clearLines();List<LatLng> points=host.parseRoutePoints(route.pointsJson());
            Polyline line=host.googleMap.addPolyline(new PolylineOptions().addAll(points).width(host.dp(5)).color(DriverThemeTokens.color(host,"pickup".equals(target.mode)?"#1683FF":"#16A34A")).zIndex(3));
            if("pickup".equals(target.mode)){host.pickupPolyline=line;host.pendingPickupRoutePoints=route.pointsJson();host.pendingDeliveryRoutePoints="";}else{host.deliveryPolyline=line;host.pendingDeliveryRoutePoints=route.pointsJson();host.pendingPickupRoutePoints="";}
            drawnMap=host.googleMap;if(!host.overviewMapApplied){host.fitNativeOverview();host.overviewMapApplied=true;}
        }
        drawReference();if(route==null){summary("Menyiapkan rute jalan…");return;}
        if(last==null||SystemClock.elapsedRealtime()-fixAt>12000){summary("Menunggu GPS • estimasi tersimpan");return;}
        if(suspect){summary("Memeriksa jalur • memperbarui rute");return;}
        NavigationProgress p=NavigationProgress.remaining(route,prior,total);double seconds=eta.seconds(SystemClock.elapsedRealtime(),p.meters,p.seconds,route.distanceMeters,route.durationSeconds);
        double gap=NavigationGeometry.meters(host.lastDriverLat,host.lastDriverLng,target.lat,target.lng);
        String label="pickup".equals(target.mode)?"penjemputan":target.mode.startsWith("waypoint_")?"stop berikutnya":"pengantaran";
        String text=p.meters<=20&&gap>35?"Ujung rute jalan • tujuan masih ±"+Math.round(gap)+" m":String.format(Locale.US,"Ke %s • sisa jalan %.1f km • ±%d menit",label,p.meters/1000,(int)Math.ceil(seconds/60));
        if(!route.comfortableRoads)text+=" • kualitas jalan belum terverifikasi";
        if("motor".equals(profile())&&!"motor".equals(used))text+="\nProfil motor belum tersedia • rute mobil";
        if(recovery.needed())text+=" • memperbarui";summary(text);
    }
    private void drawReference(){
        JSONObject snapshot=host.order==null?null:host.order.optJSONObject("server_route"),payload=snapshot==null?null:snapshot.optJSONObject("route");JSONArray routes=payload==null?null:payload.optJSONArray("routes");JSONObject r=routes==null?null:routes.optJSONObject(0),g=r==null?null:r.optJSONObject("geometry");JSONArray coords=g==null?null:g.optJSONArray("coordinates");
        String k=System.identityHashCode(host.googleMap)+"|"+(snapshot==null?"":snapshot.optString("route_hash")+"|"+snapshot.optLong("version"));if(k.equals(referenceKey))return;referenceKey=k;if(reference!=null){remove(reference);reference=null;}if(coords==null)return;
        List<LatLng> points=new ArrayList<>();for(int i=0;i<coords.length();i++){JSONArray xy=coords.optJSONArray(i);if(xy!=null&&NavigationTargetPolicy.valid(xy.optDouble(1),xy.optDouble(0)))points.add(new LatLng(xy.optDouble(1),xy.optDouble(0)));}
        if(points.size()>1)reference=host.googleMap.addPolyline(new PolylineOptions().addAll(points).width(host.dp(2)).color(0x66708090).zIndex(0));
    }
    private void summary(String value){if(host.tripLiveSummary!=null)host.tripLiveSummary.setText(value);}
    private void remove(Polyline p){if(p!=null)try{p.remove();}catch(RuntimeException e){NavigationDiagnostics.error(host,"TRIP_LINE_REMOVE_FAILED",e);}}
    private void clearLines(){remove(host.pickupPolyline);remove(host.deliveryPolyline);host.pickupPolyline=null;host.deliveryPolyline=null;drawnMap=null;}
    @Override public void close(){closed=true;main.removeCallbacksAndMessages(null);requests.close();remove(reference);}
}
