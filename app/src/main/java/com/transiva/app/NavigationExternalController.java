package com.transiva.app;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
public final class NavigationExternalController {
    private NavigationExternalController(){}
    public static boolean open(Activity a,double lat,double lng){
        if(a==null||!Double.isFinite(lat)||!Double.isFinite(lng)||lat==0d||lng==0d)return false;
        try{
            Intent g=new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q="+lat+","+lng+"&mode=d"));
            g.setPackage("com.google.android.apps.maps"); a.startActivity(g); NavigationDiagnostics.event(a,"NAV_FALLBACK_GOOGLE_MAPS",null); return true;
        }catch(Throwable first){
            TransivaDiagnostics.error(a,"navigation","GOOGLE_MAPS_OPEN_FAILED",first);
            try{a.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("geo:"+lat+","+lng+"?q="+lat+","+lng)));return true;}
            catch(Throwable second){NavigationDiagnostics.error(a,"NAV_FALLBACK_FAILED",second);return false;}
        }
    }
}
