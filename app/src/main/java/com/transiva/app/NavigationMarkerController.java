package com.transiva.app;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONObject;
import org.json.JSONArray;
import org.maplibre.android.annotations.Icon;
import org.maplibre.android.annotations.IconFactory;
import org.maplibre.android.annotations.Marker;
import org.maplibre.android.annotations.MarkerOptions;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;

/** Pickup/delivery marker owner for native navigation. */
public final class NavigationMarkerController {
    private final Activity activity;
    private final JSONObject order;
    private Marker pickupMarker;
    private Marker deliveryMarker;
    private final java.util.ArrayList<Marker> waypointMarkers = new java.util.ArrayList<>();

    public NavigationMarkerController(Activity activity, JSONObject order) {
        this.activity = activity;
        this.order = order == null ? new JSONObject() : order;
    }

    public void install(MapLibreMap map) {
        if (map == null) return;
        IconFactory f = IconFactory.getInstance(activity);
        try {
            double lat = coord("pickup_lat", "user_lat"), lng = coord("pickup_lng", "user_lng");
            if (valid(lat, lng) && pickupMarker == null) {
                pickupMarker = map.addMarker(new MarkerOptions().position(new LatLng(lat, lng))
                        .icon(icon(f, "map_pickup_pin", android.R.drawable.ic_menu_mylocation, 34, 34)));
            }
        } catch (Throwable t) { TransivaDiagnostics.error(activity, "navigation", "NAV_PICKUP_MARKER_FAILED", t); }
        try {
            double lat = coord("delivery_lat", "destination_lat"), lng = coord("delivery_lng", "destination_lng");
            if (valid(lat, lng) && deliveryMarker == null) {
                deliveryMarker = map.addMarker(new MarkerOptions().position(new LatLng(lat, lng))
                        .icon(icon(f, "map_destination_pin", android.R.drawable.ic_menu_mylocation, 34, 34)));
            }
        } catch (Throwable t) { TransivaDiagnostics.error(activity, "navigation", "NAV_DELIVERY_MARKER_FAILED", t); }
        try {
            for(Marker m:waypointMarkers){ try{map.removeMarker(m);}catch(Throwable ignored){} } waypointMarkers.clear();
            JSONObject eco=order.optJSONObject("ecosystem"); JSONArray a=eco==null?order.optJSONArray("waypoints"):eco.optJSONArray("waypoints");
            if(a!=null) for(int i=0;i<a.length();i++){ JSONObject w=a.optJSONObject(i); if(w==null)continue; double lat=w.optDouble("latitude",0),lng=w.optDouble("longitude",0); if(!valid(lat,lng))continue; int seq=w.optInt("sequence",i+1); String note=first(w.optString("note"),w.optString("address")); Marker m=map.addMarker(new MarkerOptions().position(new LatLng(lat,lng)).title("Stop "+seq+(note.isEmpty()?"":" • "+note)).icon(f.defaultMarker())); if(m!=null)waypointMarkers.add(m); }
        } catch(Throwable t){ TransivaDiagnostics.error(activity,"navigation","NAV_WAYPOINT_MARKERS_FAILED",t); }
    }

    private Icon icon(IconFactory factory, String name, int fallback, int wDp, int hDp) {
        int id = activity.getResources().getIdentifier(name, "drawable", activity.getPackageName());
        if (id <= 0) id = fallback;
        Bitmap raw = BitmapFactory.decodeResource(activity.getResources(), id);
        if (raw == null) return factory.defaultMarker();
        Bitmap scaled = Bitmap.createScaledBitmap(raw, dp(wDp), dp(hDp), true);
        return factory.fromBitmap(scaled);
    }

    private double coord(String a, String b) {
        try {
            String value = first(order.optString(a), order.optString(b), "0");
            return Double.parseDouble(value);
        } catch (Throwable ignored) { return 0d; }
    }
    private static boolean valid(double lat,double lng){ return lat!=0d && lng!=0d && !Double.isNaN(lat) && !Double.isNaN(lng); }
    private static String first(String... v){ if(v!=null) for(String s:v) if(s!=null&&!s.trim().isEmpty()&&!"null".equalsIgnoreCase(s.trim())) return s.trim(); return ""; }
    private int dp(int v){ return Math.max(1, (int)(v * activity.getResources().getDisplayMetrics().density + .5f)); }
}
