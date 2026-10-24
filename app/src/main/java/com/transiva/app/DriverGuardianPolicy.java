package com.transiva.app;

/** Monotonic, per-trip detector. Unknown speed or a GPS gap cannot count as a stop. */
final class DriverGuardianPolicy {
    static final long STOP_MS=600000L, COOLDOWN_MS=900000L;
    private String key="";
    private long stopped=-1, lastFix=-1, warned=-1;
    void reset(){key="";stopped=lastFix=warned=-1;}
    String offer(String trip,long now,boolean eligible,boolean hasSpeed,double kmh){
        if(!key.equals(trip)){reset();key=trip;}
        if(!eligible||trip.isEmpty()){stopped=lastFix=-1;return "";}
        if(lastFix>=0 && (now<lastFix||now-lastFix>60000L))stopped=-1;
        lastFix=now;
        if(!hasSpeed||!Double.isFinite(kmh)||kmh<0){stopped=-1;return "";}
        if(kmh<2){if(stopped<0)stopped=now;}else stopped=-1;
        if(warned>=0 && now-warned<COOLDOWN_MS)return "";
        return kmh>110?"speed_anomaly":stopped>=0&&now-stopped>=STOP_MS?"long_stop":"";
    }
    void warned(long now){warned=now;}
}
