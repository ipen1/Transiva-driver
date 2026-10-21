package com.transiva.app;
import android.location.Location;
import android.os.*;
import com.google.android.gms.maps.model.*;

/** Trip marker uses the same timed pose model as native navigation; no per-fix marker teleport. */
final class TripVehicleMotionController implements AutoCloseable {
    private final DriverTripActivityLayer2 host;
    private final NavigationMotionInterpolator motion=new NavigationMotionInterpolator();
    private final NavigationFrameController frames;private Location last;
    private boolean active,closed;private long revision=-1;private double drawnLat=Double.NaN,drawnLng,drawnHeading;
    TripVehicleMotionController(DriverTripActivityLayer2 h){host=h;frames=new NavigationFrameController(new Handler(Looper.getMainLooper()),NavigationCompatibilityProfile.resolve(h,"auto").visualFrameMs,this::render);}
    void active(boolean value){if(closed)return;active=value;if(value)frames.start();else{frames.stop();motion.clear();last=null;}}
    void offer(Location fix){
        if(closed||!active||fix==null)return;
        long age=SystemClock.elapsedRealtimeNanos()-fix.getElapsedRealtimeNanos();if(fix.getElapsedRealtimeNanos()<=0||age<0||age>12000_000_000L)return;
        Location previous=last;last=new Location(fix);NavigationMotionInterpolator.Pose p=host.tripLiveRoutes==null?null:host.tripLiveRoutes.visualPose(fix);
        double speed=fix.hasSpeed()?fix.getSpeed():host.currentSpeedKmh/3.6,heading=fix.hasBearing()&&speed>1.2?fix.getBearing():motion.ready()?motion.sample(SystemClock.elapsedRealtime()).bearing:0;
        if(!fix.hasBearing()&&speed>=1.5&&previous!=null&&previous.distanceTo(fix)>=3)heading=NavigationMotionInterpolator.normalize(Math.toDegrees(Math.atan2((fix.getLongitude()-previous.getLongitude())*Math.cos(Math.toRadians(fix.getLatitude())),fix.getLatitude()-previous.getLatitude())));
        revision=host.tripLiveRoutes==null?0:host.tripLiveRoutes.visualRevision();
        motion.offer(SystemClock.elapsedRealtime(),p==null?fix.getLatitude():p.lat,p==null?fix.getLongitude():p.lng,p!=null&&p.onRoute?p.bearing:heading,p==null?Double.NaN:p.progress,speed,fix.hasAccuracy()?fix.getAccuracy():50,p!=null&&p.onRoute,revision);
    }
    void mapReady(){if(active)render();}
    private void render(){
        if(closed||!active||host.googleMap==null||!host.mapReady||NavigationTargetPolicy.terminal(host.order))return;
        if(last!=null&&host.tripLiveRoutes!=null&&revision!=host.tripLiveRoutes.visualRevision())offer(last);
        NavigationMotionInterpolator.Pose p=motion.sample(SystemClock.elapsedRealtime());if(p==null)return;
        double lat=p.lat,lng=p.lng;if(p.onRoute&&host.tripLiveRoutes!=null){NavigationGeometry.Point point=host.tripLiveRoutes.visualPoint(p.progress);if(point!=null){lat=point.lat;lng=point.lng;}}
        motion.rendered(SystemClock.elapsedRealtime(),lat,lng);
        try{
            if(host.driverMarker==null){host.driverMarker=host.googleMap.addMarker(new MarkerOptions().position(new LatLng(lat,lng)).title("Posisi Driver").flat(true).rotation((float)p.bearing).anchor(.5f,.5f).icon(host.driverVehicleIcon()));drawnLat=Double.NaN;}
            if(host.driverMarker!=null&&(!Double.isFinite(drawnLat)||NavigationGeometry.meters(drawnLat,drawnLng,lat,lng)>=.05||Math.abs(NavigationMotionInterpolator.delta(drawnHeading,p.bearing))>=.25)){
                host.driverMarker.setPosition(new LatLng(lat,lng));host.driverMarker.setRotation((float)p.bearing);drawnLat=lat;drawnLng=lng;drawnHeading=p.bearing;
            }
        }catch(RuntimeException e){NavigationDiagnostics.error(host,"TRIP_MOTION_RENDER_FAILED",e);}
    }
    @Override public void close(){closed=true;frames.stop();motion.clear();last=null;}
}
