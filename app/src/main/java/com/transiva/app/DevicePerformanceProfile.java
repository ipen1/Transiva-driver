package com.transiva.app;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import java.util.Locale;

/** Centralized hardware profile used to keep old/low-RAM devices responsive. */
public final class DevicePerformanceProfile {
    public enum Tier { HIGH, NORMAL, LOW, VERY_LOW }

    public final Tier tier;
    public final long navigationGpsMs;
    public final long navigationNetworkMs;
    public final float navigationMinDistanceM;
    public final long tripGpsMs;
    public final long tripNetworkMs;
    public final float tripMinDistanceM;
    public final float pollingMultiplier;
    public final int imageMaxSidePx;
    public final int imageWorkerCount;
    public final boolean reduceMapMotion;

    private static volatile DevicePerformanceProfile cached;

    private DevicePerformanceProfile(Tier tier, long navGps, long navNet, float navDistance,
                                     long tripGps, long tripNet, float tripDistance,
                                     float pollingMultiplier, int imageMaxSidePx,
                                     int imageWorkerCount, boolean reduceMapMotion) {
        this.tier=tier; this.navigationGpsMs=navGps; this.navigationNetworkMs=navNet;
        this.navigationMinDistanceM=navDistance; this.tripGpsMs=tripGps; this.tripNetworkMs=tripNet;
        this.tripMinDistanceM=tripDistance; this.pollingMultiplier=pollingMultiplier;
        this.imageMaxSidePx=imageMaxSidePx; this.imageWorkerCount=imageWorkerCount;
        this.reduceMapMotion=reduceMapMotion;
    }

    public static DevicePerformanceProfile get(Context context) {
        DevicePerformanceProfile c=cached;
        if(c!=null) return c;
        synchronized(DevicePerformanceProfile.class){
            if(cached==null) cached=detect(context == null ? null : context.getApplicationContext());
            return cached;
        }
    }

    public static long scalePolling(Context context,long baseMs){
        long safe=Math.max(1000L,baseMs);
        return Math.max(1000L,Math.round(safe*get(context).pollingMultiplier));
    }

    private static DevicePerformanceProfile detect(Context c){
        long totalMb=4096L;
        boolean lowRam=false;
        try{
            if(c!=null){
                ActivityManager am=(ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE);
                if(am!=null){
                    ActivityManager.MemoryInfo mi=new ActivityManager.MemoryInfo(); am.getMemoryInfo(mi);
                    if(mi.totalMem>0) totalMb=mi.totalMem/(1024L*1024L);
                    if(Build.VERSION.SDK_INT>=19) lowRam=am.isLowRamDevice();
                }
            }
        }catch(Throwable ignored){}
        String model=(Build.MANUFACTURER+" "+Build.MODEL).toLowerCase(Locale.US);
        boolean budgetOem=model.contains("itel")||model.contains("tecno")||model.contains("infinix")||model.contains("redmi a")||model.contains("realme c");
        if(lowRam || totalMb<=2300L || Build.VERSION.SDK_INT<=25) return veryLow();
        if(totalMb<=3600L || Build.VERSION.SDK_INT<=27 || budgetOem) return low();
        if(totalMb>=7500L && Build.VERSION.SDK_INT>=30) return high();
        return normal();
    }

    private static DevicePerformanceProfile high(){return new DevicePerformanceProfile(Tier.HIGH,1000L,2500L,0f,1200L,3000L,0f,1.0f,1440,3,false);}
    private static DevicePerformanceProfile normal(){return new DevicePerformanceProfile(Tier.NORMAL,1300L,3200L,1f,1500L,3500L,1f,1.0f,1080,3,false);}
    private static DevicePerformanceProfile low(){return new DevicePerformanceProfile(Tier.LOW,1800L,4500L,2f,2200L,5000L,2f,1.35f,800,2,true);}
    private static DevicePerformanceProfile veryLow(){return new DevicePerformanceProfile(Tier.VERY_LOW,2500L,6500L,3f,3000L,7000L,3f,1.65f,640,1,true);}
}
