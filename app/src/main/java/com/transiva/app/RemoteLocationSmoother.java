package com.transiva.app;

import android.location.Location;

/** Stabilizes server-fed driver coordinates on the customer screen. */
public final class RemoteLocationSmoother {
    public static final class Point {
        public final double lat, lng, bearing;
        Point(double lat, double lng, double bearing) { this.lat=lat; this.lng=lng; this.bearing=bearing; }
    }

    private double lat, lng, bearing;
    private boolean hasPoint;

    public synchronized Point offer(double newLat, double newLng, double suppliedBearing) {
        if (!valid(newLat, newLng)) return hasPoint ? new Point(lat,lng,bearing) : null;
        if (!hasPoint) {
            lat = newLat; lng = newLng; bearing = normalize(suppliedBearing); hasPoint = true;
            return new Point(lat,lng,bearing);
        }
        float d = distance(lat,lng,newLat,newLng);
        // Ignore exact duplicate polling responses. Large legitimate moves are
        // accepted; animation in the WebView handles visual interpolation.
        if (d < 1.2f) return new Point(lat,lng,bearing);
        double moveBearing = bearing(lat,lng,newLat,newLng);
        bearing = smoothAngle(bearing, Double.isFinite(moveBearing) ? moveBearing : suppliedBearing, 0.35);
        lat = newLat; lng = newLng;
        return new Point(lat,lng,bearing);
    }

    private static boolean valid(double a,double b){return Double.isFinite(a)&&Double.isFinite(b)&&a!=0d&&b!=0d&&Math.abs(a)<=90&&Math.abs(b)<=180;}
    private static float distance(double a,double b,double c,double d){float[] r=new float[1];Location.distanceBetween(a,b,c,d,r);return r[0];}
    private static double bearing(double a,double b,double c,double d){double dl=Math.toRadians(d-b),a1=Math.toRadians(a),a2=Math.toRadians(c);double y=Math.sin(dl)*Math.cos(a2),x=Math.cos(a1)*Math.sin(a2)-Math.sin(a1)*Math.cos(a2)*Math.cos(dl);return normalize(Math.toDegrees(Math.atan2(y,x)));}
    private static double normalize(double v){if(!Double.isFinite(v))return 0;v%=360;if(v<0)v+=360;return v;}
    private static double smoothAngle(double from,double to,double alpha){from=normalize(from);to=normalize(to);double delta=((to-from+540)%360)-180;return normalize(from+delta*alpha);}
}
