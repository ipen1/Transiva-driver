package com.transiva.app;

import android.content.Context;
import android.os.SystemClock;
import java.util.concurrent.ConcurrentHashMap;

/** Small process-local gate that prevents several fallback pollers from firing together. */
public final class DriverPollingCoordinator {
    private static final ConcurrentHashMap<String,Long> LAST=new ConcurrentHashMap<>();
    private DriverPollingCoordinator(){}

    public static long interval(Context c,long baseMs){
        return DevicePerformanceProfile.scalePolling(c,baseMs);
    }

    public static boolean allow(String channel,long minGapMs){
        String key=channel==null?"default":channel;
        long now=SystemClock.elapsedRealtime();
        Long last=LAST.get(key);
        if(last!=null && now-last<Math.max(250L,minGapMs)) return false;
        LAST.put(key,now); return true;
    }

    public static void signalRealtime(String channel){
        if(channel!=null) LAST.put(channel,SystemClock.elapsedRealtime());
    }
}
