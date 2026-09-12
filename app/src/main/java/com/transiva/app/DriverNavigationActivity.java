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
public class DriverNavigationActivity extends DriverNavigationActivityLayer2 {

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        NavigationDiagnostics.event(this, "NAV_ACTIVITY_CREATED", null);
        NavigationHealthTelemetry.mark(this, "open");

        try {
            if (getActionBar() != null) getActionBar().hide();
            getWindow().setStatusBarColor(Color.parseColor("#0B3A78"));
            getWindow().setNavigationBarColor(Color.BLACK);
        } catch (Throwable t) {
            NavigationDiagnostics.error(this, "NAV_WINDOW_SETUP_FAILED", t);
        }

        session = new SessionManager(this);
        readOrder();
        readIdentity();
        applyNavigationResourceConfig();
        navProfile = NavigationCompatibilityProfile.resolve(this, navConfig != null ? navConfig.profile : "auto");
        NavigationDiagnostics.event(this, "NAV_PROFILE_" + navProfile.mode.name(), null);
        frameController = new NavigationFrameController(main, navProfile.visualFrameMs, this::animateTowardLatestFix);
        routeScheduler = new NavigationRouteScheduler(main, new NavigationRouteScheduler.Callback() {
            @Override public boolean isFinishing() { return DriverNavigationActivity.this.isFinishing(); }
            @Override public boolean isRouteInFlight() { return routeInFlight; }
            @Override public boolean hasRoute() { return routePoints.size() >= 2; }
            @Override public void requestRoute(boolean force) { DriverNavigationActivity.this.requestRoute(force); }
            @Override public void showRouteLoading(int percent) { DriverNavigationActivity.this.showRouteLoading(percent); }
        });
        locationController = new NavigationLocationController(this, smoothLocation, new NavigationLocationController.Callback() {
            @Override public void onSmoothFix(Location raw, SmoothLocationEngine.Fix fix) { handleNavigationLocationFix(raw, fix); }
            @Override public void onPermissionRequired() {
                if (routeBadge != null) routeBadge.setText("Aktifkan lokasi untuk memulai navigasi");
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 801);
                NavigationDiagnostics.event(DriverNavigationActivity.this, "NAV_LOCATION_PERMISSION_REQUEST", null);
            }
        });
        pipController = new NavigationPipController(this, navProfile);
        cameraController = new NavigationCameraController(navProfile);
        vehicleController = new NavigationVehicleController(this, vehicleType);
        markerController = new NavigationMarkerController(this, order);
        seedLastKnownLocation();

        // P0: render a lightweight navigation shell first. Heavy MapLibre startup is
        // deferred until the Activity is already visible, avoiding OEM/GPU startup stalls.
        buildUiShell();
        instructionController = new NavigationInstructionController(instructionBadge);
        if (pipController != null) pipController.configure();

        main.postDelayed(() -> initNativeMap(savedInstanceState), 120L);
        main.postDelayed(() -> {
            if (!styleReady && !isFinishing()) {
                NavigationDiagnostics.event(this, "NAV_STYLE_TIMEOUT", null);
                if (mapController != null) mapController.trySecondary("NAV_PRIMARY_STYLE_TIMEOUT", new IllegalStateException("Primary map style timeout"));
                if (navConfig == null || navConfig.externalFallback) showMapFallback("Peta sedang lambat. Menyiapkan jalur cadangan…");
            }
        }, mapStyleTimeoutMs);

        // Route/GPS do not depend on map rendering and can safely warm in parallel.
        requestRoute(true);
        startLocationWatch();
        frameController.start();
        routeScheduler.start();
    }

    protected void readOrder() {
        try {
            String raw = getIntent().getStringExtra("order_json");
            if (raw != null && raw.trim().startsWith("{")) order = new JSONObject(raw);
        } catch (Exception e) { TransivaDiagnostics.error(this,"navigation","ORDER_JSON_PARSE_FAILED",e); }
        try {
            String mode = first(getIntent().getStringExtra("target"), getIntent().getStringExtra("target_mode"));
            if (mode.toLowerCase(Locale.US).contains("delivery")) targetMode = "delivery";
            else if (mode.toLowerCase(Locale.US).contains("pickup")) targetMode = "pickup";
            else targetMode = routeTargetMode();
        } catch (Exception e) { TransivaDiagnostics.error(this,"navigation","TARGET_MODE_PARSE_FAILED",e); }

        driverLat = getIntent().getDoubleExtra("driver_lat", 0d);
        driverLng = getIntent().getDoubleExtra("driver_lng", 0d);
        explicitTargetLat = getIntent().getDoubleExtra("target_lat", 0d);
        explicitTargetLng = getIntent().getDoubleExtra("target_lng", 0d);
    }

    protected void readIdentity() {
        try {
            username = first(session.getUsername(), session.getName(),
                    getSharedPreferences("transiva", MODE_PRIVATE).getString("username", ""));
            vehicleType = normalizeVehicle(first(session.getDriverType(),
                    getSharedPreferences("transiva", MODE_PRIVATE).getString("driver_type", "motor")));
        } catch (Exception e) { TransivaDiagnostics.error(this,"session","NAV_IDENTITY_READ_FAILED",e); }
    }


    protected void applyNavigationResourceConfig() {
        navConfig = NavigationRuntimeConfig.load(this);
        mapStyleUrl = navConfig.primaryStyle;
        mapStyleTimeoutMs = navConfig.styleTimeoutMs;
        NavigationDiagnostics.event(this, "NAV_RESOURCE_CONFIG_APPLIED", null);
    }

    protected void seedLastKnownLocation() {
        if (valid(driverLat, driverLng) || locationController == null) return;
        Location best = locationController.bestLastKnown();
        if (best != null && valid(best.getLatitude(), best.getLongitude())) {
            driverLat = best.getLatitude(); driverLng = best.getLongitude(); lastLocation = new Location(best);
        }
    }

    protected void buildUiShell() {
        FrameLayout page = new FrameLayout(this);
        navigationRoot = page;
        page.setBackgroundColor(Color.parseColor("#EAF4FF"));

        // FIX: kendaraan tidak lagi berupa ImageView Gravity.CENTER.
        // Marker kendaraan sekarang terikat ke LatLng di peta, sehingga saat pinch zoom
        // kendaraan tetap berada pada koordinat/rute dan tidak mengikuti titik tengah HP.

        backButton = new TextView(this);
        backButton.setText("‹");
        backButton.setTextSize(38);
        backButton.setTextColor(Color.parseColor("#0B3A78"));
        backButton.setGravity(Gravity.CENTER);
        backButton.setBackground(roundRect(Color.parseColor("#FCFFFFFF"), 20));
        backButton.setElevation(dp(8));
        backButton.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams backLp = new FrameLayout.LayoutParams(dp(54), dp(54));
        backLp.leftMargin = dp(16);
        backLp.topMargin = dp(18);
        page.addView(backButton, backLp);

        routeBadge = new TextView(this);
        routeBadge.setText(targetMode.equals("delivery") ? "Menyiapkan rute ke pengantaran…" : "Menyiapkan rute ke pickup…");
        routeBadge.setTextColor(Color.parseColor("#082F63"));
        routeBadge.setTextSize(17);
        routeBadge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        routeBadge.setGravity(Gravity.CENTER_VERTICAL);
        routeBadge.setPadding(dp(20), 0, dp(20), 0);
        routeBadge.setBackground(roundRect(Color.parseColor("#FCFFFFFF"), 26));
        routeBadge.setElevation(dp(8));
        FrameLayout.LayoutParams routeLp = new FrameLayout.LayoutParams(-1, dp(62));
        routeLp.leftMargin = dp(84);
        routeLp.rightMargin = dp(18);
        routeLp.topMargin = dp(18);
        page.addView(routeBadge, routeLp);

        instructionBadge = new TextView(this);
        instructionBadge.setText("↑ Ikuti rute");
        instructionBadge.setTextColor(Color.parseColor("#0A356C"));
        instructionBadge.setTextSize(15);
        instructionBadge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        instructionBadge.setGravity(Gravity.CENTER_VERTICAL);
        instructionBadge.setPadding(dp(18), dp(8), dp(18), dp(8));
        instructionBadge.setBackground(roundRect(Color.parseColor("#FAFFFFFF"), 20));
        instructionBadge.setElevation(dp(6));
        FrameLayout.LayoutParams instructionLp = new FrameLayout.LayoutParams(-1, dp(54));
        instructionLp.leftMargin = dp(84);
        instructionLp.rightMargin = dp(18);
        instructionLp.topMargin = dp(86);
        page.addView(instructionBadge, instructionLp);

        LinearLayout bottomActions = new LinearLayout(this);
        bottomActions.setOrientation(LinearLayout.HORIZONTAL);
        bottomActions.setGravity(Gravity.CENTER_VERTICAL);

        speedBadge = new TextView(this);
        speedBadge.setText("0 km/j\nRata-rata 0 km/j");
        speedBadge.setTextColor(Color.WHITE);
        speedBadge.setTextSize(16);
        speedBadge.setPadding(dp(16), dp(10), dp(16), dp(10));
        speedBadge.setBackground(roundRect(Color.parseColor("#E6071426"), 22));
        bottomActions.addView(speedBadge, new LinearLayout.LayoutParams(0, dp(58), 1.25f));

        messageButton = new TextView(this);
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(0, dp(52), 1f);
        msgLp.setMargins(dp(8), 0, 0, 0);
        bottomActions.addView(messageButton, msgLp);

        callButton = new TextView(this);
        LinearLayout.LayoutParams callLp = new LinearLayout.LayoutParams(0, dp(52), 0.9f);
        callLp.setMargins(dp(8), 0, 0, 0);
        bottomActions.addView(callButton, callLp);

        FrameLayout.LayoutParams bottomLp = new FrameLayout.LayoutParams(-1, dp(62));
        bottomLp.leftMargin = dp(18);
        bottomLp.rightMargin = dp(18);
        bottomLp.bottomMargin = dp(24);
        bottomLp.gravity = Gravity.BOTTOM;
        page.addView(bottomActions, bottomLp);
        communicationController = new NavigationCommunicationController(this, order, messageButton, callButton);

        TextView attribution = new TextView(this);
        attribution.setText("© OpenStreetMap contributors");
        attribution.setTextColor(Color.parseColor("#7A475569"));
        attribution.setTextSize(8);
        FrameLayout.LayoutParams attrLp = new FrameLayout.LayoutParams(-2, -2);
        attrLp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        attrLp.rightMargin = dp(8);
        attrLp.bottomMargin = dp(6);
        page.addView(attribution, attrLp);

        setContentView(page);
        DriverAppSettings.apply(this);
    }


    protected void initNativeMap(Bundle state) {
        if (mapInitAttempted || isFinishing() || navigationRoot == null) return;
        mapInitAttempted = true;
        NavigationDiagnostics.event(this, "NAV_MAP_INIT", null);
        mapController = new NavigationMapController(this, navigationRoot, navConfig, navProfile,
                new NavigationMapController.Listener() {
                    @Override public void onMapReady(MapLibreMap m) {
                        if (isFinishing()) return;
                        map = m;
                        mapView = mapController.view();
                        if (frameController != null) frameController.attachMapView(mapView);
                        NavigationDiagnostics.event(DriverNavigationActivity.this, "NAV_MAP_READY", null);
                        NavigationHealthTelemetry.mark(DriverNavigationActivity.this, "map");
                    }
                    @Override public void onStyleReady(MapLibreMap m, Style st, boolean secondary) {
                        if (isFinishing()) return;
                        map = m; style = st; styleReady = true; nativeMapFailed = false;
                        hideMapFallback();
                        NavigationDiagnostics.event(DriverNavigationActivity.this,
                                secondary ? "NAV_STYLE_READY_SECONDARY" : "NAV_STYLE_READY_PRIMARY", null);
                        NavigationHealthTelemetry.mark(DriverNavigationActivity.this, "style");
                        installRouteLayers(); installVehicleLayer(); installMarkers(); drawPendingRoute(); updateNativePosition(true);
                    }
                    @Override public void onFailure(String stage, Throwable error) { failNativeMap(stage, error); }
                });
        if (activityStarted) mapController.onStart();
        if (activityResumed) mapController.onResume();
        mapController.create(state);
        mapView = mapController.view();
        if (frameController != null) frameController.attachMapView(mapView);
    }

    protected void failNativeMap(String stage, Throwable t) {
        nativeMapFailed = true;
        NavigationDiagnostics.error(this, stage, t);
        if (mapController != null && !styleReady && navConfig != null && !stage.contains("SECONDARY")) {
            mapController.trySecondary(stage, t);
            return;
        }
        if (navConfig == null || navConfig.externalFallback) showMapFallback("Peta native tidak dapat dimuat di perangkat ini.");
        else if (routeBadge != null) routeBadge.setText("Peta native gagal dimuat. Coba buka ulang navigasi.");
    }

    protected void showMapFallback(String message) {
        if (routeBadge != null && !styleReady) routeBadge.setText(message);
        if (fallbackButton == null && navigationRoot != null) {
            fallbackButton = new TextView(this);
            fallbackButton.setText("Buka Google Maps");
            fallbackButton.setTextColor(Color.WHITE);
            fallbackButton.setTextSize(15);
            fallbackButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            fallbackButton.setGravity(Gravity.CENTER);
            fallbackButton.setPadding(dp(18), dp(10), dp(18), dp(10));
            fallbackButton.setBackground(roundRect(Color.parseColor("#0B63CE"), 22));
            fallbackButton.setElevation(dp(8));
            fallbackButton.setOnClickListener(v -> openExternalNavigation());
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, dp(48));
            lp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
            lp.bottomMargin = dp(92);
            navigationRoot.addView(fallbackButton, lp);
        }
        if (fallbackButton != null) fallbackButton.setVisibility(View.VISIBLE);
    }

    protected void hideMapFallback() {
        if (fallbackButton != null) fallbackButton.setVisibility(View.GONE);
    }

    protected void openExternalNavigation() {
        double lat = explicitTargetLat, lng = explicitTargetLng;
        if (!valid(lat, lng)) {
            if (targetMode.equals("delivery")) { lat = coord("delivery_lat", "destination_lat"); lng = coord("delivery_lng", "destination_lng"); }
            else { lat = coord("pickup_lat", "user_lat"); lng = coord("pickup_lng", "user_lng"); }
        }
        if (!valid(lat, lng)) { TransivaDiagnostics.event(this,"navigation","EXTERNAL_TARGET_INVALID"); return; }
        NavigationExternalController.open(this, lat, lng);
    }

    protected android.graphics.drawable.GradientDrawable roundRect(int color, int radiusDp) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    protected void installRouteLayers() {
        if (style == null) return;
        try {
            if (style.getSource(ROUTE_SOURCE) == null) {
                style.addSource(new GeoJsonSource(ROUTE_SOURCE, emptyFeatureCollection()));
            }
            // Wide translucent glow makes the active route readable on both light
            // and dark basemaps without obscuring junction details.
            if (style.getLayer(ROUTE_GLOW_LAYER) == null) {
                style.addLayer(new LineLayer(ROUTE_GLOW_LAYER, ROUTE_SOURCE).withProperties(
                        lineColor(Color.parseColor("#48A8FF")),
                        lineOpacity(0.20f),
                        lineWidth(18f),
                        lineCap(LINE_CAP_ROUND),
                        lineJoin(LINE_JOIN_ROUND)
                ));
            }
            if (style.getLayer(ROUTE_CASE_LAYER) == null) {
                style.addLayer(new LineLayer(ROUTE_CASE_LAYER, ROUTE_SOURCE).withProperties(
                        lineColor(Color.parseColor("#073A71")),
                        lineOpacity(0.60f),
                        lineWidth(10f),
                        lineCap(LINE_CAP_ROUND),
                        lineJoin(LINE_JOIN_ROUND)
                ));
            }
            if (style.getLayer(ROUTE_LAYER) == null) {
                style.addLayer(new LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
                        lineColor(Color.parseColor("#087CFF")),
                        lineOpacity(0.98f),
                        lineWidth(6f),
                        lineCap(LINE_CAP_ROUND),
                        lineJoin(LINE_JOIN_ROUND)
                ));
            }
        } catch (Exception ignored) { TransivaDiagnostics.error(this,"navigation","NON_FATAL_EXCEPTION",ignored); }
    }

    protected void installVehicleLayer() {
        if (vehicleController == null) vehicleController = new NavigationVehicleController(this, vehicleType);
        double lat = displayInitialized ? displayLat : driverLat;
        double lng = displayInitialized ? displayLng : driverLng;
        vehicleController.install(style, lat, lng);
    }


    protected void updateVehicleSource() {
        if (vehicleController == null) return;
        double lat = displayInitialized ? displayLat : driverLat;
        double lng = displayInitialized ? displayLng : driverLng;
        vehicleController.update(lat, lng);
    }

    protected void installMarkers() {
        if (markerController != null) markerController.install(map);
        updateVehicleSource();
    }



    protected void startLocationWatch() { if (locationController != null) locationController.start(); }

    /**
     * Android may deliver GPS and NETWORK fixes almost back-to-back. When a fresh,
     * reasonably accurate GPS fix exists, accepting the coarser network fix causes
     * the classic left-right map jump. Prefer GPS for a short confidence window,
     * but immediately allow network fallback when GPS becomes stale or weak.
     */

    protected void stopLocationWatch() { if (locationController != null) locationController.stop(); }

    protected void handleNavigationLocationFix(Location raw, SmoothLocationEngine.Fix fix) {
        if (fix == null || fix.location == null) return;
        Location l = fix.location;
        lastLocation = new Location(l);
        driverLat = l.getLatitude(); driverLng = l.getLongitude();
        lastFixRealtimeMs = SystemClock.elapsedRealtime();
        lastGpsAccuracyM = l.hasAccuracy() ? Math.max(1f, l.getAccuracy()) : 50f;
        updateSpeed(l);
        if (l.hasBearing() && l.getSpeed() > 1.2f) currentBearing = l.getBearing();
        else if (displayInitialized) currentBearing = bearing(displayLat, displayLng, driverLat, driverLng);
        if (fix.render) updateNativePosition(false);
        maybeRefreshRoute(l);
    }

    /**
     * Native continuous interpolation. The latest GPS fix becomes a moving
     * target; the display advances a fraction every 50 ms instead of stopping
     * between 1-second fixes.
     */
    protected void animateTowardLatestFix() {
        if (!valid(driverLat, driverLng) || map == null || !styleReady) return;

        SnapPoint base = snapToRoute(driverLat, driverLng);
        SnapPoint target = base;

        // Dead-reckoning between GPS fixes: the visual target keeps moving at the
        // measured speed for a short bounded window, so the icon/map does not
        // move-stop-move between 700-1300 ms location samples.
        if (base.onRoute && currentSpeedKmh > 2d && lastFixRealtimeMs > 0L && lastGpsAccuracyM < 35f) {
            // CLEAN navigation: only a short prediction on a trustworthy GPS fix.
            // Long dead-reckoning was visually smooth in ideal conditions but could
            // overshoot corners/tunnels and appear as a map/marker "glitch".
            double maxPredictionSec = lastGpsAccuracyM < 15f ? 0.80d : 0.55d;
            double ageSec = Math.max(0d, Math.min(maxPredictionSec,
                    (SystemClock.elapsedRealtime() - lastFixRealtimeMs) / 1000d));
            double predictedSpeedMps = Math.max(0d, currentSpeedKmh / 3.6d);
            double lookAheadMeters = Math.min(12d, predictedSpeedMps * ageSec);
            target = advanceAlongRoute(base, lookAheadMeters);
        }

        if (target.onRoute) {
            visualRouteProgressMeters = target.progressMeters;
            visualRouteSegmentIndex = target.segmentIndex;
        }

        // A weak GPS sample must never make the visual position teleport far
        // along the route. Hold the last clean position until a better sample arrives.
        if (displayInitialized && lastGpsAccuracyM >= 35f
                && meters(displayLat, displayLng, target.lat, target.lng) > 35f) {
            updateInstructionBanner(visualRouteProgressMeters);
            return;
        }

        if (!displayInitialized) {
            displayLat = target.lat;
            displayLng = target.lng;
            snappedBearing = target.bearing;
            displayInitialized = true;
            updateNativePosition(true);
            maybeUpdateRemainingRouteLine();
            updateInstructionBanner(target.progressMeters);
            return;
        }

        if (target.onRoute) snappedBearing = target.bearing;

        float distance = meters(displayLat, displayLng, target.lat, target.lng);
        if (distance > 0.05f) {
            // Adaptive smoothing: slow driving advances gently; fast driving catches
            // the visual target more quickly. Frame loop is ~60 FPS.
            double speedFactor = Math.max(0d, Math.min(1d, currentSpeedKmh / 55d));
            long frameNow = SystemClock.elapsedRealtime();
            double frameMs = lastVisualFrameAt <= 0L ? 16.67d
                    : Math.max(8d, Math.min(50d, frameNow - lastVisualFrameAt));
            lastVisualFrameAt = frameNow;
            // Time-based exponential easing produces the same motion on 60/90/120 Hz
            // screens and survives an occasional slow frame without visible snapping.
            double tauMs = 350d - 155d * speedFactor;
            float alpha = (float) (1d - Math.exp(-frameMs / tauMs));
            if (distance > 25f) alpha = Math.max(alpha, 0.18f);
            displayLat = easePosition(displayLat, target.lat, alpha);
            displayLng = easePosition(displayLng, target.lng, alpha);

            // MapLibre camera updates are expensive. Rendering every 16 ms can
            // overload mid-range phones and actually look less smooth. Keep the
            // physics loop at 60 Hz, but render the map at a stable ~30 FPS
            // (PiP ~15 FPS), always using the newest interpolated position.
            long now = SystemClock.elapsedRealtime();
            boolean pip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    (inPictureInPicture || isInPictureInPictureMode());
            long cleanFrame = navProfile != null ? Math.max(16L, navProfile.visualFrameMs) : 33L;
            long renderInterval = pip ? Math.max(66L, cleanFrame) : cleanFrame;
            if (now - lastMapRenderAt >= renderInterval) {
                lastMapRenderAt = now;
                updateNativePosition(false);
            }
        }

        maybeUpdateRemainingRouteLine();
        updateInstructionBanner(target.progressMeters);
    }
}
