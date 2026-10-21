package com.transiva.app;

/** Heading follows the tangent at the rendered position, never a future GPS corner. */
final class NavigationRenderedHeading {
    private double heading=Double.NaN;
    private long frameAt=-1;
    void clear(){heading=Double.NaN;frameAt=-1;}
    double update(long now,double tangent,boolean moving){
        if(!Double.isFinite(tangent))return Double.isFinite(heading)?heading:0;
        tangent=NavigationMotionInterpolator.normalize(tangent);
        if(!Double.isFinite(heading)||frameAt<0||now-frameAt>5000)heading=tangent;
        else if(moving){
            long dt=Math.max(0,now-frameAt);
            heading=NavigationMotionInterpolator.normalize(heading+NavigationMotionInterpolator.delta(heading,tangent)*NavigationMotionInterpolator.alpha(dt,140));
        }
        frameAt=now;return heading;
    }
}
