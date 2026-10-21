package com.transiva.app;

/** Render-only pose interpolation. Raw accepted GPS remains the authority for reroute and arrival. */
final class NavigationMotionInterpolator {
    static final class Pose {
        final double lat,lng,bearing,progress,velocity; final boolean onRoute;
        Pose(double a,double b,double h,double p,double v,boolean r){lat=a;lng=b;bearing=h;progress=p;velocity=v;onRoute=r;}
    }
    private int intervals;private double drawnLat,drawnLng;private long drawnAt=-1000;
    void rendered(long now,double lat,double lng){drawnAt=now;drawnLat=lat;drawnLng=lng;}
    private Pose from,to;private long at,lastOffer,revision;private double duration=850,period=850,startTangent,endTangent;private boolean along;
    void clear(){from=to=null;lastOffer=0;intervals=0;drawnAt=-1000;}
    boolean ready(){return to!=null;}
    void offer(long now,double lat,double lng,double bearing,double progress,double speed,double accuracy,boolean onRoute,long routeRevision){
        if(!NavigationTargetPolicy.valid(lat,lng)||!Double.isFinite(bearing)||!Double.isFinite(speed)||!Double.isFinite(accuracy))return;
        speed=Math.max(0,Math.min(50,speed));Pose current=sample(now);
        long gap=lastOffer==0?0:now-lastOffer;
        if(gap>150&&gap<=4000){period=intervals++==0?gap:Math.max(gap,period*.65+gap*.35);}
        if(current!=null&&revision!=routeRevision&&now-drawnAt<150)current=new Pose(drawnLat,drawnLng,current.bearing,current.progress,current.velocity,current.onRoute);
        boolean fresh=current==null||gap>12000||NavigationGeometry.meters(current.lat,current.lng,lat,lng)>120;
        if(current!=null&&speed<1.2&&NavigationGeometry.meters(current.lat,current.lng,lat,lng)<3)bearing=current.bearing;
        Pose next=new Pose(lat,lng,normalize(bearing),progress,speed,onRoute&&Double.isFinite(progress));
        if(fresh){from=to=next;at=now;duration=0;along=next.onRoute;revision=routeRevision;lastOffer=now;return;}
        along=revision==routeRevision&&current.onRoute&&next.onRoute&&progress>=current.progress-4;
        if(along&&progress<current.progress)next=new Pose(lat,lng,next.bearing,current.progress,speed,true);
        from=current;to=next;at=now;duration=Math.max(300,Math.min(3200,period));
        if(revision!=routeRevision)duration=450;
        double distance=along?Math.max(0,to.progress-from.progress):NavigationGeometry.meters(from.lat,from.lng,to.lat,to.lng);
        // Monotone cubic tangents preserve speed across fixes without overshooting the next fix.
        double seconds=duration/1000;startTangent=Math.min(distance*2,Math.max(0,current.velocity)*seconds);
        endTangent=Math.min(distance*2,speed*seconds);
        if(distance>0){double scale=Math.max(1,(startTangent+endTangent)/(3*distance));startTangent/=scale;endTangent/=scale;}
        revision=routeRevision;lastOffer=now;
    }
    Pose sample(long now){
        if(to==null)return null;if(duration<=0)return to;
        double u=Math.max(0,(now-at)/duration),t=Math.min(1,u),distance=along?Math.max(0,to.progress-from.progress):NavigationGeometry.meters(from.lat,from.lng,to.lat,to.lng);
        double t2=t*t,t3=t2*t;
        double travelled=(t3-2*t2+t)*startTangent+(-2*t3+3*t2)*distance+(t3-t2)*endTangent;
        travelled=Math.max(0,Math.min(distance,travelled));double f=distance>.001?travelled/distance:t;
        // No free-flight prediction across turns, missing GPS or an unmatched road. Hold last fix.
        double velocity=(3*t2-4*t+1)*startTangent+(-6*t2+6*t)*distance+(3*t2-2*t)*endTangent;
        velocity=u<=1?Math.max(0,velocity/(duration/1000)):u<=1.15?endTangent/(duration/1000):0;
        double heading=normalize(from.bearing+delta(from.bearing,to.bearing)*t);
        return new Pose(from.lat+(to.lat-from.lat)*f,from.lng+(to.lng-from.lng)*f,heading,along?from.progress+travelled:to.progress,velocity,along||t>=1&&to.onRoute);
    }
    static double alpha(long deltaMs,double tauMs){return 1-Math.exp(-Math.max(0,Math.min(250,deltaMs))/Math.max(1,tauMs));}
    static double normalize(double a){return (a%360+360)%360;}
    static double delta(double from,double to){return ((to-from+540)%360)-180;}
}
