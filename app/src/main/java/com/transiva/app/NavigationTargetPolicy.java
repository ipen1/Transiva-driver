package com.transiva.app;
import org.json.*;
import java.util.Locale;

/** Shared read-only destination rules. Never advances order/waypoint status or changes fare. */
final class NavigationTargetPolicy {
    static final class Target {
        final String mode; final double lat,lng;
        Target(String mode,double lat,double lng){this.mode=mode;this.lat=lat;this.lng=lng;}
        boolean valid(){return NavigationTargetPolicy.valid(lat,lng);}
        String key(JSONObject order){return identity(order)+"|"+mode+"|"+lat+"|"+lng;}
    }
    static String id(JSONObject o){return o==null?"":first(o.optString("order_id"),o.optString("id"));}
    static String source(JSONObject o){String s=o==null?"":first(o.optString("source"),o.optString("source_table"),o.optString("order_kind"));return s.toLowerCase(Locale.US).contains("pickup")?"pickup_orders":"orders";}
    static String identity(JSONObject o){return source(o)+"|"+id(o);}
    static boolean same(JSONObject a,JSONObject b){return !id(a).isEmpty()&&identity(a).equals(identity(b));}
    static String revision(JSONObject o){
        if(o==null)return "";StringBuilder s=new StringBuilder(identity(o));
        for(String key:new String[]{"status","pickup_lat","pickup_lng","user_lat","user_lng","delivery_lat","delivery_lng","destination_lat","destination_lng"})s.append('|').append(o.optString(key));
        JSONObject eco=o.optJSONObject("ecosystem");JSONArray stops=eco==null?o.optJSONArray("waypoints"):eco.optJSONArray("waypoints");return s.append('|').append(stops==null?"":stops.toString()).toString();
    }
    static boolean terminal(JSONObject o){String s=o==null?"":o.optString("status").trim().toLowerCase(Locale.US);return s.equals("finished")||s.equals("completed")||s.contains("cancel")||s.equals("rejected");}
    static boolean delivery(JSONObject o){String s=o==null?"":o.optString("status").trim().toLowerCase(Locale.US);return s.equals("arrived_pickup")||s.equals("on_delivery")||s.equals("arrived_delivery");}
    static Target resolve(JSONObject o,String preferred,double fallbackLat,double fallbackLng){
        boolean toDelivery=delivery(o)||"delivery".equals(preferred)||(preferred!=null&&preferred.startsWith("waypoint_"));
        if(toDelivery){
            JSONObject eco=o==null?null:o.optJSONObject("ecosystem");JSONArray stops=eco==null?(o==null?null:o.optJSONArray("waypoints")):eco.optJSONArray("waypoints");
            JSONObject next=null;int sequence=Integer.MAX_VALUE;
            if(stops!=null)for(int i=0;i<stops.length();i++){JSONObject w=stops.optJSONObject(i);if(w==null)continue;String state=w.optString("status","pending");if("arrived".equalsIgnoreCase(state)||"cancelled".equalsIgnoreCase(state)||"skipped".equalsIgnoreCase(state))continue;
                double lat=w.optDouble("latitude",Double.NaN),lng=w.optDouble("longitude",Double.NaN);int seq=w.optInt("sequence",i+1);if(valid(lat,lng)&&seq<sequence){next=w;sequence=seq;}}
            if(next!=null)return new Target("waypoint_"+sequence,next.optDouble("latitude"),next.optDouble("longitude"));
        }
        String mode=toDelivery?"delivery":"pickup";
        double lat=coord(o,toDelivery?"delivery_lat":"pickup_lat",toDelivery?"destination_lat":"user_lat"),lng=coord(o,toDelivery?"delivery_lng":"pickup_lng",toDelivery?"destination_lng":"user_lng");
        // Never reuse an old waypoint as the final delivery destination after the stop changes.
        if(!valid(lat,lng)&&(preferred==null||!preferred.startsWith("waypoint_"))){lat=fallbackLat;lng=fallbackLng;}
        return new Target(mode,lat,lng);
    }
    static double coord(JSONObject o,String a,String b){if(o==null)return Double.NaN;double v=o.optDouble(a,Double.NaN);return Double.isFinite(v)?v:o.optDouble(b,Double.NaN);}
    static boolean valid(double lat,double lng){return Double.isFinite(lat)&&Double.isFinite(lng)&&Math.abs(lat)<=90&&Math.abs(lng)<=180&&!(lat==0&&lng==0);}
    private static String first(String... ss){for(String s:ss)if(s!=null&&!s.trim().isEmpty()&&!"null".equalsIgnoreCase(s.trim()))return s.trim();return "";}
}
