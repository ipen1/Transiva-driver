package com.transiva.app;

import android.content.Context;
import org.json.JSONObject;

public final class NavigationRuntimeConfig {
    public String primaryStyle="https://tiles.openfreemap.org/styles/liberty";
    public String secondaryStyle="https://demotiles.maplibre.org/style.json";
    public long styleTimeoutMs=9000L;
    public boolean externalFallback=true;
    public String profile="auto";
    public static NavigationRuntimeConfig load(Context c){
        NavigationRuntimeConfig out=new NavigationRuntimeConfig();
        try {
            JSONObject j=ResourceUpdateManager.loadJsonOverride(c,"config/navigation.json");
            if(j==null) return out;
            String p=j.optString("primary_style_url",j.optString("map_style_url","")).trim();
            String s=j.optString("secondary_style_url","").trim();
            if(p.startsWith("https://")) out.primaryStyle=p;
            if(s.startsWith("https://")) out.secondaryStyle=s;
            out.styleTimeoutMs=Math.max(5000L,Math.min(20000L,j.optLong("style_timeout_ms",out.styleTimeoutMs)));
            out.externalFallback=j.optBoolean("fallback_external_navigation",true);
            out.profile=j.optString("profile","auto");
        } catch(Throwable t){ TransivaDiagnostics.error(c,"navigation","CONFIG_LOAD_FAILED",t); }
        return out;
    }
}
