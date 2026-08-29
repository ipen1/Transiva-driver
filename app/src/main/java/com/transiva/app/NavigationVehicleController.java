package com.transiva.app;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.Property;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonSource;

import static org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap;
import static org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement;
import static org.maplibre.android.style.layers.PropertyFactory.iconImage;
import static org.maplibre.android.style.layers.PropertyFactory.iconPitchAlignment;
import static org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment;
import static org.maplibre.android.style.layers.PropertyFactory.iconSize;

/** Owns the MapLibre vehicle source/layer and resource-overridable vehicle icons. */
public final class NavigationVehicleController {
    private static final String SOURCE = "transiva-vehicle-source";
    private static final String LAYER = "transiva-vehicle-layer";
    private static final String MOTOR = "transiva-vehicle-motor";
    private static final String CAR = "transiva-vehicle-car";
    private final Activity activity;
    private final String vehicleType;
    private Style style;

    public NavigationVehicleController(Activity activity, String vehicleType) {
        this.activity = activity;
        this.vehicleType = "car".equalsIgnoreCase(vehicleType) ? "car" : "motor";
    }

    public void install(Style style, double lat, double lng) {
        this.style = style;
        if (style == null) return;
        try {
            if (style.getSource(SOURCE) == null) style.addSource(new GeoJsonSource(SOURCE, emptyFeatureCollection()));
            addImage(MOTOR, "map_motor_top", android.R.drawable.ic_menu_directions);
            addImage(CAR, "map_car_top", android.R.drawable.ic_menu_directions);
            if (style.getLayer(LAYER) == null) {
                style.addLayer(new SymbolLayer(LAYER, SOURCE).withProperties(
                        iconImage("car".equals(vehicleType) ? CAR : MOTOR), iconSize(1f),
                        iconAllowOverlap(true), iconIgnorePlacement(true),
                        iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
                        iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT)));
            }
            update(lat, lng);
        } catch (Throwable t) { TransivaDiagnostics.error(activity, "navigation", "NAV_VEHICLE_INSTALL_FAILED", t); }
    }

    public void update(double lat, double lng) {
        if (style == null || !valid(lat, lng)) return;
        try {
            JSONObject geometry = new JSONObject(); geometry.put("type", "Point");
            JSONArray coordinate = new JSONArray(); coordinate.put(lng); coordinate.put(lat); geometry.put("coordinates", coordinate);
            JSONObject feature = new JSONObject(); feature.put("type", "Feature"); feature.put("properties", new JSONObject()); feature.put("geometry", geometry);
            JSONObject collection = new JSONObject(); collection.put("type", "FeatureCollection");
            JSONArray features = new JSONArray(); features.put(feature); collection.put("features", features);
            GeoJsonSource source = style.getSourceAs(SOURCE);
            if (source != null) source.setGeoJson(collection.toString());
        } catch (Throwable t) { TransivaDiagnostics.error(activity, "navigation", "NAV_VEHICLE_UPDATE_FAILED", t); }
    }

    private void addImage(String imageName, String drawableName, int fallback) {
        try {
            Bitmap raw = ResourceUpdateManager.loadBitmapOverride(activity, "images/" + drawableName + ".png");
            if (raw == null) {
                int id = activity.getResources().getIdentifier(drawableName, "drawable", activity.getPackageName());
                if (id <= 0) id = fallback;
                raw = BitmapFactory.decodeResource(activity.getResources(), id);
            }
            if (raw == null) return;
            int px = dp(48);
            style.addImage(imageName, Bitmap.createScaledBitmap(raw, px, px, true));
        } catch (Throwable t) { TransivaDiagnostics.error(activity, "navigation", "NAV_VEHICLE_IMAGE_FAILED", t); }
    }

    private String emptyFeatureCollection() { return "{\"type\":\"FeatureCollection\",\"features\":[]}"; }
    private boolean valid(double lat,double lng){ return lat!=0d && lng!=0d && !Double.isNaN(lat) && !Double.isNaN(lng); }
    private int dp(int v){ return (int)(v * activity.getResources().getDisplayMetrics().density + .5f); }
}
