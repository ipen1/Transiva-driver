package com.transiva.app;
import java.util.List;

public final class NavigationGeometry {
    private NavigationGeometry(){}
    public static final class Projection {
        public final double distance,bearing,progress;
        Projection(double d,double b,double p){distance=d;bearing=b;progress=p;}
    }
    static final class Point {
        final double lat,lng,bearing;final int segment;
        Point(double a,double b,double h,int i){lat=a;lng=b;bearing=h;segment=i;}
    }
    static Point at(List<double[]> points,List<Double> cumulative,double progress){
        if(points.size()<2||points.size()!=cumulative.size())return null;
        double p=Math.max(0,Math.min(cumulative.get(cumulative.size()-1),progress));
        int i=java.util.Collections.binarySearch(cumulative,p);i=i<0?Math.max(0,-i-2):i;i=Math.min(points.size()-2,i);
        double length=cumulative.get(i+1)-cumulative.get(i),t=length>0?(p-cumulative.get(i))/length:0;double[] a=points.get(i),b=points.get(i+1);
        double bearing=(Math.toDegrees(Math.atan2((b[1]-a[1])*Math.cos(Math.toRadians(a[0])),b[0]-a[0]))+360)%360;
        return new Point(a[0]+(b[0]-a[0])*t,a[1]+(b[1]-a[1])*t,bearing,i);
    }
    public static double meters(double a,double b,double x,double y){
        double p=Math.toRadians(x-a),q=Math.toRadians(y-b),v=Math.sin(p/2)*Math.sin(p/2)+Math.cos(Math.toRadians(a))*Math.cos(Math.toRadians(x))*Math.sin(q/2)*Math.sin(q/2);
        return 6371000*2*Math.atan2(Math.sqrt(Math.max(0,v)),Math.sqrt(Math.max(0,1-v)));
    }
    public static double distanceTo(org.json.JSONArray geometry,double lat,double lng){
        java.util.ArrayList<double[]> points=new java.util.ArrayList<>();java.util.ArrayList<Double> cumulative=new java.util.ArrayList<>();double length=0;
        for(int i=0;i<geometry.length();i++){org.json.JSONArray xy=geometry.optJSONArray(i);if(xy==null||xy.length()<2)continue;double[] p={xy.optDouble(0),xy.optDouble(1)};if(!points.isEmpty()){double[] q=points.get(points.size()-1);length+=meters(q[0],q[1],p[0],p[1]);}points.add(p);cumulative.add(length);}
        return project(points,cumulative,lat,lng,Double.NaN).distance;
    }
    public static Projection project(List<double[]> points,List<Double> cumulative,double lat,double lng,double prior){
        return project(points,cumulative,lat,lng,prior,Double.NaN,50,0);
    }
    /** Heading disambiguates nearby return legs only within the GPS uncertainty corridor.
     * Never hide a true wrong-way fix by snapping to a distant, heading-compatible road. */
    public static Projection project(List<double[]> points,List<Double> cumulative,double lat,double lng,double prior,
                                     double heading,double accuracy,double speed){
        double sx=111320*Math.max(.15,Math.cos(Math.toRadians(lat))),sy=111320;
        int from=0,to=points.size()-2;
        if(Double.isFinite(prior)&&cumulative.size()==points.size()){
            int low=java.util.Collections.binarySearch(cumulative,Math.max(0,prior-180));
            int high=java.util.Collections.binarySearch(cumulative,prior+650);
            from=Math.max(0,(low<0?-low-2:low)-1);to=Math.min(to,high<0?-high-1:high);
        }
        double nearestDistance=Double.POSITIVE_INFINITY,nearestBearing=Double.NaN,nearestProgress=0;
        double bestScore=Double.POSITIVE_INFINITY,preferredDistance=0,preferredBearing=0,preferredProgress=0;boolean preferred=false;
        boolean directional=Double.isFinite(heading)&&accuracy>0&&accuracy<=25&&speed>=2.5;
        // First pass finds the actual closest road; the second may only resolve ambiguity nearby.
        for(int pass=0;pass<(directional?2:1);pass++)for(int i=from;i<=to;i++){
            double[] a=points.get(i),b=points.get(i+1);double length=i+1<cumulative.size()?Math.max(0,cumulative.get(i+1)-cumulative.get(i)):meters(a[0],a[1],b[0],b[1]);
            if(length<.01)continue;
            double start=i<cumulative.size()?cumulative.get(i):0;
            if(Double.isFinite(prior)&&(start+length<prior-180||start>prior+650))continue;
            double vx=(b[1]-a[1])*sx,vy=(b[0]-a[0])*sy,px=(lng-a[1])*sx,py=(lat-a[0])*sy;
            double den=vx*vx+vy*vy,t=den<.0001?0:Math.max(0,Math.min(1,(px*vx+py*vy)/den));
            double d=Math.hypot(px-vx*t,py-vy*t),bearing=(Math.toDegrees(Math.atan2(vx,vy))+360)%360,progress=start+length*t;
            if(pass==0){if(d<nearestDistance){nearestDistance=d;nearestBearing=bearing;nearestProgress=progress;}}
            else if(d<=nearestDistance+Math.max(3,Math.min(10,accuracy*.5))){
                double angle=Math.abs(((heading-bearing+540)%360)-180);
                double score=d+angle*.08;
                if(Double.isFinite(prior)&&progress<prior-4)score+=Math.min(6,(prior-progress)*.08);
                if(score<bestScore){bestScore=score;preferredDistance=d;preferredBearing=bearing;preferredProgress=progress;preferred=true;}
            }
        }
        return preferred?new Projection(preferredDistance,preferredBearing,preferredProgress):new Projection(nearestDistance,nearestBearing,nearestProgress);
    }
}
