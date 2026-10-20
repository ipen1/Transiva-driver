package com.transiva.app;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

public final class NavigationExternalController {
    private NavigationExternalController(){}
    public static boolean open(Activity activity,double lat,double lng){return open(activity,lat,lng,"car");}
    static String routeUrl(double lat,double lng,String profile){
        if(!Double.isFinite(lat)||!Double.isFinite(lng)||Math.abs(lat)>90||Math.abs(lng)>180||(lat==0&&lng==0))throw new IllegalArgumentException("Invalid destination");
        return "https://www.google.com/maps/dir/?api=1&destination="+lat+"%2C"+lng+"&travelmode="+("motor".equals(profile)?"two-wheeler":"driving")+"&dir_action=navigate";
    }
    public static boolean open(Activity activity,double lat,double lng,String profile){
        if(activity==null||activity.isFinishing()||activity.isDestroyed())return false;
        final Uri destination;try{destination=Uri.parse(routeUrl(lat,lng,profile));}catch(IllegalArgumentException e){return false;}
        try{Intent maps=new Intent(Intent.ACTION_VIEW,destination);maps.setPackage("com.google.android.apps.maps");activity.startActivity(maps);NavigationDiagnostics.event(activity,"NAV_FALLBACK_GOOGLE_MAPS",null);return true;}
        catch(Throwable first){
            try{activity.startActivity(new Intent(Intent.ACTION_VIEW,destination));return true;}
            catch(Throwable second){NavigationDiagnostics.error(activity,"NAV_FALLBACK_FAILED",second);return false;}
        }
    }
}
