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
            DriverSystemBars.setStatusBarColor(this, DriverThemeTokens.color(this, "#0B3A78"));
            DriverSystemBars.setNavigationBarColor(this, DriverThemeTokens.background(this));
        } catch (Throwable t) {
            NavigationDiagnostics.error(this, "NAV_WINDOW_SETUP_FAILED", t);
        }

        session = new SessionManager(this);
        readOrder();
        readIdentity();
        applyNavigationResourceConfig();
        navProfile = NavigationCompatibilityProfile.resolve(this, navConfig != null ? navConfig.profile : "auto");
        NavigationDisplayPolicy.prefer(this,navProfile.visualFrameMs);
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
        if(savedInstanceState!=null&&!savedInstanceState.getBoolean("nav_camera_follow",true))cameraController.follow(false);
        vehicleController = new NavigationVehicleController(this, vehicleType);
        markerController = new NavigationMarkerController(this, order);
        seedLastKnownLocation();

        // P0: render a lightweight navigation shell first. Heavy MapLibre startup is
        // deferred until the Activity is already visible, avoiding OEM/GPU startup stalls.
        buildUiShell();
        instructionController = new NavigationInstructionController(instructionBadge,targetMode);
        liveRoutes = new NavigationLiveRouteController(this);
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

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);if(isFinishing()||isDestroyed())return;setIntent(intent);readOrder();readIdentity();
        if(communicationController!=null)communicationController.onStop();
        communicationController=new NavigationCommunicationController(this,order,messageButton,callButton);
        if(activityStarted)communicationController.onStart();
        if(markerController!=null)markerController.clear(map);
        markerController=new NavigationMarkerController(this,order);installMarkers();
        if(liveRoutes!=null)liveRoutes.targetChanged();
    }

    protected void readOrder() {
        try {
            String raw = getIntent().getStringExtra("order_json");
            if (raw != null && raw.trim().startsWith("{")) order = new JSONObject(raw);
        } catch (Exception e) { TransivaDiagnostics.error(this,"navigation","ORDER_JSON_PARSE_FAILED",e); }
        try {
            String mode = first(getIntent().getStringExtra("target"), getIntent().getStringExtra("target_mode"));
            if (mode.trim().toLowerCase(Locale.US).matches("waypoint_[0-9]+")) targetMode = mode.trim().toLowerCase(Locale.US);
            else if (mode.toLowerCase(Locale.US).contains("delivery")) targetMode = "delivery";
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
        if (locationController == null) return;
        Location best = locationController.bestLastKnown();
        if (best != null && valid(best.getLatitude(), best.getLongitude())) {
            driverLat = best.getLatitude(); driverLng = best.getLongitude(); lastLocation = new Location(best); lastFixRealtimeMs=SystemClock.elapsedRealtime(); lastGpsAccuracyM=best.getAccuracy();
        }
    }

    protected void buildUiShell() {
        FrameLayout page = new FrameLayout(this);
        navigationRoot = page;
        page.setBackgroundColor(DriverThemeTokens.color(this, "#EAF4FF"));

        // FIX: kendaraan tidak lagi berupa ImageView Gravity.CENTER.
        // Marker kendaraan sekarang terikat ke LatLng di peta, sehingga saat pinch zoom
        // kendaraan tetap berada pada koordinat/rute dan tidak mengikuti titik tengah HP.

        LinearLayout header=new LinearLayout(this);header.setOrientation(LinearLayout.VERTICAL);header.setPadding(dp(14),dp(10),dp(14),dp(10));
        header.setBackground(roundRect(DriverThemeTokens.surface(this),22));header.setElevation(dp(6));
        FrameLayout.LayoutParams headLp=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP);headLp.setMargins(dp(12),dp(12),dp(12),0);
        android.widget.ScrollView headerScroll=new android.widget.ScrollView(this);headerScroll.setTag("navigation_header_container");headerScroll.setVerticalScrollBarEnabled(false);headerScroll.addView(header,new android.widget.ScrollView.LayoutParams(-1,-2));page.addView(headerScroll,headLp);
        page.addOnLayoutChangeListener((v,l,t,rr,b,ol,ot,orr,ob)->{int desired=header.getMeasuredHeight(),limit=Math.max(dp(100),page.getHeight()-dp(130));int height=Math.min(desired,limit);FrameLayout.LayoutParams hp=(FrameLayout.LayoutParams)headerScroll.getLayoutParams();if(height>0&&hp.height!=height){hp.height=height;headerScroll.setLayoutParams(hp);}});
        LinearLayout titleRow=new LinearLayout(this);titleRow.setGravity(Gravity.CENTER_VERTICAL);header.addView(titleRow,new LinearLayout.LayoutParams(-1,-2));
        backButton=new TextView(this);backButton.setText("‹");backButton.setTextSize(32);backButton.setGravity(Gravity.CENTER);backButton.setTextColor(DriverThemeTokens.textPrimary(this));backButton.setContentDescription("Kembali dari navigasi");backButton.setOnClickListener(v->finish());titleRow.addView(backButton,new LinearLayout.LayoutParams(dp(48),dp(48)));
        routeBadge=new TextView(this);routeBadge.setText("Menyiapkan rute…");routeBadge.setTextColor(DriverThemeTokens.textPrimary(this));routeBadge.setTextSize(14);routeBadge.setTypeface(Typeface.DEFAULT,Typeface.BOLD);routeBadge.setMaxLines(3);routeBadge.setPadding(dp(6),0,0,0);titleRow.addView(routeBadge,new LinearLayout.LayoutParams(0,-2,1));
        instructionBadge=new TextView(this);instructionBadge.setText("↑ Ikuti rute");instructionBadge.setTextColor(DriverThemeTokens.textPrimary(this));instructionBadge.setTextSize(15);instructionBadge.setMaxLines(3);instructionBadge.setTypeface(Typeface.DEFAULT,Typeface.BOLD);instructionBadge.setPadding(dp(12),dp(10),dp(12),dp(10));instructionBadge.setBackground(roundRect(DriverThemeTokens.surfaceAlt(this),14));LinearLayout.LayoutParams instructionLp=new LinearLayout.LayoutParams(-1,-2);instructionLp.topMargin=dp(6);header.addView(instructionBadge,instructionLp);
        // Options are inserted into this measured column, avoiding absolute vertical offsets.
        header.setTag("navigation_header");
        LinearLayout bottomActions=new LinearLayout(this);bottomActions.setGravity(Gravity.CENTER_VERTICAL);bottomActions.setPadding(dp(8),dp(8),dp(8),dp(8));bottomActions.setBackground(roundRect(DriverThemeTokens.surface(this),22));bottomActions.setElevation(dp(6));
        speedBadge=new TextView(this);speedBadge.setText("0 km/j\nRata-rata 0 km/j");speedBadge.setTextColor(DriverThemeTokens.textPrimary(this));speedBadge.setTextSize(12);speedBadge.setGravity(Gravity.CENTER);speedBadge.setMaxLines(3);bottomActions.addView(speedBadge,new LinearLayout.LayoutParams(0,-2,1.15f));
        messageButton=new TextView(this);LinearLayout.LayoutParams msgLp=new LinearLayout.LayoutParams(0,dp(48),1);msgLp.leftMargin=dp(6);bottomActions.addView(messageButton,msgLp);
        callButton=new TextView(this);LinearLayout.LayoutParams callLp=new LinearLayout.LayoutParams(0,dp(48),1);callLp.leftMargin=dp(6);bottomActions.addView(callButton,callLp);
        FrameLayout.LayoutParams bottomLp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);bottomLp.setMargins(dp(12),0,dp(12),dp(22));page.addView(bottomActions,bottomLp);
        communicationController=new NavigationCommunicationController(this,order,messageButton,callButton);
        recenterButton=new TextView(this);recenterButton.setText("◎  Ikuti");recenterButton.setTextSize(14);recenterButton.setTypeface(Typeface.DEFAULT,Typeface.BOLD);recenterButton.setGravity(Gravity.CENTER);
        recenterButton.setTextColor(DriverThemeTokens.onAccent(this));recenterButton.setBackground(roundRect(DriverThemeTokens.color(this,"#146DE8"),24));recenterButton.setElevation(dp(5));recenterButton.setContentDescription("Kembali mengikuti posisi kendaraan");
        FrameLayout.LayoutParams followLp=new FrameLayout.LayoutParams(dp(112),dp(48),Gravity.BOTTOM|Gravity.RIGHT);followLp.setMargins(dp(12),0,dp(12),dp(108));page.addView(recenterButton,followLp);
        recenterButton.setVisibility(cameraController!=null&&!cameraController.following()?View.VISIBLE:View.GONE);
        bottomActions.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,orr,ob)->{FrameLayout.LayoutParams fp=(FrameLayout.LayoutParams)recenterButton.getLayoutParams();int margin=bottomActions.getHeight()+dp(34);if(fp.bottomMargin!=margin){fp.bottomMargin=margin;recenterButton.setLayoutParams(fp);}});
        recenterButton.setOnClickListener(v->{if(cameraController==null||map==null)return;map.cancelTransitions();cameraController.follow(true);recenterButton.setVisibility(View.GONE);updateNativePosition(true);});

        TextView attribution = new TextView(this);
        attribution.setText("© OpenStreetMap contributors");
        attribution.setTextColor(DriverThemeTokens.color(this, "#7A475569"));
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
                        cameraController.bind(m,()->{if(recenterButton!=null)recenterButton.setVisibility(View.VISIBLE);},()->{if(styleReady)updateVehicleSource();});
                        mapView = mapController.view();
                        if (frameController != null) frameController.attachMapView(mapView);
                        NavigationDiagnostics.event(DriverNavigationActivity.this, "NAV_MAP_READY", null);
                        NavigationHealthTelemetry.mark(DriverNavigationActivity.this, "map");
                    }
                    @Override public void onStyleReady(MapLibreMap m, Style st, boolean secondary) {
                        if (isFinishing()) return;
                        map = m; style = st; styleReady = true; nativeMapFailed = false;
                        if(liveRoutes!=null)liveRoutes.mapReady();else hideMapFallback();
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
            fallbackButton.setTextColor(DriverThemeTokens.onAccent(this));
            fallbackButton.setTextSize(15);
            fallbackButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            fallbackButton.setGravity(Gravity.CENTER);
            fallbackButton.setPadding(dp(18), dp(10), dp(18), dp(10));
            fallbackButton.setBackground(roundRect(DriverThemeTokens.color(this, "#0B63CE"), 22));
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
        if(NavigationExternalController.open(this, lat, lng,liveRoutes==null?vehicleType:liveRoutes.profile()))NavigationGuidanceService.stop(this);
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
                        lineColor(DriverThemeTokens.color(this, "#48A8FF")),
                        lineOpacity(0.20f),
                        lineWidth(18f),
                        lineCap(LINE_CAP_ROUND),
                        lineJoin(LINE_JOIN_ROUND)
                ));
            }
            if (style.getLayer(ROUTE_CASE_LAYER) == null) {
                style.addLayer(new LineLayer(ROUTE_CASE_LAYER, ROUTE_SOURCE).withProperties(
                        lineColor(DriverThemeTokens.color(this, "#073A71")),
                        lineOpacity(0.60f),
                        lineWidth(10f),
                        lineCap(LINE_CAP_ROUND),
                        lineJoin(LINE_JOIN_ROUND)
                ));
            }
            if (style.getLayer(ROUTE_LAYER) == null) {
                style.addLayer(new LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
                        lineColor(DriverThemeTokens.color(this, "#087CFF")),
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
        double cameraHeading=map==null?0:map.getCameraPosition().bearing;
        vehicleController.update(lat,lng,(Double.isFinite(smoothMarkerBearing)?smoothMarkerBearing:0)-cameraHeading);
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
        if(lastFixRealtimeMs>0&&SystemClock.elapsedRealtime()-lastFixRealtimeMs>12000){
            lastMatchedProgressMeters=Double.NaN;lastMatchedRawLat=Double.NaN;lastMatchedRawLng=Double.NaN;lastMatchRealtimeMs=0;
        }
        Location previousAccepted=lastLocation;
        lastLocation = new Location(l);
        driverLat = l.getLatitude(); driverLng = l.getLongitude();
        lastFixRealtimeMs = SystemClock.elapsedRealtime();
        lastGpsAccuracyM = l.hasAccuracy() ? Math.max(1f, l.getAccuracy()) : 50f;
        updateSpeed(l);
        double motionSpeed=l.hasSpeed()?Math.max(0,l.getSpeed()):currentSpeedKmh/3.6;
        if(motionSpeed>=1.5){
            if(l.hasBearing())currentBearing=l.getBearing();
            else if(previousAccepted!=null&&previousAccepted.distanceTo(l)>=3)currentBearing=bearing(previousAccepted.getLatitude(),previousAccepted.getLongitude(),driverLat,driverLng);
        }
        // Rendering is owned exclusively by the display callback.
        maybeRefreshRoute(raw);
    }

    /**
     * Display-paced interpolation follows the observed GPS cadence. Road tangent
     * is resolved at the rendered progress, independently of camera browsing.
     */
    protected void animateTowardLatestFix() {
        if(!valid(driverLat,driverLng)||map==null||!styleReady)return;
        long now=SystemClock.elapsedRealtime();
        boolean newFix=visualMatch==null||motionFixAt!=lastFixRealtimeMs||motionRevision!=routeVisualRevision;
        if(newFix)visualMatch=snapToRoute(driverLat,driverLng);
        SnapPoint base=visualMatch;
        if(lastFixRealtimeMs>0&&(motionFixAt!=lastFixRealtimeMs||motionRevision!=routeVisualRevision)){
            double heading=base.onRoute?base.bearing:currentBearing;
            vehicleMotion.offer(now,base.lat,base.lng,heading,base.progressMeters,currentSpeedKmh/3.6,lastGpsAccuracyM,base.onRoute,routeVisualRevision);
            motionFixAt=lastFixRealtimeMs;motionRevision=routeVisualRevision;
        }
        NavigationMotionInterpolator.Pose pose=vehicleMotion.sample(now);if(pose==null)return;
        double lat=pose.lat,lng=pose.lng,heading=pose.bearing;
        if(pose.onRoute){synchronized(routePoints){NavigationGeometry.Point point=NavigationGeometry.at(routePoints,routeCumulativeMeters,pose.progress);if(point!=null){lat=point.lat;lng=point.lng;visualRouteProgressMeters=pose.progress;visualRouteSegmentIndex=point.segment;heading=point.bearing;}}}
        displayLat=lat;displayLng=lng;displayInitialized=true;snappedBearing=renderedHeading.update(now,heading,currentSpeedKmh>=4.3||pose.velocity>=1.2);
        // One pose publication per display-paced callback. GPS callbacks never race the render loop.
        vehicleMotion.rendered(now,lat,lng);
        boolean changed=!Double.isFinite(lastPoseLat)||meters(lastPoseLat,lastPoseLng,lat,lng)>.005||Math.abs(NavigationMotionInterpolator.delta(lastPoseHeading,snappedBearing))>.2||Math.abs(lastPoseSpeed-currentSpeedKmh)>.5;
        if(changed){poseChangedAt=now;lastPoseLat=lat;lastPoseLng=lng;lastPoseHeading=snappedBearing;lastPoseSpeed=currentSpeedKmh;}
        if(changed||now-poseChangedAt<1500||now-lastMapRenderAt>=250){updateNativePosition(false);lastMapRenderAt=now;}
        if(newFix||now-lastNavigationUiAt>=100){
            lastNavigationUiAt=now;maybeUpdateRemainingRouteLine();
            // Instructions and arrival use accepted GPS progress, never visual delay.
            updateInstructionBanner(base.progressMeters);
        }
    }
}
