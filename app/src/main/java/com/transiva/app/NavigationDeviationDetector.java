package com.transiva.app;

/** GPS evidence accumulator. Distances are measured against raw accepted fixes, not snapped icons. */
public final class NavigationDeviationDetector {
    public enum Reason { NONE, OFF_ROUTE, PARALLEL_ROAD, WRONG_DIRECTION }
    private Reason candidate=Reason.NONE;
    private long began,lastFix=-1,lastReroute=-100000;
    private int fixes;
    private double travelled,lastLat,lastLng;
    public void reset(){candidate=Reason.NONE;fixes=0;travelled=0;lastFix=-1;}
    public void routeChanged(){reset();}
    public Reason offer(long now,double lat,double lng,double accuracy,double speedMps,
                        double heading,double routeBearing,double deviation) {
        if(!Double.isFinite(deviation)||!Double.isFinite(accuracy)||!Double.isFinite(speedMps)||accuracy>85||accuracy<=0||speedMps<1.2){reset();return Reason.NONE;}
        double delta=Math.abs(((heading-routeBearing+540)%360)-180);
        Reason next=Reason.NONE;
        if(Double.isFinite(heading)&&accuracy<=25&&speedMps>=2.5&&delta>=125&&deviation<=60)next=Reason.WRONG_DIRECTION;
        else if(deviation>=Math.max(28,accuracy*1.5))next=Reason.OFF_ROUTE;
        else if(accuracy<=15&&speedMps>=2&&deviation>=Math.max(12,accuracy*1.8))next=Reason.PARALLEL_ROAD;
        if(next==Reason.NONE){reset();return Reason.NONE;}
        if(next!=candidate||lastFix<0||now-lastFix>6000){candidate=next;began=now;fixes=1;travelled=0;}
        else {fixes++;travelled+=NavigationGeometry.meters(lastLat,lastLng,lat,lng);}
        lastFix=now;lastLat=lat;lastLng=lng;
        int needed=next==Reason.PARALLEL_ROAD?4:3;
        long duration=next==Reason.PARALLEL_ROAD?6000:next==Reason.WRONG_DIRECTION?3000:2200;
        double movement=next==Reason.PARALLEL_ROAD?15:10;
        if(accuracy>45){needed=5;duration=6500;movement=28;}
        boolean hard=next==Reason.OFF_ROUTE&&accuracy<=35&&deviation>=Math.max(75,accuracy*2)&&fixes>=2&&travelled>=8;
        if(now-lastReroute>=7000&&(hard||(fixes>=needed&&now-began>=duration&&travelled>=movement))){Reason result=candidate;lastReroute=now;reset();return result;}
        return Reason.NONE;
    }
}
