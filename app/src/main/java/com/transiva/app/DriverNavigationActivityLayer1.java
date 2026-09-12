package com.transiva.app;

import android.Manifest;
import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Rational;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.maplibre.android.MapLibre;
import org.maplibre.android.annotations.Icon;
import org.maplibre.android.annotations.IconFactory;
import org.maplibre.android.annotations.Marker;
import org.maplibre.android.annotations.MarkerOptions;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapLibreMapOptions;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.Property;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonSource;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.maplibre.android.style.layers.PropertyFactory.lineCap;
import static org.maplibre.android.style.layers.PropertyFactory.lineColor;
import static org.maplibre.android.style.layers.PropertyFactory.lineJoin;
import static org.maplibre.android.style.layers.PropertyFactory.lineOpacity;
import static org.maplibre.android.style.layers.PropertyFactory.lineWidth;
import static org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap;
import static org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement;
import static org.maplibre.android.style.layers.PropertyFactory.iconImage;
import static org.maplibre.android.style.layers.PropertyFactory.iconPitchAlignment;
import static org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment;
import static org.maplibre.android.style.layers.PropertyFactory.iconSize;
import static org.maplibre.android.style.layers.Property.LINE_CAP_ROUND;
import static org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND;
abstract class DriverNavigationActivityLayer1 extends Activity {
    protected static final String MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty";
    protected static final String ROUTE_SOURCE = "transiva-route-source";
    protected static final String ROUTE_GLOW_LAYER = "transiva-route-glow";
    protected static final String ROUTE_CASE_LAYER = "transiva-route-case";
    protected static final String ROUTE_LAYER = "transiva-route-line";
    protected static final String VEHICLE_SOURCE = "transiva-vehicle-source";
    protected static final String VEHICLE_LAYER = "transiva-vehicle-layer";
    protected static final String VEHICLE_MOTOR_IMAGE = "transiva-vehicle-motor";
    protected static final String VEHICLE_CAR_IMAGE = "transiva-vehicle-car";

    protected static final long LOCATION_UPLOAD_MS = 2500L;

    // Auto-reroute is deliberately confirmed over several fixes. This prevents a
    // single weak-GPS jump from replacing a route that is still correct.

    // Navigation map-matching stability. These values are intentionally conservative:
    // on dual-carriageway / parallel roads we prefer continuity over jumping to a
    // geometrically-nearer segment several dozen metres ahead.

    protected final Handler main = new Handler(Looper.getMainLooper());
    protected final SmoothLocationEngine smoothLocation = new SmoothLocationEngine(1800L);

    protected MapView mapView;
    protected MapLibreMap map;
    protected Style style;

    protected TextView routeBadge;
    protected TextView instructionBadge;
    protected TextView speedBadge;
    protected TextView messageButton;
    protected TextView callButton;
    protected NavigationCommunicationController communicationController;
    protected TextView backButton;
    protected TextView fallbackButton;
    protected FrameLayout navigationRoot;

    protected Location lastLocation;
    protected Location lastRouteLocation;
    protected Location lastSpeedLocation;

    protected JSONObject order = new JSONObject();
    protected SessionManager session;
    protected String username = "";
    protected String vehicleType = "motor";
    protected String targetMode = "pickup";
    protected String mapStyleUrl = MAP_STYLE;
    protected long mapStyleTimeoutMs = 9000L;
    protected NavigationRuntimeConfig navConfig;
    protected NavigationCompatibilityProfile navProfile;
    protected NavigationMapController mapController;
    protected NavigationLocationController locationController;
    protected NavigationPipController pipController;
    protected NavigationInstructionController instructionController;
    protected NavigationCameraController cameraController;
    protected NavigationVehicleController vehicleController;
    protected NavigationMarkerController markerController;

    protected double driverLat;
    protected double driverLng;
    protected double currentBearing;
    protected double currentSpeedKmh;
    protected double previousSpeedKmh;
    protected double smoothedAccelerationMps2;
    protected long lastSpeedRealtimeMs;
    protected double averageSpeedKmh;
    protected double speedSum;
    protected long speedSamples;
    protected long lastUploadAt;
    protected long lastRouteRequestAt;

    // Keep network work bounded. Location uploads are latest-value wins so a slow
    // mobile connection can never create dozens of pending upload threads.
    protected final ExecutorService routeExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "transiva-route-worker");
        t.setDaemon(true);
        return t;
    });

    protected boolean styleReady;
    protected boolean activityStarted;
    protected boolean activityResumed;
    protected boolean mapViewStarted;
    protected boolean mapInitAttempted;
    protected boolean nativeMapFailed;
    protected boolean inPictureInPicture = false;
    protected boolean mapViewResumed = false;
    protected volatile boolean routeInFlight;
    protected boolean userAdjustedZoom = false;

    // Final navigation smoothing: frequent small interpolation steps instead of GPS-sized jumps.
    protected static final long VISUAL_FRAME_MS = 16L;          // ~60 FPS
    protected static final long POSITION_EASE_MS = 900L;
    protected static final float POSITION_EASE_ALPHA = 0.16f;
    protected static final float BEARING_EASE_ALPHA = 0.10f;
    protected static final float CAMERA_BEARING_ALPHA = 0.075f;
    protected double smoothMarkerBearing = Double.NaN;
    protected String pendingRouteGeoJson = "";
    protected double pendingRouteKm;
    protected double pendingRouteSeconds;
    protected final List<double[]> routePoints = new ArrayList<>();
    protected final List<Double> routeCumulativeMeters = new ArrayList<>();
    protected JSONArray routeManeuvers = new JSONArray();
    protected int routeProgressIndex = 0;
    protected double snappedBearing = 0d;
    protected long lastRouteSuccessAt = 0L;
    protected int routeFailureCount = 0;

    // Weak-GPS and sustained route-deviation state.
    protected float lastGpsAccuracyM = 50f;
    protected int offRouteFixCount = 0;
    protected long offRouteStartedAt = 0L;
    protected double offRouteStartLat = Double.NaN;
    protected double offRouteStartLng = Double.NaN;
    protected long lastAutoRerouteAt = 0L;

    // Continuous route progress (metres), not only a segment index. This is the
    // key to preventing lane/parallel-road hopping and route-line leftovers.
    protected double lastMatchedProgressMeters = Double.NaN;
    protected int lastMatchedSegmentIndex = 0;
    protected double lastMatchedSegmentT = 0d;
    protected double lastMatchedLat = Double.NaN;
    protected double lastMatchedLng = Double.NaN;
    protected double lastMatchedRawLat = Double.NaN;
    protected double lastMatchedRawLng = Double.NaN;
    protected long lastMatchRealtimeMs = 0L;
    protected double visualRouteProgressMeters = Double.NaN;
    protected int visualRouteSegmentIndex = 0;
    protected double lastRenderedRouteProgressMeters = Double.NaN;

    protected NavigationRouteScheduler routeScheduler;

    protected NavigationFrameController frameController;

    protected double explicitTargetLat;
    protected double explicitTargetLng;
    protected double displayLat;
    protected double displayLng;
    protected boolean displayInitialized;
    protected long lastFixRealtimeMs = 0L;
    protected int lastRenderedRouteIndex = -1;
    protected long lastRouteLineUpdateAt = 0L;
    protected long lastMapRenderAt = 0L;
    protected long lastVisualFrameAt = 0L;
    protected long lastSpeedUiAt = 0L;

    protected static final class SnapPoint {
        final double lat, lng, bearing;
        final boolean onRoute;
        final int segmentIndex;
        final double segmentT;
        final double progressMeters;

        SnapPoint(double lat, double lng, double bearing, boolean onRoute,
                  int segmentIndex, double segmentT, double progressMeters) {
            this.lat = lat;
            this.lng = lng;
            this.bearing = bearing;
            this.onRoute = onRoute;
            this.segmentIndex = segmentIndex;
            this.segmentT = segmentT;
            this.progressMeters = progressMeters;
        }
    }

    protected static double bearingDelta(double a, double b) {
        if (!Double.isFinite(a) || !Double.isFinite(b)) return 0d;
        double d = Math.abs(((b - a + 540d) % 360d) - 180d);
        return Math.max(0d, Math.min(180d, d));
    }

    protected SnapPoint pointAtRouteProgressLocked(double progressMeters) {
        if (routePoints.size() < 2) {
            return new SnapPoint(driverLat, driverLng, currentBearing, false, 0, 0d, 0d);
        }
        double total = routeCumulativeMeters.get(routeCumulativeMeters.size() - 1);
        double p = Math.max(0d, Math.min(total, progressMeters));
        int i = segmentForProgressLocked(p);
        double[] a = routePoints.get(i);
        double[] b = routePoints.get(i + 1);
        double start = routeCumulativeMeters.get(i);
        double segM = Math.max(0.01d, meters(a[0], a[1], b[0], b[1]));
        double t = Math.max(0d, Math.min(1d, (p - start) / segM));
        double lat = a[0] + (b[0] - a[0]) * t;
        double lng = a[1] + (b[1] - a[1]) * t;
        return new SnapPoint(lat, lng, bearing(a[0], a[1], b[0], b[1]), true, i, t, p);
    }


    protected double routeProgressMeters(int index) {
        synchronized (routePoints) {
            if (routeCumulativeMeters.isEmpty()) return 0d;
            int i = Math.max(0, Math.min(index, routeCumulativeMeters.size() - 1));
            return routeCumulativeMeters.get(i);
        }
    }

    protected SnapPoint advanceAlongRoute(SnapPoint base, double metersAhead) {
        synchronized (routePoints) {
            if (!base.onRoute || routePoints.size() < 2 || metersAhead <= 0d) return base;

            double targetProgress = base.progressMeters + metersAhead;
            int i = Math.max(0, Math.min(base.segmentIndex, routePoints.size() - 2));

            while (i < routePoints.size() - 2 &&
                    routeProgressMeters(i + 1) < targetProgress) {
                i++;
            }

            double[] a = routePoints.get(i);
            double[] b = routePoints.get(i + 1);
            double startM = routeProgressMeters(i);
            double segM = Math.max(0.01d, meters(a[0], a[1], b[0], b[1]));
            double t = Math.max(0d, Math.min(1d, (targetProgress - startM) / segM));
            double lat = a[0] + (b[0] - a[0]) * t;
            double lng = a[1] + (b[1] - a[1]) * t;
            double brg = bearing(a[0], a[1], b[0], b[1]);
            return new SnapPoint(lat, lng, brg, true, i, t,
                    Math.min(targetProgress, routeProgressMeters(routePoints.size() - 1)));
        }
    }

    protected void updateInstructionBanner(double progressMeters) {
        if (instructionController != null) instructionController.update(routeManeuvers, progressMeters);
    }

    protected String routeGeoJson(String pointsJson) throws Exception {
        JSONArray pts = new JSONArray(pointsJson);
        JSONArray coords = new JSONArray();
        for (int i = 0; i < pts.length(); i++) {
            JSONArray p = pts.optJSONArray(i);
            if (p == null || p.length() < 2) continue;
            JSONArray c = new JSONArray();
            c.put(p.optDouble(1)); // lng
            c.put(p.optDouble(0)); // lat
            coords.put(c);
        }
        JSONObject geometry = new JSONObject();
        geometry.put("type", "LineString");
        geometry.put("coordinates", coords);
        JSONObject feature = new JSONObject();
        feature.put("type", "Feature");
        feature.put("properties", new JSONObject());
        feature.put("geometry", geometry);
        return feature.toString();
    }

    protected String emptyFeatureCollection() {
        return "{\"type\":\"FeatureCollection\",\"features\":[]}";
    }

    // Upload lokasi server ditangani eksklusif oleh LocationService.
    // NavigationActivity hanya memproses lokasi untuk UI/rute/kamera.

    protected double targetLat() {
        double fromOrder = targetMode.equals("delivery") ?
                coord("delivery_lat", "destination_lat") :
                coord("pickup_lat", "user_lat");
        return valid(fromOrder, targetLngFromOrder()) ? fromOrder : explicitTargetLat;
    }

    protected double targetLng() {
        double fromOrder = targetLngFromOrder();
        double fromLat = targetMode.equals("delivery") ?
                coord("delivery_lat", "destination_lat") :
                coord("pickup_lat", "user_lat");
        return valid(fromLat, fromOrder) ? fromOrder : explicitTargetLng;
    }

    protected double targetLngFromOrder() {
        return targetMode.equals("delivery") ?
                coord("delivery_lng", "destination_lng") :
                coord("pickup_lng", "user_lng");
    }

    protected String routeTargetMode() {
        String st = first(order.optString("status"), "taken").toLowerCase(Locale.US);
        if (st.equals("arrived_pickup") || st.equals("on_delivery") || st.equals("arrived_delivery")) {
            return "delivery";
        }
        return "pickup";
    }

    protected double coord(String a, String b) {
        try { return Double.parseDouble(first(order.optString(a), order.optString(b), "0")); }
        catch (Exception e) { return 0d; }
    }

    protected String normalizeVehicle(String value) {
        value = first(value, "motor").toLowerCase(Locale.US);
        return value.contains("car") || value.contains("mobil") ? "car" : "motor";
    }

    protected float meters(double aLat, double aLng, double bLat, double bLng) {
        try {
            float[] r = new float[1];
            Location.distanceBetween(aLat, aLng, bLat, bLng, r);
            return r[0];
        } catch (Exception e) {
            return 999999f;
        }
    }

    protected double bearing(double lat1, double lng1, double lat2, double lng2) {
        if (!valid(lat1, lng1) || !valid(lat2, lng2)) return currentBearing;
        double dl = Math.toRadians(lng2 - lng1);
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) -
                Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360d) % 360d;
    }

    protected boolean valid(double lat, double lng) {
        return Double.isFinite(lat) && Double.isFinite(lng) && lat != 0d && lng != 0d;
    }

    protected String first(String... values) {
        if (values == null) return "";
        for (String s : values) {
            if (s != null && !s.trim().isEmpty() && !"null".equalsIgnoreCase(s.trim())) return s.trim();
        }
        return "";
    }

    protected int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + .5f);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 801 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            NavigationDiagnostics.event(this, "NAV_LOCATION_PERMISSION_GRANTED", null);
            startLocationWatch();
        } else if (requestCode == 801) {
            NavigationDiagnostics.event(this, "NAV_LOCATION_PERMISSION_DENIED", null);
            if (routeBadge != null) routeBadge.setText("Lokasi nonaktif — peta tetap dapat dibuka");
            showMapFallback("Lokasi belum diizinkan. Navigasi eksternal tetap tersedia.");
        }
    }

    @Override protected void onStart() {
        super.onStart(); activityStarted = true; if (mapController != null) mapController.onStart();
        if (communicationController != null) communicationController.onStart();
    }

    @Override protected void onResume() {
        super.onResume(); activityResumed = true; if (mapController != null) mapController.onResume(); startLocationWatch();
    }

    /**
     * When the driver presses Home while navigation is active, Android 8+ keeps
     * this Activity visible in a small Picture-in-Picture window. We deliberately
     * keep MapLibre and the GPS listener alive while PiP is visible so the vehicle
     * marker, route progress and instructions continue moving in real time.
     */
    @Override public void onUserLeaveHint() {
        super.onUserLeaveHint();
        // OEM-SAFE P0 FIX: never auto-enter PiP from this lifecycle callback.
        // Several Android skins dispatch onUserLeaveHint() during transient system UI,
        // permission panels, calls/chats and launcher transitions. Entering PiP here while
        // MapLibre owns a GL surface can cause the navigation Activity to be stopped or
        // recreated unexpectedly. Navigation must remain full-screen unless PiP is invoked
        // explicitly by a future user action.
        NavigationDiagnostics.event(this, "NAV_USER_LEAVE_HINT_IGNORED", null);
    }


    protected void enterNavigationPictureInPicture() {
        // Kept only for an explicit future PiP button. Automatic lifecycle entry is disabled.
        if (pipController != null) pipController.enter();
    }

    @Override public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode,
                                                         android.content.res.Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        inPictureInPicture = isInPictureInPictureMode;

        // PiP is map-first: remove every large overlay that can collapse into a
        // white vertical pill/button on narrow OEM launchers.
        if (backButton != null) backButton.setVisibility(isInPictureInPictureMode ? View.GONE : View.VISIBLE);
        if (routeBadge != null) routeBadge.setVisibility(isInPictureInPictureMode ? View.GONE : View.VISIBLE);
        if (speedBadge != null) speedBadge.setVisibility(isInPictureInPictureMode ? View.GONE : View.VISIBLE);
        if (communicationController != null) communicationController.onPictureInPictureModeChanged(isInPictureInPictureMode);
        if (instructionBadge != null) {
            instructionBadge.setVisibility(isInPictureInPictureMode ? View.GONE : View.VISIBLE);
            instructionBadge.setTextSize(15);
        }

        if (isInPictureInPictureMode) {
            startLocationWatch();
            updateNativePosition(true);
        } else if (map != null && styleReady) {
            // Restore a useful full-screen navigation zoom after expanding PiP.
            double lat = displayInitialized ? displayLat : driverLat;
            double lng = displayInitialized ? displayLng : driverLng;
            if (valid(lat, lng) && cameraController != null) cameraController.restore(map, lat, lng);
        }
    }

    @Override protected void onPause() {
        activityResumed = false;
        boolean pip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && (inPictureInPicture || isInPictureInPictureMode());
        if (!pip) { stopLocationWatch(); if (mapController != null) mapController.onPause(); }
        super.onPause();
    }

    @Override protected void onStop() {
        activityStarted = false;
        boolean pip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode();
        if (!pip) {
            stopLocationWatch();
            if (mapController != null) mapController.onStop();
            if (communicationController != null) communicationController.onStop();
        }
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mapController != null) mapController.onSaveInstanceState(outState);
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // Do not finish navigation under memory pressure. Let MapLibre release caches and
        // reduce visual work; GPS/route guidance remains alive even if map rendering degrades.
        if (mapController != null && level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            mapController.onLowMemory();
        }
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            if (frameController != null) {
                frameController.stop();
                if (activityResumed || inPictureInPicture) frameController.start();
            }
            NavigationDiagnostics.event(this, "NAV_MEMORY_PRESSURE_" + level, null);
        }
    }

    @Override public void onLowMemory() {
        super.onLowMemory();
        if (mapController != null) mapController.onLowMemory();
    }

    @Override protected void onDestroy() {
        stopLocationWatch();
        if (frameController != null) frameController.stop();
        if (routeScheduler != null) routeScheduler.stop();
        main.removeCallbacksAndMessages(null);
        routeExecutor.shutdownNow();
        if (communicationController != null) communicationController.onStop();
        if (mapController != null) mapController.onDestroy();
        super.onDestroy();
    }
    // Cross-layer contracts keep the split type-safe without duplicating state.
    protected abstract void readOrder();
    protected abstract void readIdentity();
    protected abstract void applyNavigationResourceConfig();
    protected abstract void seedLastKnownLocation();
    protected abstract void buildUiShell();
    protected abstract void initNativeMap(Bundle state);
    protected abstract void failNativeMap(String stage, Throwable t);
    protected abstract void showMapFallback(String message);
    protected abstract void hideMapFallback();
    protected abstract void openExternalNavigation();
    protected abstract android.graphics.drawable.GradientDrawable roundRect(int color, int radiusDp);
    protected abstract void installRouteLayers();
    protected abstract void installVehicleLayer();
    protected abstract void updateVehicleSource();
    protected abstract void installMarkers();
    protected abstract void startLocationWatch();
    protected abstract void stopLocationWatch();
    protected abstract void handleNavigationLocationFix(Location raw, SmoothLocationEngine.Fix fix);
    protected abstract void animateTowardLatestFix();
    protected abstract double easeBearing(double current, double target, float alpha);
    protected abstract double easePosition(double current, double target, float alpha);
    protected abstract void updateNativePosition(boolean immediate);
    protected abstract void updateSpeed(Location l);
    protected abstract void requestRoute(boolean force);
    protected abstract void showRouteLoading(int percent);
    protected abstract void maybeRefreshRoute(Location fix);
    protected abstract void resetOffRouteConfirmation();
    protected abstract double nearestRouteDistanceMeters(double lat, double lng);
    protected abstract void maybeUpdateRemainingRouteLine();
    protected abstract void updateRemainingRouteLine(boolean force);
    protected abstract String remainingRouteGeoJson();
    protected abstract int segmentForProgressLocked(double progressMeters);
    protected abstract void drawPendingRoute();
    protected abstract void setRoutePoints(JSONArray points);
    protected abstract SnapPoint snapToRoute(double lat, double lng);

}
