package com.transiva.app;
import org.json.*;
public final class DriverEcosystemFeatures{
 public final JSONArray waypoints; public final boolean guardian,audioProtect; public final int groupSize; public final String splitFareMode;
 private DriverEcosystemFeatures(JSONArray w,boolean g,boolean a,int n,String s){waypoints=w==null?new JSONArray():w;guardian=g;audioProtect=a;groupSize=Math.max(1,n);splitFareMode=s==null?"none":s;}
 public static DriverEcosystemFeatures from(JSONObject order){JSONObject e=order==null?null:order.optJSONObject("ecosystem");if(e==null)e=order;return new DriverEcosystemFeatures(e==null?null:e.optJSONArray("waypoints"),e!=null&&e.optBoolean("trip_monitoring",true),e!=null&&e.optBoolean("audio_protect",false),e==null?1:e.optInt("group_size",1),e==null?"none":e.optString("split_fare_mode","none"));}
 public JSONObject nextPendingWaypoint(){for(int i=0;i<waypoints.length();i++){JSONObject w=waypoints.optJSONObject(i);if(w!=null&&!"arrived".equalsIgnoreCase(w.optString("status","pending")))return w;}return null;}
 public String summary(){StringBuilder s=new StringBuilder();if(waypoints.length()>0)s.append(" • ").append(waypoints.length()).append(" stop");if(groupSize>1)s.append(" • Group ").append(groupSize);if(!"none".equals(splitFareMode))s.append(" • Split fare");if(guardian)s.append(" • Guardian");if(audioProtect)s.append(" • AudioProtect customer");return s.toString();}
}
