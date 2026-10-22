package com.transiva.app;

/** Target-scoped hysteresis prevents a fresh GPS sample from disabling an in-progress arrival swipe. */
final class TripArrivalGate {
    private String target="";private boolean near;
    boolean eligible(String key,double distance,double radius){
        if(!target.equals(key)){target=key;near=false;}
        if(!Double.isFinite(distance)||distance<0){near=false;return false;}
        near=distance<=(near?radius+25:radius);return near;
    }
}
