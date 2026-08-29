package com.transiva.app;

import android.content.Context;
import java.util.Locale;

/** Map/navigation rendering profile. Explicit account performance mode has priority. */
public final class NavigationCompatibilityProfile {
    public enum Mode { NORMAL, STABLE, ULTRA }
    public final Mode mode;
    public final long visualFrameMs;
    public final float positionEaseAlpha;
    public final float bearingEaseAlpha;
    public final float cameraBearingAlpha;
    public final double cameraTilt;
    public final boolean allowPip;

    private NavigationCompatibilityProfile(Mode mode, long frame, float pos, float bearing, float camera, double tilt, boolean pip){
        this.mode=mode; this.visualFrameMs=frame; this.positionEaseAlpha=pos; this.bearingEaseAlpha=bearing; this.cameraBearingAlpha=camera; this.cameraTilt=tilt; this.allowPip=pip;
    }
    public static NavigationCompatibilityProfile resolve(Context c, String requested){
        DevicePerformanceProfile perf = DevicePerformanceProfile.get(c);
        DevicePerformanceProfile.UserMode selected = DevicePerformanceProfile.getSelectedMode(c);
        if (selected == DevicePerformanceProfile.UserMode.HIGH) return normal();
        if (selected == DevicePerformanceProfile.UserMode.NORMAL) return stable();
        if (selected == DevicePerformanceProfile.UserMode.LOW) return low(perf.visualFrameMs);

        String r = requested == null ? "" : requested.trim().toLowerCase(Locale.US);
        if (r.contains("ultra")) return ultra();
        if (r.contains("normal")) return normal();
        if (r.contains("stable")) return stable();
        try {
            if (perf.tier==DevicePerformanceProfile.Tier.VERY_LOW) return low(perf.visualFrameMs);
            if (perf.tier==DevicePerformanceProfile.Tier.LOW) return ultra();
            if (perf.tier==DevicePerformanceProfile.Tier.NORMAL) return stable();
        } catch(Throwable t){ TransivaDiagnostics.error(c,"navigation","PROFILE_DETECT_FAILED",t); }
        return normal();
    }
    public static NavigationCompatibilityProfile normal(){ return new NavigationCompatibilityProfile(Mode.NORMAL,16L,.16f,.10f,.075f,42d,true); }
    public static NavigationCompatibilityProfile stable(){ return new NavigationCompatibilityProfile(Mode.STABLE,33L,.20f,.13f,.10f,30d,true); }
    public static NavigationCompatibilityProfile ultra(){ return new NavigationCompatibilityProfile(Mode.ULTRA,50L,.25f,.18f,.14f,0d,false); }
    private static NavigationCompatibilityProfile low(long frame){ return new NavigationCompatibilityProfile(Mode.ULTRA,Math.max(50L,frame),.27f,.19f,.15f,0d,false); }
}
