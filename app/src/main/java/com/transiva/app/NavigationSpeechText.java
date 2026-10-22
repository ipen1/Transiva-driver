package com.transiva.app;
import java.util.Locale;
import org.json.JSONObject;

/** Spoken units and destination side; unknown side stays neutral instead of being invented. */
final class NavigationSpeechText {
    static String distance(double meters){
        if(meters>=1000){double km=Math.round(meters/100d)/10d;return (km==Math.rint(km)?Long.toString((long)km):String.format(new Locale("id","ID"),"%.1f",km))+" kilometer";}
        return Math.max(10,Math.round(Math.max(0,meters)/10)*10)+" meter";
    }
    static String prompt(double meters,double speed,boolean arrival,String action){
        if((arrival&&meters<=25)||(!arrival&&meters<=NavigationVoicePolicy.near(speed)))return action+".";
        double early=NavigationVoicePolicy.early(speed,arrival);
        double rounded=meters>=early*.75?early:Math.max(50,Math.round(meters/50)*50);
        String lower=action.isEmpty()||action.startsWith("Anda ")?action:Character.toLowerCase(action.charAt(0))+action.substring(1);
        return "Setelah "+distance(rounded)+", "+lower+".";
    }
    static String arrival(JSONObject maneuver,String mode,boolean confirmed,double lat,double lng){
        boolean stop=mode!=null&&mode.startsWith("waypoint_");
        if(confirmed)return stop?"Anda telah tiba di persinggahan":"pickup".equals(mode)?"Anda telah tiba di lokasi penjemputan":"Anda telah tiba di lokasi pengantaran";
        if(stop)return "Anda akan tiba di persinggahan";
        String side=side(maneuver,lat,lng),point="pickup".equals(mode)?"Lokasi penjemputan":"Tujuan Anda";
        return side.isEmpty()?point+" berada di depan":point+" berada di sebelah "+side;
    }
    static String side(JSONObject m,double lat,double lng){
        String modifier=m.optString("modifier").toLowerCase(Locale.US);
        if(modifier.contains("left"))return "kiri";if(modifier.contains("right"))return "kanan";
        double a=m.optDouble("lat",Double.NaN),b=m.optDouble("lng",Double.NaN),heading=m.optDouble("bearing_before",Double.NaN);
        if(!NavigationTargetPolicy.valid(lat,lng)||!NavigationTargetPolicy.valid(a,b)||!Double.isFinite(heading))return "";
        double east=(lng-b)*111320*Math.cos(Math.toRadians(a)),north=(lat-a)*111320;
        double offset=Math.hypot(east,north),cross=east*Math.cos(Math.toRadians(heading))-north*Math.sin(Math.toRadians(heading));
        if(offset>80||Math.abs(cross)<8)return "";return cross>0?"kanan":"kiri";
    }
}
