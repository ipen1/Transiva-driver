package com.transiva.app;

/** Bounded healthy-route evaluation; a longer alternative can win on time, never distance alone. */
final class NavigationRefreshPolicy {
    private long evaluated=-1,manualUntil; private double lat,lng;
    boolean initialized(){return evaluated>=0;}
    void reset(){evaluated=-1;manualUntil=0;}
    void evaluated(long now,double a,double b){evaluated=now;lat=a;lng=b;}
    void manual(long now){manualUntil=now+180000;}
    long manualRemaining(long now){return Math.max(0,manualUntil-now);}
    void hold(long now,long millis){manualUntil=now+Math.max(0,Math.min(180000,millis));}
    boolean due(long now,double a,double b,double speed,double accuracy,double remaining){
        return evaluated>=0&&now>=manualUntil&&now-evaluated>=90000&&speed>=2&&accuracy<=30&&remaining>350&&NavigationGeometry.meters(lat,lng,a,b)>=250;
    }
    boolean better(long now,double oldSeconds,double newSeconds){return now>=manualUntil&&Double.isFinite(oldSeconds)&&Double.isFinite(newSeconds)&&oldSeconds>0&&oldSeconds-newSeconds>=Math.max(60,oldSeconds*.15);}
}
