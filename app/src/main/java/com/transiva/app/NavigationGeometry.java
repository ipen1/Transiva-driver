package com.transiva.app;
import java.util.List;

public final class NavigationGeometry {
    private NavigationGeometry(){}
    public static final class Projection {
        public final double distance,bearing,progress;
        Projection(double d,double b,double p){distance=d;bearing=b;progress=p;}
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
        double best=Double.POSITIVE_INFINITY,bearing=Double.NaN,progress=0;
        double sx=111320*Math.max(.15,Math.cos(Math.toRadians(lat))),sy=111320;
        for(int i=0;i+1<points.size();i++){
            double[] a=points.get(i),b=points.get(i+1);double length=meters(a[0],a[1],b[0],b[1]);
            double start=i<cumulative.size()?cumulative.get(i):0;
            if(Double.isFinite(prior)&&(start+length<prior-180||start>prior+650))continue;
            double vx=(b[1]-a[1])*sx,vy=(b[0]-a[0])*sy,px=(lng-a[1])*sx,py=(lat-a[0])*sy;
            double den=vx*vx+vy*vy,t=den<.0001?0:Math.max(0,Math.min(1,(px*vx+py*vy)/den));
            double d=Math.hypot(px-vx*t,py-vy*t);
            if(d<best){best=d;bearing=(Math.toDegrees(Math.atan2(vx,vy))+360)%360;progress=start+length*t;}
        }
        return new Projection(best,bearing,progress);
    }
}
