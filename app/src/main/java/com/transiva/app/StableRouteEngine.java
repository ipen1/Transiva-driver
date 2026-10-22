package com.transiva.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Legacy order overview and authenticated navigation use separate contracts and caches. */
public final class StableRouteEngine {
    private StableRouteEngine(){}
    private static final class Cache {
        final Result result;final double a,b,x,y;final long at;
        Cache(Result r,double a,double b,double x,double y){result=r;this.a=a;this.b=b;this.x=x;this.y=y;at=System.currentTimeMillis();}
    }
    private static volatile Cache cache;
    public static final class Result {
        public final JSONArray latLngPoints,maneuvers;
        public final double distanceMeters,durationSeconds;
        public final String summary;
        Result(JSONArray points,double meters,double seconds,JSONArray turns){this(points,meters,seconds,turns,"");}
        Result(JSONArray points,double meters,double seconds,JSONArray turns,String summary){latLngPoints=points;distanceMeters=meters;durationSeconds=seconds;maneuvers=turns==null?new JSONArray():turns;this.summary=summary;}
        public String pointsJson(){return latLngPoints==null?"[]":latLngPoints.toString();}
    }
    public static final class RouteSet {
        public final List<Result> routes;
        public final String requestedProfile,usedProfile;
        public final boolean profileFallback;
        RouteSet(List<Result> r,String requested,String used,boolean fallback){routes=Collections.unmodifiableList(new ArrayList<>(r));requestedProfile=requested;usedProfile=used;profileFallback=fallback;}
    }
    public static Result fetch(double a,double b,double x,double y)throws Exception{return fetch(a,b,x,y,false);}
    public static Result fetch(double a,double b,double x,double y,boolean bypass)throws Exception{
        coordinates(a,b,x,y);Cache snapshot=cache;
        if(!bypass&&snapshot!=null&&System.currentTimeMillis()-snapshot.at<180000&&NavigationGeometry.meters(a,b,snapshot.a,snapshot.b)<=15&&NavigationGeometry.meters(x,y,snapshot.x,snapshot.y)<=30)return snapshot.result;
        Exception last=null;
        for(int i=0;i<2;i++){
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Route cancelled");
            try{JSONObject root=request(DriverApiConfig.endpoint("customer_route_proxy.php")+query(a,b,x,y),null);Result r=parseRoutes(root).get(0);cache=new Cache(r,a,b,x,y);return r;}
            catch(Exception e){last=e;if(Thread.currentThread().isInterrupted())throw e;}
        }
        throw last;
    }
    public static Result fetchOverview(DriverSessionOwner owner,double a,double b,double x,double y)throws Exception {
        coordinates(a,b,x,y);owner.check();return parseRoutes(request(DriverApiConfig.endpoint("customer_route_proxy.php")+query(a,b,x,y),owner)).get(0);
    }
    public static RouteSet fetchNavigation(DriverSessionOwner owner,double a,double b,double x,double y,String profile,double heading,boolean force)throws Exception{
        coordinates(a,b,x,y);owner.check();
        String p="motor".equals(profile)?"motor":"car";
        String url=DriverApiConfig.endpoint("driver_navigation_route.php")+query(a,b,x,y)+"&profile="+p+"&alternatives=1&refresh="+(force?1:0);
        if(Double.isFinite(heading))url+="&heading="+String.format(Locale.US,"%.1f",(heading%360+360)%360);
        JSONObject response=request(url,owner);JSONObject meta=response.optJSONObject("transiva_navigation");
        if(meta==null)throw new IOException("Metadata profil rute tidak tersedia. Perbarui endpoint navigasi server.");
        if(!p.equals(meta.optString("requested_profile")))throw new IOException("Target profil respons berubah");
        String used=meta.optString("used_profile");if(!used.equals("motor")&&!used.equals("car"))throw new IOException("Profil rute tidak valid");
        return new RouteSet(parseRoutes(response),p,used,!p.equals(used));
    }
    private static String query(double a,double b,double x,double y){return String.format(Locale.US,"?from_lat=%.7f&from_lng=%.7f&to_lat=%.7f&to_lng=%.7f",a,b,x,y);}
    private static JSONObject request(String url,DriverSessionOwner owner)throws Exception{
        HttpURLConnection c=com.transiva.app.driver.data.DriverConnectionRepository.open(url);
        try{
            c.setRequestMethod("GET");c.setConnectTimeout(3000);c.setReadTimeout(18000);c.setUseCaches(false);c.setInstanceFollowRedirects(false);c.setRequestProperty("Accept","application/json");
            if(owner!=null)owner.pin(c);
            int code=c.getResponseCode();InputStream stream=code>=200&&code<300?c.getInputStream():c.getErrorStream();
            if(stream==null)throw new IOException("Route HTTP "+code);
            String raw;
            try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buf=new byte[8192];int n;long deadline=System.nanoTime()+25_000_000_000L;
                while((n=in.read(buf))!=-1){if(owner!=null)owner.check();if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)throw new InterruptedIOException("Route expired");if(out.size()+n>8388608)throw new IOException("Route response too large");out.write(buf,0,n);}
                raw=new String(out.toByteArray(),StandardCharsets.UTF_8);
            }
            if(owner!=null)owner.check();if(code<200||code>=300)throw new IOException("Route HTTP "+code);
            return new JSONObject(raw);
        }finally{if(owner!=null)owner.release(c);c.disconnect();}
    }
    static List<Result> parseRoutes(JSONObject root)throws Exception{
        if(!"Ok".equals(root.optString("code")))throw new IOException("Route unavailable");
        JSONArray routes=root.optJSONArray("routes");List<Result> results=new ArrayList<>();
        if(routes!=null)for(int k=0;k<Math.min(3,routes.length());k++){
            JSONObject r=routes.optJSONObject(k);if(r==null)continue;
            JSONObject geometry=r.optJSONObject("geometry");JSONArray coords=geometry==null?null:geometry.optJSONArray("coordinates");
            if(coords==null||coords.length()<2)continue;JSONArray points=new JSONArray();
            for(int i=0;i<coords.length();i++){JSONArray xy=coords.optJSONArray(i);if(xy==null||xy.length()<2)throw new IOException("Invalid route geometry");double x=xy.optDouble(0,Double.NaN),y=xy.optDouble(1,Double.NaN);if(!valid(y,x))throw new IOException("Invalid route point");points.put(new JSONArray().put(y).put(x));}
            double distance=r.optDouble("distance",Double.NaN),duration=r.optDouble("duration",Double.NaN);
            if(!Double.isFinite(distance)||!Double.isFinite(duration)||distance<0||duration<0)throw new IOException("Invalid route metrics");
            JSONArray legs=r.optJSONArray("legs");String summary="";if(legs!=null&&legs.optJSONObject(0)!=null)summary=legs.optJSONObject(0).optString("summary","");
            results.add(new Result(points,distance,duration,parseManeuvers(r),summary));
        }
        if(results.isEmpty())throw new IOException("No route returned");return results;
    }
    private static JSONArray parseManeuvers(JSONObject route)throws Exception{
        JSONArray out=new JSONArray(),legs=route.optJSONArray("legs");double cumulative=0;
        if(legs==null)return out;
        for(int l=0;l<legs.length();l++){
            JSONObject leg=legs.optJSONObject(l);JSONArray steps=leg==null?null:leg.optJSONArray("steps");if(steps==null)continue;
            for(int i=0;i<steps.length();i++){
                JSONObject step=steps.optJSONObject(i);if(step==null)continue;JSONObject man=step.optJSONObject("maneuver");
                double distance=Math.max(0,step.optDouble("distance",0));
                if(man!=null){JSONObject item=new JSONObject().put("distance_from_start",cumulative).put("step_distance",distance).put("step_duration",Math.max(0,step.optDouble("duration",0))).put("name",step.optString("name")).put("type",man.optString("type")).put("modifier",man.optString("modifier")).put("exit",man.optInt("exit",0)).put("destinations",step.optString("destinations"));
                    if(Double.isFinite(man.optDouble("bearing_before",Double.NaN)))item.put("bearing_before",man.optDouble("bearing_before"));if(Double.isFinite(man.optDouble("bearing_after",Double.NaN)))item.put("bearing_after",man.optDouble("bearing_after"));
                    JSONArray loc=man.optJSONArray("location");if(loc!=null&&loc.length()>1)item.put("lng",loc.optDouble(0)).put("lat",loc.optDouble(1));
                    JSONArray intersections=step.optJSONArray("intersections");JSONObject junction=intersections==null?null:intersections.optJSONObject(0);
                    JSONArray lanes=junction==null?null:junction.optJSONArray("lanes"),junctionLocation=junction==null?null:junction.optJSONArray("location");
                    if(lanes!=null&&lanes.length()>0&&loc!=null&&loc.length()>1&&junctionLocation!=null&&junctionLocation.length()>1
                            &&NavigationGeometry.meters(loc.optDouble(1),loc.optDouble(0),junctionLocation.optDouble(1),junctionLocation.optDouble(0))<=20)
                        item.put("lanes",new JSONArray(lanes.toString()));
                    out.put(item);
                }cumulative+=distance;
            }
        }return out;
    }
    private static void coordinates(double a,double b,double x,double y){if(!valid(a,b)||!valid(x,y))throw new IllegalArgumentException("Invalid route coordinates");}
    private static boolean valid(double lat,double lng){return Double.isFinite(lat)&&Double.isFinite(lng)&&Math.abs(lat)<=90&&Math.abs(lng)<=180&&!(lat==0&&lng==0);}
}
