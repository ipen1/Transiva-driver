package com.transiva.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
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
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
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
import org.maplibre.android.style.sources.GeoJsonSource;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;

import static org.maplibre.android.style.layers.PropertyFactory.lineCap;
import static org.maplibre.android.style.layers.PropertyFactory.lineColor;
import static org.maplibre.android.style.layers.PropertyFactory.lineJoin;
import static org.maplibre.android.style.layers.PropertyFactory.lineOpacity;
import static org.maplibre.android.style.layers.PropertyFactory.lineWidth;
import static org.maplibre.android.style.layers.Property.LINE_CAP_ROUND;
import static org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND;

/**
 * Transiva Native Navigation.
 *
 * Fast path:
 * 1. No WebView / Leaflet / JavaScript.
 * 2. MapLibre Native renders with the Android native/GPU pipeline.
 * 3. OpenFreeMap provides the basemap without API keys.
 * 4. StableRouteEngine starts before the map style is ready; cached routes are
 *    normally available immediately after DriverTripActivity.
 * 5. Camera is heading-up: the road ahead stays at the top of the screen.
 */
public class DriverNavigationActivity extends Activity {
    private static final String MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty";
    private static final String LOCATION_API =
            "https://transiva.my.id/server/driver_update_location_native.php";

    private static final String ROUTE_SOURCE = "transiva-route-source";
    private static final String ROUTE_CASE_LAYER = "transiva-route-case";
    private static final String ROUTE_LAYER = "transiva-route-line";

    private static final long LOCATION_UPLOAD_MS = 2500L;
    private static final long ROUTE_REFRESH_MS = 15000L;
    private static final float ROUTE_REFRESH_DISTANCE_M = 35f;

    // Auto-reroute is deliberately confirmed over several fixes. This prevents a
    // single weak-GPS jump from replacing a route that is still correct.
    private static final double OFF_ROUTE_BASE_DISTANCE_M = 45d;
    private static final double OFF_ROUTE_HARD_DISTANCE_M = 120d;
    private static final double OFF_ROUTE_MIN_TRAVEL_M = 24d;
    private static final long OFF_ROUTE_CONFIRM_MS = 4200L;
    private static final long REROUTE_COOLDOWN_MS = 12000L;

    // Navigation map-matching stability. These values are intentionally conservative:
    // on dual-carriageway / parallel roads we prefer continuity over jumping to a
    // geometrically-nearer segment several dozen metres ahead.
    private static final double ROUTE_MATCH_BACKWARD_ALLOWANCE_M = 8d;
    private static final double ROUTE_MATCH_FORWARD_BASE_M = 42d;
    private static final double ROUTE_MATCH_MAX_DISTANCE_M = 65d;
    private static final double ROUTE_LINE_CUT_AHEAD_M = 4.5d;
    private static final double ROUTE_LINE_PROGRESS_STEP_M = 3.0d;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final SmoothLocationEngine smoothLocation = new SmoothLocationEngine(1800L);

    private MapView mapView;
    private MapLibreMap map;
    private Style style;
    private Marker driverMarker;
    private Marker pickupMarker;
    private Marker deliveryMarker;

    private TextView routeBadge;
    private TextView instructionBadge;
    private TextView speedBadge;

    private LocationManager locationManager;
    private LocationListener locationListener;
    private Location lastLocation;
    private Location lastRouteLocation;
    private Location lastSpeedLocation;

    private JSONObject order = new JSONObject();
    private SessionManager session;
    private String username = "";
    private String vehicleType = "motor";
    private String targetMode = "pickup";

    private double driverLat;
    private double driverLng;
    private double currentBearing;
    private double currentSpeedKmh;
    private double averageSpeedKmh;
    private double speedSum;
    private long speedSamples;
    private long lastUploadAt;
    private long lastRouteRequestAt;

    private boolean styleReady;
    private boolean routeInFlight;
    private boolean userAdjustedZoom = false;

    // Final navigation smoothing: frequent small interpolation steps instead of GPS-sized jumps.
    private static final long VISUAL_FRAME_MS = 16L;          // ~60 FPS
    private static final long POSITION_EASE_MS = 900L;
    private static final float POSITION_EASE_ALPHA = 0.16f;
    private static final float BEARING_EASE_ALPHA = 0.10f;
    private static final float CAMERA_BEARING_ALPHA = 0.075f;
    private double smoothCameraBearing = Double.NaN;
    private double smoothMarkerBearing = Double.NaN;
    private String pendingRouteGeoJson = "";
    private double pendingRouteKm;
    private double pendingRouteSeconds;
    private final List<double[]> routePoints = new ArrayList<>();
    private final List<Double> routeCumulativeMeters = new ArrayList<>();
    private JSONArray routeManeuvers = new JSONArray();
    private int routeProgressIndex = 0;
    private double snappedBearing = 0d;
    private long lastRouteSuccessAt = 0L;
    private int routeFailureCount = 0;

    // Weak-GPS and sustained route-deviation state.
    private float lastGpsAccuracyM = 50f;
    private int offRouteFixCount = 0;
    private long offRouteStartedAt = 0L;
    private double offRouteStartLat = Double.NaN;
    private double offRouteStartLng = Double.NaN;
    private long lastAutoRerouteAt = 0L;

    // Continuous route progress (metres), not only a segment index. This is the
    // key to preventing lane/parallel-road hopping and route-line leftovers.
    private double lastMatchedProgressMeters = Double.NaN;
    private int lastMatchedSegmentIndex = 0;
    private double lastMatchedSegmentT = 0d;
    private double lastMatchedLat = Double.NaN;
    private double lastMatchedLng = Double.NaN;
    private double lastMatchedRawLat = Double.NaN;
    private double lastMatchedRawLng = Double.NaN;
    private long lastMatchRealtimeMs = 0L;
    private double visualRouteProgressMeters = Double.NaN;
    private int visualRouteSegmentIndex = 0;
    private double lastRenderedRouteProgressMeters = Double.NaN;

    // Route loading progress. OSRM itself does not expose byte-level route progress,
    // so this is a staged UI progress that advances while connect/compute/parse/draw run.
    private int routeLoadingPercent = 0;
    private long routeLoadingStartedAt = 0L;

    private final Runnable routeLoadingTick = new Runnable() {
        @Override public void run() {
            if (!routeInFlight || isFinishing()) return;
            long elapsed = Math.max(0L, SystemClock.elapsedRealtime() - routeLoadingStartedAt);
            int target;
            if (elapsed < 500L) target = 18;
            else if (elapsed < 1200L) target = 38;
            else if (elapsed < 2500L) target = 58;
            else if (elapsed < 4500L) target = 74;
            else target = 88;

            if (routeLoadingPercent < target) routeLoadingPercent++;
            showRouteLoading(routeLoadingPercent);
            main.postDelayed(this, 90L);
        }
    };

    private final Runnable routeRetryTick = new Runnable() {
        @Override public void run() {
            if (!isFinishing()) {
                if (routePoints.size() < 2) {
                    requestRoute(true);
                }
                main.postDelayed(this, routePoints.size() < 2 ? 2200L : 15000L);
            }
        }
    };

    private final Runnable animationTick = new Runnable() {
        @Override public void run() {
            animateTowardLatestFix();
            main.postDelayed(this, VISUAL_FRAME_MS);
        }
    };

    private double displayLat;
    private double displayLng;
    private boolean displayInitialized;
    private long lastFixRealtimeMs = 0L;
    private long lastInstructionUiAt = 0L;
    private int lastRenderedRouteIndex = -1;
    private long lastRouteLineUpdateAt = 0L;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            if (getActionBar() != null) getActionBar().hide();
            getWindow().setStatusBarColor(Color.parseColor("#0B3A78"));
            getWindow().setNavigationBarColor(Color.BLACK);
        } catch (Exception ignored) {}

        session = new SessionManager(this);
        readOrder();
        readIdentity();
        seedLastKnownLocation();

        // Initialise MapLibre before MapView.onCreate().
        MapLibre.getInstance(getApplicationContext());

        buildUi(savedInstanceState);

        // Start route calculation immediately; it runs in parallel with native map/style loading.
        requestRoute(true);
        startLocationWatch();
        main.post(animationTick);
        main.postDelayed(routeRetryTick, 2200L);
    }

    private void readOrder() {
        try {
            String raw = getIntent().getStringExtra("order_json");
            if (raw != null && raw.trim().startsWith("{")) order = new JSONObject(raw);
        } catch (Exception ignored) {}
        try {
            String mode = getIntent().getStringExtra("target_mode");
            if (mode != null && mode.toLowerCase(Locale.US).contains("delivery")) targetMode = "delivery";
            else targetMode = routeTargetMode();
        } catch (Exception ignored) {}

        driverLat = getIntent().getDoubleExtra("driver_lat", 0d);
        driverLng = getIntent().getDoubleExtra("driver_lng", 0d);
    }

    private void readIdentity() {
        try {
            username = first(session.getUsername(), session.getName(),
                    getSharedPreferences("transiva", MODE_PRIVATE).getString("username", ""));
            vehicleType = normalizeVehicle(first(session.getDriverType(),
                    getSharedPreferences("transiva", MODE_PRIVATE).getString("driver_type", "motor")));
        } catch (Exception ignored) {}
    }

    private void seedLastKnownLocation() {
        if (valid(driverLat, driverLng)) return;
        if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            Location gps = null, net = null;
            try { if (lm != null) gps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER); } catch (Exception ignored) {}
            try { if (lm != null) net = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER); } catch (Exception ignored) {}
            Location best = gps != null ? gps : net;
            if (gps != null && net != null && net.getTime() > gps.getTime()) best = net;
            if (best != null && valid(best.getLatitude(), best.getLongitude())) {
                driverLat = best.getLatitude();
                driverLng = best.getLongitude();
                lastLocation = new Location(best);
            }
        } catch (Exception ignored) {}
    }

    private void buildUi(Bundle state) {
        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(Color.parseColor("#EAF4FF"));

        MapLibreMapOptions options = MapLibreMapOptions.createFromAttributes(this)
                .compassEnabled(false)
                .attributionEnabled(false)
                .logoEnabled(false)
                .rotateGesturesEnabled(false)
                .tiltGesturesEnabled(false)
                .scrollGesturesEnabled(false)
                .zoomGesturesEnabled(true);

        mapView = new MapView(this, options);
        page.addView(mapView, new FrameLayout.LayoutParams(-1, -1));
        mapView.onCreate(state);
        mapView.getMapAsync(m -> {
            map = m;
            map.getUiSettings().setCompassEnabled(false);
            map.getUiSettings().setRotateGesturesEnabled(false);
            map.getUiSettings().setTiltGesturesEnabled(false);
            map.getUiSettings().setZoomGesturesEnabled(true);
            map.getUiSettings().setAttributionEnabled(false);
            map.getUiSettings().setLogoEnabled(false);
            map.setStyle(new Style.Builder().fromUri(MAP_STYLE), s -> {
                style = s;
                styleReady = true;
                installRouteLayers();
                installMarkers();
                drawPendingRoute();
                updateNativePosition(true);
            });
        });

        // FIX: kendaraan tidak lagi berupa ImageView Gravity.CENTER.
        // Marker kendaraan sekarang terikat ke LatLng di peta, sehingga saat pinch zoom
        // kendaraan tetap berada pada koordinat/rute dan tidak mengikuti titik tengah HP.

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(38);
        back.setTextColor(Color.parseColor("#0B3A78"));
        back.setGravity(Gravity.CENTER);
        back.setBackground(roundRect(Color.parseColor("#FCFFFFFF"), 20));
        back.setElevation(dp(8));
        back.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams backLp = new FrameLayout.LayoutParams(dp(54), dp(54));
        backLp.leftMargin = dp(16);
        backLp.topMargin = dp(18);
        page.addView(back, backLp);

        routeBadge = new TextView(this);
        routeBadge.setText(targetMode.equals("delivery") ? "Menyiapkan rute ke tujuan…" : "Menyiapkan rute ke pickup…");
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

        speedBadge = new TextView(this);
        speedBadge.setText("0 km/j\nRata-rata 0 km/j");
        speedBadge.setTextColor(Color.WHITE);
        speedBadge.setTextSize(16);
        speedBadge.setPadding(dp(16), dp(10), dp(16), dp(10));
        speedBadge.setBackground(roundRect(Color.parseColor("#E6071426"), 22));
        FrameLayout.LayoutParams speedLp = new FrameLayout.LayoutParams(-2, -2);
        speedLp.leftMargin = dp(18);
        speedLp.bottomMargin = dp(28);
        speedLp.gravity = Gravity.BOTTOM | Gravity.LEFT;
        page.addView(speedBadge, speedLp);

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

    private android.graphics.drawable.GradientDrawable roundRect(int color, int radiusDp) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private void installRouteLayers() {
        if (style == null) return;
        try {
            if (style.getSource(ROUTE_SOURCE) == null) {
                style.addSource(new GeoJsonSource(ROUTE_SOURCE, emptyFeatureCollection()));
            }
            if (style.getLayer(ROUTE_CASE_LAYER) == null) {
                style.addLayer(new LineLayer(ROUTE_CASE_LAYER, ROUTE_SOURCE).withProperties(
                        lineColor(Color.parseColor("#174A7E")),
                        lineOpacity(0.32f),
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
        } catch (Exception ignored) {}
    }

    private void installMarkers() {
        if (map == null) return;
        IconFactory f = IconFactory.getInstance(this);
        try {
            double pLat = coord("pickup_lat", "user_lat");
            double pLng = coord("pickup_lng", "user_lng");
            if (valid(pLat, pLng) && pickupMarker == null) {
                Icon icon = iconFromDrawableScaled(f, "map_pickup_pin",
                        android.R.drawable.ic_menu_mylocation, dp(34), dp(34));
                pickupMarker = map.addMarker(new MarkerOptions().position(new LatLng(pLat, pLng)).icon(icon));
            }
        } catch (Exception ignored) {}

        try {
            double dLat = coord("delivery_lat", "destination_lat");
            double dLng = coord("delivery_lng", "destination_lng");
            if (valid(dLat, dLng) && deliveryMarker == null) {
                Icon icon = iconFromDrawableScaled(f, "map_destination_pin",
                        android.R.drawable.ic_menu_mylocation, dp(34), dp(34));
                deliveryMarker = map.addMarker(new MarkerOptions().position(new LatLng(dLat, dLng)).icon(icon));
            }
        } catch (Exception ignored) {}

        // FIX ZOOM: marker driver adalah objek peta (LatLng), bukan overlay layar.
        if (driverMarker == null) {
            double lat = displayInitialized ? displayLat : driverLat;
            double lng = displayInitialized ? displayLng : driverLng;
            if (valid(lat, lng)) {
                String drawable = vehicleType.equals("car") ? "map_car_top" : "map_motor_top";
                Icon icon = iconFromDrawableScaled(f, drawable,
                        android.R.drawable.ic_menu_directions, dp(48), dp(48));
                driverMarker = map.addMarker(new MarkerOptions()
                        .position(new LatLng(lat, lng))
                        .icon(icon));
            }
        }
    }

    private Icon iconFromDrawable(IconFactory factory, String name, int fallback) {
        int id = getResources().getIdentifier(name, "drawable", getPackageName());
        if (id <= 0) id = fallback;
        Bitmap b = BitmapFactory.decodeResource(getResources(), id);
        return factory.fromBitmap(b);
    }

    private Icon iconFromDrawableScaled(IconFactory factory, String name, int fallback,
                                        int widthPx, int heightPx) {
        int id = getResources().getIdentifier(name, "drawable", getPackageName());
        if (id <= 0) id = fallback;
        Bitmap raw = BitmapFactory.decodeResource(getResources(), id);
        if (raw == null) return factory.defaultMarker();
        Bitmap scaled = Bitmap.createScaledBitmap(raw,
                Math.max(1, widthPx), Math.max(1, heightPx), true);
        return factory.fromBitmap(scaled);
    }

    private void startLocationWatch() {
        if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, 801);
            return;
        }
        try {
            locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (locationManager == null) return;
            stopLocationWatch();

            locationListener = new LocationListener() {
                @Override public void onLocationChanged(Location raw) {
                    SmoothLocationEngine.Fix fix = smoothLocation.offer(raw);
                    if (fix == null) return;
                    Location l = fix.location;
                    lastLocation = new Location(l);
                    driverLat = l.getLatitude();
                    driverLng = l.getLongitude();
                    lastFixRealtimeMs = SystemClock.elapsedRealtime();
                    lastGpsAccuracyM = l.hasAccuracy() ? Math.max(1f, l.getAccuracy()) : 50f;
                    updateSpeed(l);

                    if (l.hasBearing() && l.getSpeed() > 1.2f) currentBearing = l.getBearing();
                    else if (displayInitialized) currentBearing = bearing(displayLat, displayLng, driverLat, driverLng);

                    if (fix.upload) uploadLocation(l);
                    if (fix.render) updateNativePosition(false);
                    maybeRefreshRoute(l);
                }
                @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
                @Override public void onProviderEnabled(String provider) {}
                @Override public void onProviderDisabled(String provider) {}
            };

            try {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                        700L, 0f, locationListener, Looper.getMainLooper());
            } catch (Exception ignored) {}
            try {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                        1300L, 0f, locationListener, Looper.getMainLooper());
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    private void stopLocationWatch() {
        try {
            if (locationManager != null && locationListener != null) {
                locationManager.removeUpdates(locationListener);
            }
        } catch (Exception ignored) {}
        locationListener = null;
    }

    /**
     * Native continuous interpolation. The latest GPS fix becomes a moving
     * target; the display advances a fraction every 50 ms instead of stopping
     * between 1-second fixes.
     */
    private void animateTowardLatestFix() {
        if (!valid(driverLat, driverLng) || map == null || !styleReady) return;

        SnapPoint base = snapToRoute(driverLat, driverLng);
        SnapPoint target = base;

        // Dead-reckoning between GPS fixes: the visual target keeps moving at the
        // measured speed for a short bounded window, so the icon/map does not
        // move-stop-move between 700-1300 ms location samples.
        if (base.onRoute && currentSpeedKmh > 1d && lastFixRealtimeMs > 0L) {
            // With a weak signal, continue gently along the already-matched route
            // for a little longer instead of allowing the marker/camera to jump.
            double maxPredictionSec = lastGpsAccuracyM >= 55f ? 3.2d
                    : (lastGpsAccuracyM >= 30f ? 2.2d : 1.15d);
            double ageSec = Math.max(0d, Math.min(maxPredictionSec,
                    (SystemClock.elapsedRealtime() - lastFixRealtimeMs) / 1000d));
            double lookAheadMeters = Math.min(35d, (currentSpeedKmh / 3.6d) * ageSec);
            target = advanceAlongRoute(base, lookAheadMeters);
        }

        if (target.onRoute) {
            visualRouteProgressMeters = target.progressMeters;
            visualRouteSegmentIndex = target.segmentIndex;
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
            float alpha = (float) (0.045d + 0.105d * speedFactor);
            if (distance > 25f) alpha = Math.max(alpha, 0.20f);
            displayLat = easePosition(displayLat, target.lat, alpha);
            displayLng = easePosition(displayLng, target.lng, alpha);
            updateNativePosition(false);
        }

        maybeUpdateRemainingRouteLine();
        updateInstructionBanner(target.progressMeters);
    }

    private double easeBearing(double current, double target, float alpha) {
        target = ((target % 360d) + 360d) % 360d;
        if (Double.isNaN(current)) return target;
        current = ((current % 360d) + 360d) % 360d;
        double delta = ((target - current + 540d) % 360d) - 180d;
        return (current + delta * alpha + 360d) % 360d;
    }

    private double easePosition(double current, double target, float alpha) {
        return current + (target - current) * alpha;
    }

    private void updateNativePosition(boolean immediate) {
        if (map == null || !styleReady) return;
        installMarkers();

        double lat = displayInitialized ? displayLat : driverLat;
        double lng = displayInitialized ? displayLng : driverLng;
        if (!valid(lat, lng)) return;

        // FIX ZOOM: koordinat kendaraan selalu diperbarui sebagai LatLng marker.
        // Jadi pinch zoom hanya mengubah proyeksi kamera; marker tetap menempel ke rute.
        if (driverMarker != null) {
            driverMarker.setPosition(new LatLng(lat, lng));
        }

        double desiredBearing = routePoints.size() >= 2 && Double.isFinite(snappedBearing)
                ? snappedBearing
                : (Double.isFinite(currentBearing) ? currentBearing : 0d);
        smoothCameraBearing = easeBearing(smoothCameraBearing, desiredBearing,
                immediate ? 1.0f : 0.065f);
        double cameraBearing = smoothCameraBearing;
        double zoom = 18.4d;
        try {
            if (map != null && map.getCameraPosition() != null && map.getCameraPosition().zoom > 2d) {
                zoom = map.getCameraPosition().zoom;
            }
        } catch (Exception ignored) {}

        CameraPosition cp = new CameraPosition.Builder()
                .target(new LatLng(lat, lng))
                .zoom(zoom)
                .bearing(cameraBearing)
                .tilt(42d)
                .build();

        // Do NOT start a new easeCamera animation every frame. Repeatedly cancelling
        // animations is the main cause of "maju-berhenti-maju". Position/bearing are
        // already smoothed above, so direct native camera updates are continuous.
        map.moveCamera(CameraUpdateFactory.newCameraPosition(cp));

        speedBadge.setText(String.format(Locale.US, "%.0f km/j\nRata-rata %.0f km/j",
                currentSpeedKmh, averageSpeedKmh));
    }

    private void updateSpeed(Location l) {
        double instant = 0d;
        if (l.hasSpeed() && l.getSpeed() >= 0f) {
            instant = l.getSpeed() * 3.6d;
        } else if (lastSpeedLocation != null) {
            long dt = l.getTime() - lastSpeedLocation.getTime();
            if (dt > 250L && dt < 10000L) {
                instant = lastSpeedLocation.distanceTo(l) / (dt / 1000d) * 3.6d;
            }
        }
        if (!Double.isFinite(instant) || instant < 0d) instant = 0d;
        if (instant > 180d) instant = 180d;
        currentSpeedKmh = currentSpeedKmh <= 0 ? instant :
                currentSpeedKmh * 0.68d + instant * 0.32d;
        if (currentSpeedKmh >= 1d) {
            speedSum += currentSpeedKmh;
            speedSamples++;
            averageSpeedKmh = speedSum / Math.max(1L, speedSamples);
        }
        lastSpeedLocation = new Location(l);
    }

    private void requestRoute(boolean force) {
        if (!valid(driverLat, driverLng) || routeInFlight) return;

        final double toLat = targetLat();
        final double toLng = targetLng();
        if (!valid(toLat, toLng)) return;

        long now = System.currentTimeMillis();
        if (!force && lastRouteLocation != null &&
                meters(lastRouteLocation.getLatitude(), lastRouteLocation.getLongitude(),
                        driverLat, driverLng) < ROUTE_REFRESH_DISTANCE_M &&
                now - lastRouteRequestAt < ROUTE_REFRESH_MS) {
            return;
        }

        routeInFlight = true;
        lastRouteRequestAt = now;
        routeLoadingPercent = 4;
        routeLoadingStartedAt = SystemClock.elapsedRealtime();
        showRouteLoading(routeLoadingPercent);
        main.removeCallbacks(routeLoadingTick);
        main.post(routeLoadingTick);

        final double fromLat = driverLat, fromLng = driverLng;

        new Thread(() -> {
            try {
                StableRouteEngine.Result r = StableRouteEngine.fetch(fromLat, fromLng, toLat, toLng);
                main.post(() -> {
                    routeLoadingPercent = Math.max(routeLoadingPercent, 72);
                    showRouteLoading(routeLoadingPercent);
                });

                pendingRouteGeoJson = routeGeoJson(r.pointsJson());
                setRoutePoints(r.latLngPoints);
                lastRenderedRouteIndex = -1;
                lastRenderedRouteProgressMeters = Double.NaN;
                routeManeuvers = r.maneuvers == null ? new JSONArray() : r.maneuvers;
                pendingRouteKm = r.distanceMeters / 1000d;
                pendingRouteSeconds = r.durationSeconds;
                lastRouteSuccessAt = System.currentTimeMillis();
                routeFailureCount = 0;

                Location rl = new Location("route");
                rl.setLatitude(fromLat);
                rl.setLongitude(fromLng);
                lastRouteLocation = rl;

                main.post(() -> {
                    routeLoadingPercent = 90;
                    showRouteLoading(routeLoadingPercent);

                    // Match first, then cut the route line from the matched progress.
                    // This avoids drawing the old segment behind/under the vehicle.
                    if (valid(driverLat, driverLng)) {
                        SnapPoint match = snapToRoute(driverLat, driverLng);
                        displayLat = match.lat;
                        displayLng = match.lng;
                        displayInitialized = true;
                        if (match.onRoute) snappedBearing = match.bearing;
                        updateNativePosition(true);
                    }
                    updateRemainingRouteLine(true);

                    routeLoadingPercent = 100;
                    showRouteLoading(100);
                    final int mins = Math.max(1, (int) Math.ceil(pendingRouteSeconds / 60d));
                    main.postDelayed(() -> {
                        if (!isFinishing() && routePoints.size() >= 2) {
                            routeBadge.setText(String.format(Locale.US, "%s • %.1f km • %d menit",
                                    targetMode.equals("delivery") ? "Menuju tujuan" : "Menuju pickup",
                                    pendingRouteKm, mins));
                        }
                    }, 260L);
                });
            } catch (Exception ignored) {
                routeFailureCount++;
                main.post(() -> {
                    if (routePoints.size() < 2) {
                        routeBadge.setText(routeFailureCount <= 1
                                ? "OSRM belum merespons • mencoba kembali…"
                                : "Rute belum tersedia • mencoba kembali…");
                    }
                });
            } finally {
                routeInFlight = false;
                main.post(() -> main.removeCallbacks(routeLoadingTick));
            }
        }, "transiva-native-route").start();
    }

    private void showRouteLoading(int percent) {
        if (routeBadge == null) return;
        int p = Math.max(0, Math.min(100, percent));
        String target = targetMode.equals("delivery") ? "tujuan" : "pickup";
        routeBadge.setText(String.format(Locale.US, "Membuat rute ke %s • %d%%", target, p));
    }

    private void maybeRefreshRoute(Location fix) {
        if (routePoints.size() < 2) {
            resetOffRouteConfirmation();
            requestRoute(false);
            return;
        }

        final long now = SystemClock.elapsedRealtime();
        final float accuracy = fix != null && fix.hasAccuracy()
                ? Math.max(1f, fix.getAccuracy()) : lastGpsAccuracyM;
        final double deviation = nearestRouteDistanceMeters(driverLat, driverLng);
        final double trigger = Math.max(OFF_ROUTE_BASE_DISTANCE_M, accuracy * 1.35d);

        // A highly inaccurate fix is not enough evidence by itself. Only an
        // unmistakably large deviation may start confirmation while GPS is weak.
        if (!Double.isFinite(deviation) ||
                (accuracy > 85f && deviation < OFF_ROUTE_HARD_DISTANCE_M + 30d) ||
                deviation < trigger) {
            resetOffRouteConfirmation();
            requestRoute(false);
            return;
        }

        if (offRouteStartedAt == 0L) {
            offRouteStartedAt = now;
            offRouteStartLat = driverLat;
            offRouteStartLng = driverLng;
            offRouteFixCount = 1;
            return;
        }

        offRouteFixCount++;
        double traveled = valid(offRouteStartLat, offRouteStartLng)
                ? meters(offRouteStartLat, offRouteStartLng, driverLat, driverLng) : 0d;
        long duration = now - offRouteStartedAt;

        int requiredFixes = accuracy >= 55f ? 5 : (accuracy >= 30f ? 4 : 3);
        long requiredMs = accuracy >= 55f ? 8000L : OFF_ROUTE_CONFIRM_MS;
        double requiredTravel = accuracy >= 55f ? 35d : OFF_ROUTE_MIN_TRAVEL_M;
        boolean hardDeviation = deviation >= OFF_ROUTE_HARD_DISTANCE_M && offRouteFixCount >= 2;
        boolean confirmed = offRouteFixCount >= requiredFixes &&
                duration >= requiredMs && traveled >= requiredTravel;

        if ((hardDeviation || confirmed) && now - lastAutoRerouteAt >= REROUTE_COOLDOWN_MS) {
            lastAutoRerouteAt = now;
            resetOffRouteConfirmation();
            if (routeBadge != null) routeBadge.setText("Mendeteksi pindah jalur • membuat rute baru…");
            requestRoute(true);
            return;
        }

        // Keep the stable current route while confirmation is in progress.
        requestRoute(false);
    }

    private void resetOffRouteConfirmation() {
        offRouteFixCount = 0;
        offRouteStartedAt = 0L;
        offRouteStartLat = Double.NaN;
        offRouteStartLng = Double.NaN;
    }

    /** Returns raw GPS distance to the nearby route corridor without changing map-match state. */
    private double nearestRouteDistanceMeters(double lat, double lng) {
        synchronized (routePoints) {
            if (routePoints.size() < 2 || !valid(lat, lng)) return Double.POSITIVE_INFINITY;
            int start = Math.max(0, lastMatchedSegmentIndex - 20);
            int end = Math.min(routePoints.size() - 2, lastMatchedSegmentIndex + 90);
            if (Double.isNaN(lastMatchedProgressMeters)) { start = 0; end = routePoints.size() - 2; }

            double latScale = 111320d;
            double lngScale = 111320d * Math.max(0.15d, Math.cos(Math.toRadians(lat)));
            double px = lng * lngScale, py = lat * latScale;
            double best = Double.POSITIVE_INFINITY;
            for (int i = start; i <= end; i++) {
                double[] a = routePoints.get(i), b = routePoints.get(i + 1);
                double ax = a[1] * lngScale, ay = a[0] * latScale;
                double bx = b[1] * lngScale, by = b[0] * latScale;
                double vx = bx - ax, vy = by - ay;
                double len2 = vx * vx + vy * vy;
                double t = len2 <= 0.000001d ? 0d : ((px - ax) * vx + (py - ay) * vy) / len2;
                t = Math.max(0d, Math.min(1d, t));
                double dx = px - (ax + vx * t), dy = py - (ay + vy * t);
                best = Math.min(best, Math.sqrt(dx * dx + dy * dy));
            }
            return best;
        }
    }


    /**
     * Keep only the untraveled section visible.
     * The already-passed route is removed from the GeoJSON source as routeProgressIndex advances.
     */
    /**
     * Route source updates are intentionally throttled.
     * Rebuilding GeoJSON every 16 ms caused the blue route to blink on some GPUs.
     * We only cut the traveled route when the matched segment changes.
     */
    private void maybeUpdateRemainingRouteLine() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastRouteLineUpdateAt < 220L) return;

        double lineProgress = Double.isNaN(visualRouteProgressMeters)
                ? lastMatchedProgressMeters : visualRouteProgressMeters;
        int lineSegment = Double.isNaN(visualRouteProgressMeters)
                ? lastMatchedSegmentIndex : visualRouteSegmentIndex;
        boolean segmentChanged = lineSegment != lastRenderedRouteIndex;
        boolean progressed = Double.isNaN(lastRenderedRouteProgressMeters) ||
                (!Double.isNaN(lineProgress) &&
                        lineProgress - lastRenderedRouteProgressMeters >= ROUTE_LINE_PROGRESS_STEP_M);
        if (!segmentChanged && !progressed) return;
        updateRemainingRouteLine(false);
    }

    private void updateRemainingRouteLine(boolean force) {
        if (!styleReady || style == null) return;
        if (!force) {
            double lineProgress = Double.isNaN(visualRouteProgressMeters)
                    ? lastMatchedProgressMeters : visualRouteProgressMeters;
            int lineSegment = Double.isNaN(visualRouteProgressMeters)
                    ? lastMatchedSegmentIndex : visualRouteSegmentIndex;
            boolean segmentChanged = lineSegment != lastRenderedRouteIndex;
            boolean progressed = Double.isNaN(lastRenderedRouteProgressMeters) ||
                    (!Double.isNaN(lineProgress) &&
                            lineProgress - lastRenderedRouteProgressMeters >= ROUTE_LINE_PROGRESS_STEP_M);
            if (!segmentChanged && !progressed) return;
        }

        String geo = remainingRouteGeoJson();
        if (geo == null || geo.isEmpty()) return;
        try {
            GeoJsonSource source = style.getSourceAs(ROUTE_SOURCE);
            if (source != null) {
                source.setGeoJson(geo);
                lastRenderedRouteIndex = Double.isNaN(visualRouteProgressMeters)
                        ? lastMatchedSegmentIndex : visualRouteSegmentIndex;
                lastRenderedRouteProgressMeters = Double.isNaN(visualRouteProgressMeters)
                        ? lastMatchedProgressMeters : visualRouteProgressMeters;
                lastRouteLineUpdateAt = SystemClock.elapsedRealtime();
            }
        } catch (Exception ignored) {}
    }

    /**
     * Build only the route still in front of the vehicle. The first coordinate
     * is interpolated from continuous matched progress (+ a tiny visual cut),
     * not from the beginning of the current segment. Therefore a blue tail can
     * no longer remain behind or directly underneath the vehicle icon.
     */
    private String remainingRouteGeoJson() {
        synchronized (routePoints) {
            if (routePoints.size() < 2) return pendingRouteGeoJson;
            try {
                double progress = !Double.isNaN(visualRouteProgressMeters)
                        ? visualRouteProgressMeters
                        : (Double.isNaN(lastMatchedProgressMeters)
                            ? routeProgressMeters(Math.max(0, routeProgressIndex))
                            : lastMatchedProgressMeters);
                double total = routeProgressMeters(routePoints.size() - 1);
                double lineStartProgress = Math.max(0d, Math.min(total, progress + ROUTE_LINE_CUT_AHEAD_M));

                int seg = segmentForProgressLocked(lineStartProgress);
                double[] a = routePoints.get(seg);
                double[] b = routePoints.get(Math.min(seg + 1, routePoints.size() - 1));
                double segStart = routeCumulativeMeters.get(seg);
                double segMeters = Math.max(0.01d, meters(a[0], a[1], b[0], b[1]));
                double t = Math.max(0d, Math.min(1d, (lineStartProgress - segStart) / segMeters));
                double startLat = a[0] + (b[0] - a[0]) * t;
                double startLng = a[1] + (b[1] - a[1]) * t;

                JSONArray coords = new JSONArray();
                JSONArray first = new JSONArray();
                first.put(startLng);
                first.put(startLat);
                coords.put(first);

                for (int i = seg + 1; i < routePoints.size(); i++) {
                    double[] rp = routePoints.get(i);
                    JSONArray c = new JSONArray();
                    c.put(rp[1]);
                    c.put(rp[0]);
                    coords.put(c);
                }

                if (coords.length() < 2) return emptyFeatureCollection();

                JSONObject geometry = new JSONObject();
                geometry.put("type", "LineString");
                geometry.put("coordinates", coords);
                JSONObject feature = new JSONObject();
                feature.put("type", "Feature");
                feature.put("properties", new JSONObject());
                feature.put("geometry", geometry);
                return feature.toString();
            } catch (Exception e) {
                return pendingRouteGeoJson;
            }
        }
    }

    private int segmentForProgressLocked(double progressMeters) {
        if (routePoints.size() < 2 || routeCumulativeMeters.isEmpty()) return 0;
        int lo = 0, hi = routePoints.size() - 2;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            double start = routeCumulativeMeters.get(mid);
            double end = mid + 1 < routeCumulativeMeters.size()
                    ? routeCumulativeMeters.get(mid + 1) : start;
            if (progressMeters < start) hi = mid - 1;
            else if (progressMeters > end) lo = mid + 1;
            else return mid;
        }
        return Math.max(0, Math.min(routePoints.size() - 2, lo));
    }

    private void drawPendingRoute() {
        if (!styleReady || style == null || pendingRouteGeoJson.isEmpty()) return;
        updateRemainingRouteLine(true);
    }


    private void setRoutePoints(JSONArray points) {
        synchronized (routePoints) {
            routePoints.clear();
            routeCumulativeMeters.clear();
            routeProgressIndex = 0;
            lastMatchedProgressMeters = Double.NaN;
            lastMatchedSegmentIndex = 0;
            lastMatchedSegmentT = 0d;
            lastMatchedLat = Double.NaN;
            lastMatchedLng = Double.NaN;
            lastMatchedRawLat = Double.NaN;
            lastMatchedRawLng = Double.NaN;
            lastMatchRealtimeMs = 0L;
            visualRouteProgressMeters = Double.NaN;
            visualRouteSegmentIndex = 0;
            lastRenderedRouteProgressMeters = Double.NaN;
            double cumulative = 0d;
            if (points == null) return;

            double prevLat = 0d, prevLng = 0d;
            boolean havePrev = false;
            for (int i = 0; i < points.length(); i++) {
                JSONArray p = points.optJSONArray(i);
                if (p == null || p.length() < 2) continue;
                double lat = p.optDouble(0, Double.NaN);
                double lng = p.optDouble(1, Double.NaN);
                if (!valid(lat, lng)) continue;

                if (havePrev) cumulative += meters(prevLat, prevLng, lat, lng);
                routePoints.add(new double[]{lat, lng});
                routeCumulativeMeters.add(cumulative);
                prevLat = lat;
                prevLng = lng;
                havePrev = true;
            }
        }
    }

    /**
     * Lightweight route map-matching.
     * Projects the raw GPS fix onto the nearest route segment and keeps progress
     * mostly forward so GPS noise cannot pull the vehicle back to an old segment.
     */
    private SnapPoint snapToRoute(double lat, double lng) {
        synchronized (routePoints) {
            if (routePoints.size() < 2 || !valid(lat, lng)) {
                return new SnapPoint(lat, lng, currentBearing, false, routeProgressIndex, 0d,
                        routeProgressMeters(routeProgressIndex));
            }

            // animateTowardLatestFix() runs at ~60 FPS, while GPS is much slower.
            // Re-use the same route match for an unchanged raw fix; otherwise the
            // continuity limiter could be applied dozens of times to one GPS sample.
            if (!Double.isNaN(lastMatchedRawLat) && !Double.isNaN(lastMatchedRawLng) &&
                    !Double.isNaN(lastMatchedProgressMeters) &&
                    meters(lat, lng, lastMatchedRawLat, lastMatchedRawLng) < 0.20f) {
                return new SnapPoint(lastMatchedLat, lastMatchedLng, snappedBearing, true,
                        lastMatchedSegmentIndex, lastMatchedSegmentT, lastMatchedProgressMeters);
            }

            final long nowRt = SystemClock.elapsedRealtime();
            final double previousProgress = lastMatchedProgressMeters;
            final double speedMps = Math.max(0d, currentSpeedKmh / 3.6d);
            final double dtSec = lastMatchRealtimeMs <= 0L ? 1d
                    : Math.max(0.25d, Math.min(8d, (nowRt - lastMatchRealtimeMs) / 1000d));
            final double maxForward = Math.max(ROUTE_MATCH_FORWARD_BASE_M, speedMps * dtSec * 2.2d + 22d);

            int startIndex;
            int endIndex;
            if (Double.isNaN(previousProgress)) {
                startIndex = 0;
                endIndex = Math.min(routePoints.size() - 2, 220);
            } else {
                startIndex = Math.max(0, segmentForProgressLocked(
                        Math.max(0d, previousProgress - ROUTE_MATCH_BACKWARD_ALLOWANCE_M - 18d)) - 2);
                endIndex = Math.min(routePoints.size() - 2, segmentForProgressLocked(
                        previousProgress + maxForward + 55d) + 3);
            }

            double latScale = 111320d;
            double lngScale = 111320d * Math.max(0.15d, Math.cos(Math.toRadians(lat)));
            double px = lng * lngScale;
            double py = lat * latScale;

            double bestScore = Double.MAX_VALUE;
            double bestDistance = Double.MAX_VALUE;
            double bestLat = lat, bestLng = lng, bestBearing = currentBearing;
            int bestIndex = Math.max(0, Math.min(routeProgressIndex, routePoints.size() - 2));
            double bestT = 0d;
            double bestProgress = Double.isNaN(previousProgress) ? 0d : previousProgress;

            boolean useHeading = Double.isFinite(currentBearing) && currentSpeedKmh >= 4d;

            for (int i = startIndex; i <= endIndex; i++) {
                double[] a = routePoints.get(i);
                double[] b = routePoints.get(i + 1);
                double ax = a[1] * lngScale, ay = a[0] * latScale;
                double bx = b[1] * lngScale, by = b[0] * latScale;
                double vx = bx - ax, vy = by - ay;
                double len2 = vx * vx + vy * vy;
                double t = len2 <= 0.000001d ? 0d : ((px - ax) * vx + (py - ay) * vy) / len2;
                t = Math.max(0d, Math.min(1d, t));

                double qx = ax + vx * t, qy = ay + vy * t;
                double dx = px - qx, dy = py - qy;
                double distance = Math.sqrt(dx * dx + dy * dy);
                double segMeters = Math.max(0.01d, meters(a[0], a[1], b[0], b[1]));
                double progress = routeCumulativeMeters.get(i) + segMeters * t;
                double segBearing = bearing(a[0], a[1], b[0], b[1]);

                double score = distance;

                // Heading strongly separates opposite carriageways / parallel return legs.
                if (useHeading) {
                    double hd = bearingDelta(currentBearing, segBearing);
                    if (hd > 110d) score += 120d;
                    else if (hd > 70d) score += 45d;
                    else score += hd * 0.10d;
                }

                if (!Double.isNaN(previousProgress)) {
                    double delta = progress - previousProgress;
                    if (delta < -ROUTE_MATCH_BACKWARD_ALLOWANCE_M) {
                        score += 150d + Math.abs(delta) * 2.5d;
                    } else if (delta < 0d) {
                        score += Math.abs(delta) * 2.0d;
                    }
                    if (delta > maxForward) {
                        score += (delta - maxForward) * 3.0d;
                    }

                    // Small continuity preference keeps the vehicle on the same
                    // matched corridor when two lane centre-lines are equally close.
                    score += Math.abs(i - lastMatchedSegmentIndex) * 0.035d;
                }

                if (score < bestScore) {
                    bestScore = score;
                    bestDistance = distance;
                    bestLng = qx / lngScale;
                    bestLat = qy / latScale;
                    bestBearing = segBearing;
                    bestIndex = i;
                    bestT = t;
                    bestProgress = progress;
                }
            }

            if (bestDistance > ROUTE_MATCH_MAX_DISTANCE_M) {
                return new SnapPoint(lat, lng, currentBearing, false, routeProgressIndex, 0d,
                        Double.isNaN(previousProgress) ? routeProgressMeters(routeProgressIndex) : previousProgress);
            }

            // Never visually travel backward due to GPS jitter. Also cap suspicious
            // forward jumps; after a provider pause the icon catches up smoothly.
            if (!Double.isNaN(previousProgress)) {
                if (bestProgress < previousProgress - 1.5d) bestProgress = previousProgress;
                if (bestProgress > previousProgress + maxForward) bestProgress = previousProgress + maxForward;

                if (Math.abs(bestProgress - (routeCumulativeMeters.get(bestIndex) +
                        Math.max(0.01d, meters(routePoints.get(bestIndex)[0], routePoints.get(bestIndex)[1],
                                routePoints.get(bestIndex + 1)[0], routePoints.get(bestIndex + 1)[1])) * bestT)) > 0.5d) {
                    SnapPoint capped = pointAtRouteProgressLocked(bestProgress);
                    bestLat = capped.lat;
                    bestLng = capped.lng;
                    bestBearing = capped.bearing;
                    bestIndex = capped.segmentIndex;
                    bestT = capped.segmentT;
                }
            }

            lastMatchedProgressMeters = bestProgress;
            lastMatchedSegmentIndex = bestIndex;
            lastMatchedSegmentT = bestT;
            lastMatchedLat = bestLat;
            lastMatchedLng = bestLng;
            lastMatchedRawLat = lat;
            lastMatchedRawLng = lng;
            lastMatchRealtimeMs = nowRt;

            int oldProgress = routeProgressIndex;
            routeProgressIndex = Math.max(routeProgressIndex, bestIndex);
            if (routeProgressIndex != oldProgress) main.post(this::maybeUpdateRemainingRouteLine);

            return new SnapPoint(bestLat, bestLng, bestBearing, true, bestIndex, bestT, bestProgress);
        }
    }

    private SnapPoint pointAtRouteProgressLocked(double progressMeters) {
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

    private static double bearingDelta(double a, double b) {
        if (!Double.isFinite(a) || !Double.isFinite(b)) return 0d;
        double d = Math.abs(((b - a + 540d) % 360d) - 180d);
        return Math.max(0d, Math.min(180d, d));
    }

    private static final class SnapPoint {
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


    private double routeProgressMeters(int index) {
        synchronized (routePoints) {
            if (routeCumulativeMeters.isEmpty()) return 0d;
            int i = Math.max(0, Math.min(index, routeCumulativeMeters.size() - 1));
            return routeCumulativeMeters.get(i);
        }
    }

    private SnapPoint advanceAlongRoute(SnapPoint base, double metersAhead) {
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

    private void updateInstructionBanner(double progressMeters) {
        if (instructionBadge == null) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastInstructionUiAt < 250L) return;
        lastInstructionUiAt = now;

        JSONObject next = null;
        double remaining = Double.MAX_VALUE;
        for (int i = 0; i < routeManeuvers.length(); i++) {
            JSONObject m = routeManeuvers.optJSONObject(i);
            if (m == null) continue;
            double at = m.optDouble("distance_from_start", -1d);
            if (at < 0d) continue;
            double d = at - progressMeters;
            if (d >= -8d && d < remaining) {
                remaining = Math.max(0d, d);
                next = m;
            }
        }

        if (next == null) {
            instructionBadge.setText("↑ Terus ikuti rute");
            return;
        }

        String modifier = next.optString("modifier", "").toLowerCase(Locale.US);
        String type = next.optString("type", "").toLowerCase(Locale.US);
        String road = next.optString("name", "").trim();

        String arrow = "↑";
        String action = "Terus lurus";
        if (modifier.contains("left")) {
            arrow = "↰";
            action = "Belok kiri";
        } else if (modifier.contains("right")) {
            arrow = "↱";
            action = "Belok kanan";
        } else if (modifier.contains("uturn")) {
            arrow = "↶";
            action = "Putar balik";
        } else if (type.contains("arrive")) {
            arrow = "⚑";
            action = "Tiba di tujuan";
        }

        String distText;
        if (remaining >= 1000d) distText = String.format(Locale.US, "%.1f km", remaining / 1000d);
        else if (remaining >= 100d) distText = String.format(Locale.US, "%.0f m", Math.round(remaining / 50d) * 50d);
        else distText = String.format(Locale.US, "%.0f m", Math.round(remaining / 10d) * 10d);

        String text = arrow + " " + distText + " • " + action;
        if (!road.isEmpty()) text += "\n" + road;
        instructionBadge.setText(text);
    }

    private String routeGeoJson(String pointsJson) throws Exception {
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

    private String emptyFeatureCollection() {
        return "{\"type\":\"FeatureCollection\",\"features\":[]}";
    }

    private void uploadLocation(Location l) {
        long now = System.currentTimeMillis();
        if (now - lastUploadAt < LOCATION_UPLOAD_MS) return;
        lastUploadAt = now;

        final double lat = l.getLatitude(), lng = l.getLongitude();
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                JSONObject body = new JSONObject();
                body.put("username", username);
                body.put("driver", username);
                body.put("latitude", lat);
                body.put("longitude", lng);
                body.put("driver_type", vehicleType);
                body.put("accuracy", l.hasAccuracy() ? l.getAccuracy() : JSONObject.NULL);
                body.put("speed", currentSpeedKmh);
                body.put("speed_kmh", currentSpeedKmh);
                body.put("average_speed_kmh", averageSpeedKmh);
                body.put("bearing", currentBearing);
                body.put("location_time", l.getTime());
                body.put("order_id", first(order.optString("order_id"), order.optString("id"), ""));

                c = (HttpURLConnection) new URL(LOCATION_API).openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(5000);
                c.setReadTimeout(6000);
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("Accept", "application/json");
                try {
                    String token = session == null ? "" : session.getToken();
                    if (token != null && !token.trim().isEmpty()) {
                        c.setRequestProperty("Authorization", "Bearer " + token.trim());
                    }
                } catch (Exception ignored) {}
                c.setDoOutput(true);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                c.getResponseCode();
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.disconnect();
            }
        }, "transiva-location-upload").start();
    }

    private double targetLat() {
        return targetMode.equals("delivery") ?
                coord("delivery_lat", "destination_lat") :
                coord("pickup_lat", "user_lat");
    }

    private double targetLng() {
        return targetMode.equals("delivery") ?
                coord("delivery_lng", "destination_lng") :
                coord("pickup_lng", "user_lng");
    }

    private String routeTargetMode() {
        String st = first(order.optString("status"), "taken").toLowerCase(Locale.US);
        if (st.equals("arrived_pickup") || st.equals("on_delivery") || st.equals("arrived_delivery")) {
            return "delivery";
        }
        return "pickup";
    }

    private double coord(String a, String b) {
        try { return Double.parseDouble(first(order.optString(a), order.optString(b), "0")); }
        catch (Exception e) { return 0d; }
    }

    private String normalizeVehicle(String value) {
        value = first(value, "motor").toLowerCase(Locale.US);
        return value.contains("car") || value.contains("mobil") ? "car" : "motor";
    }

    private float meters(double aLat, double aLng, double bLat, double bLng) {
        try {
            float[] r = new float[1];
            Location.distanceBetween(aLat, aLng, bLat, bLng, r);
            return r[0];
        } catch (Exception e) {
            return 999999f;
        }
    }

    private double bearing(double lat1, double lng1, double lat2, double lng2) {
        if (!valid(lat1, lng1) || !valid(lat2, lng2)) return currentBearing;
        double dl = Math.toRadians(lng2 - lng1);
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) -
                Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360d) % 360d;
    }

    private boolean valid(double lat, double lng) {
        return Double.isFinite(lat) && Double.isFinite(lng) && lat != 0d && lng != 0d;
    }

    private String first(String... values) {
        if (values == null) return "";
        for (String s : values) {
            if (s != null && !s.trim().isEmpty() && !"null".equalsIgnoreCase(s.trim())) return s.trim();
        }
        return "";
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + .5f);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 801 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startLocationWatch();
        }
    }

    @Override protected void onStart() {
        super.onStart();
        if (mapView != null) mapView.onStart();
    }

    @Override protected void onResume() {
        super.onResume();
        if (mapView != null) mapView.onResume();
        startLocationWatch();
    }

    @Override protected void onPause() {
        stopLocationWatch();
        if (mapView != null) mapView.onPause();
        super.onPause();
    }

    @Override protected void onStop() {
        if (mapView != null) mapView.onStop();
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mapView != null) mapView.onSaveInstanceState(outState);
    }

    @Override public void onLowMemory() {
        super.onLowMemory();
        if (mapView != null) mapView.onLowMemory();
    }

    @Override protected void onDestroy() {
        stopLocationWatch();
        main.removeCallbacks(animationTick);
        main.removeCallbacks(routeRetryTick);
        main.removeCallbacksAndMessages(null);
        if (mapView != null) mapView.onDestroy();
        super.onDestroy();
    }
}
