package com.transiva.app;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

/** Persistent navigation funnel: open -> map -> style -> route, mirrored to Crashlytics keys. */
public final class NavigationHealthTelemetry {
    private static final String P="nav_health_v1";
    private NavigationHealthTelemetry(){}
    public static void mark(Context c,String stage){
        if(c==null||stage==null)return;
        try{
            SharedPreferences p=c.getSharedPreferences(P,Context.MODE_PRIVATE);
            long n=p.getLong(stage,0L)+1L;
            long open="open".equals(stage)?n:p.getLong("open",0L);
            long map="map".equals(stage)?n:p.getLong("map",0L);
            long style="style".equals(stage)?n:p.getLong("style",0L);
            long route="route".equals(stage)?n:p.getLong("route",0L);
            p.edit().putLong(stage,n).putString("last_stage",stage).putLong("last_at",System.currentTimeMillis()).apply();
            NavigationDiagnostics.event(c,"NAV_HEALTH_"+stage.toUpperCase(),null);
            try{
                FirebaseCrashlytics f=FirebaseCrashlytics.getInstance();
                f.setCustomKey("nav_health_open",open);
                f.setCustomKey("nav_health_map",map);
                f.setCustomKey("nav_health_style",style);
                f.setCustomKey("nav_health_route",route);
                f.setCustomKey("nav_health_last_stage",stage);
                if(open>0) f.setCustomKey("nav_route_success_pct",(int)Math.min(100L,(route*100L)/open));
            }catch(Throwable t){ TransivaDiagnostics.error(c,"navigation","HEALTH_CRASHLYTICS_FAILED",t); }
        }catch(Throwable t){TransivaDiagnostics.error(c,"navigation","HEALTH_MARK_FAILED",t);}
    }
    public static long count(Context c,String stage){try{return c.getSharedPreferences(P,Context.MODE_PRIVATE).getLong(stage,0L);}catch(Throwable t){return 0L;}}
}
