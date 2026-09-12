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
abstract class DriverNavigationActivityLayer2 extends DriverNavigationActivityLayer1 {

    protected double easeBearing(double current, double target, float alpha) {
        target = ((target % 360d) + 360d) % 360d;
        if (Double.isNaN(current)) return target;
        current = ((current % 360d) + 360d) % 360d;
        double delta = ((target - current + 540d) % 360d) - 180d;
        return (current + delta * alpha + 360d) % 360d;
    }

    protected double easePosition(double current, double target, float alpha) {
        return current + (target - current) * alpha;
    }

    protected void updateNativePosition(boolean immediate) {
        if (map == null || !styleReady) return;
        installMarkers();

        double lat = displayInitialized ? displayLat : driverLat;
        double lng = displayInitialized ? displayLng : driverLng;
        if (!valid(lat, lng)) return;

        // Vehicle source belongs to a top-most SymbolLayer. Updating only one
        // GeoJSON point is cheap and guarantees the icon remains above the route.
        updateVehicleSource();

        double desiredBearing = routePoints.size() >= 2 && Double.isFinite(snappedBearing)
                ? snappedBearing
                : (Double.isFinite(currentBearing) ? currentBearing : 0d);
        boolean pipCamera = pipController != null && pipController.isActive();
        double cameraBearing = cameraController != null
                ? cameraController.update(map, lat, lng, desiredBearing, currentSpeedKmh, pipCamera, immediate)
                : desiredBearing;
        smoothMarkerBearing = easeBearing(smoothMarkerBearing, desiredBearing,
                immediate ? 1.0f : (navProfile != null ? navProfile.bearingEaseAlpha : (currentSpeedKmh < 5d ? 0.10f : 0.16f)));
        long uiNow = SystemClock.elapsedRealtime();
        if (speedBadge != null && uiNow - lastSpeedUiAt >= 400L) {
            lastSpeedUiAt = uiNow;
            speedBadge.setText(String.format(Locale.US, "%.0f km/j\nRata-rata %.0f km/j",
                    currentSpeedKmh, averageSpeedKmh));
        }
    }

    protected void updateSpeed(Location l) {
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
        long nowRealtime = SystemClock.elapsedRealtime();
        previousSpeedKmh = currentSpeedKmh;
        currentSpeedKmh = currentSpeedKmh <= 0 ? instant :
                currentSpeedKmh * 0.72d + instant * 0.28d;
        if (lastSpeedRealtimeMs > 0L) {
            double dtSec = Math.max(0.25d, Math.min(3d, (nowRealtime - lastSpeedRealtimeMs) / 1000d));
            double acceleration = ((currentSpeedKmh - previousSpeedKmh) / 3.6d) / dtSec;
            acceleration = Math.max(-4.5d, Math.min(4.5d, acceleration));
            smoothedAccelerationMps2 = smoothedAccelerationMps2 * 0.78d + acceleration * 0.22d;
        }
        lastSpeedRealtimeMs = nowRealtime;
        if (currentSpeedKmh >= 1d) {
            speedSum += currentSpeedKmh;
            speedSamples++;
            averageSpeedKmh = speedSum / Math.max(1L, speedSamples);
        }
        lastSpeedLocation = new Location(l);
    }

    protected void requestRoute(boolean force) {
        if (!valid(driverLat, driverLng) || routeInFlight) return;

        final double toLat = targetLat();
        final double toLng = targetLng();
        if (!valid(toLat, toLng)) return;

        long now = System.currentTimeMillis();
        if (!force && lastRouteLocation != null &&
                meters(lastRouteLocation.getLatitude(), lastRouteLocation.getLongitude(),
                        driverLat, driverLng) < NavigationRoutePolicy.ROUTE_REFRESH_DISTANCE_M &&
                now - lastRouteRequestAt < NavigationRoutePolicy.ROUTE_REFRESH_MS) {
            return;
        }

        routeInFlight = true;
        lastRouteRequestAt = now;
        if (routeScheduler != null) routeScheduler.beginLoading(4);

        final double fromLat = driverLat, fromLng = driverLng;

        routeExecutor.execute(() -> {
            try {
                StableRouteEngine.Result r = StableRouteEngine.fetch(fromLat, fromLng, toLat, toLng);
                main.post(() -> { if (routeScheduler != null) routeScheduler.updateProgress(72); });

                pendingRouteGeoJson = routeGeoJson(r.pointsJson());
                setRoutePoints(r.latLngPoints);
                lastRenderedRouteIndex = -1;
                lastRenderedRouteProgressMeters = Double.NaN;
                routeManeuvers = r.maneuvers == null ? new JSONArray() : r.maneuvers;
                pendingRouteKm = r.distanceMeters / 1000d;
                pendingRouteSeconds = r.durationSeconds;
                lastRouteSuccessAt = System.currentTimeMillis();
                routeFailureCount = 0;
                NavigationDiagnostics.event(this, "NAV_ROUTE_READY", null);
                NavigationHealthTelemetry.mark(this, "route");

                Location rl = new Location("route");
                rl.setLatitude(fromLat);
                rl.setLongitude(fromLng);
                lastRouteLocation = rl;

                main.post(() -> {
                    if (routeScheduler != null) routeScheduler.updateProgress(90);

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

                    if (routeScheduler != null) routeScheduler.updateProgress(100);
                    final int mins = Math.max(1, (int) Math.ceil(pendingRouteSeconds / 60d));
                    main.postDelayed(() -> {
                        if (!isFinishing() && routePoints.size() >= 2) {
                            routeBadge.setText(String.format(Locale.US, "%s • %.1f km • %d menit",
                                    targetMode.equals("delivery") ? "Menuju pengantaran" : "Menuju penjemputan",
                                    pendingRouteKm, mins));
                        }
                    }, 260L);
                });
            } catch (Exception routeError) {
                routeFailureCount++;
                NavigationDiagnostics.error(this, "NAV_ROUTE_FAILED", routeError);
                main.post(() -> {
                    if (routePoints.size() < 2) {
                        routeBadge.setText(routeFailureCount <= 1
                                ? "OSRM belum merespons • mencoba kembali…"
                                : "Rute belum tersedia • mencoba kembali…");
                    }
                });
            } finally {
                routeInFlight = false;
                main.post(() -> { if (routeScheduler != null) routeScheduler.endLoading(); });
            }
        });
    }

    protected void showRouteLoading(int percent) {
        if (routeBadge == null) return;
        int p = Math.max(0, Math.min(100, percent));
        String target = targetMode.equals("delivery") ? "tujuan" : "pickup";
        routeBadge.setText(String.format(Locale.US, "Membuat rute ke %s • %d%%", target, p));
    }

    protected void maybeRefreshRoute(Location fix) {
        if (routePoints.size() < 2) {
            resetOffRouteConfirmation();
            requestRoute(false);
            return;
        }

        final long now = SystemClock.elapsedRealtime();
        final float accuracy = fix != null && fix.hasAccuracy()
                ? Math.max(1f, fix.getAccuracy()) : lastGpsAccuracyM;
        final double deviation = nearestRouteDistanceMeters(driverLat, driverLng);
        final double trigger = Math.max(NavigationRoutePolicy.OFF_ROUTE_BASE_DISTANCE_M, accuracy * 1.35d);

        // A highly inaccurate fix is not enough evidence by itself. Only an
        // unmistakably large deviation may start confirmation while GPS is weak.
        if (!Double.isFinite(deviation) ||
                (accuracy > 85f && deviation < NavigationRoutePolicy.OFF_ROUTE_HARD_DISTANCE_M + 30d) ||
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
        long requiredMs = accuracy >= 55f ? 8000L : NavigationRoutePolicy.OFF_ROUTE_CONFIRM_MS;
        double requiredTravel = accuracy >= 55f ? 35d : NavigationRoutePolicy.OFF_ROUTE_MIN_TRAVEL_M;
        boolean hardDeviation = deviation >= NavigationRoutePolicy.OFF_ROUTE_HARD_DISTANCE_M && offRouteFixCount >= 2;
        boolean confirmed = offRouteFixCount >= requiredFixes &&
                duration >= requiredMs && traveled >= requiredTravel;

        if ((hardDeviation || confirmed) && now - lastAutoRerouteAt >= NavigationRoutePolicy.REROUTE_COOLDOWN_MS) {
            lastAutoRerouteAt = now;
            resetOffRouteConfirmation();
            if (routeBadge != null) routeBadge.setText("Mendeteksi pindah jalur • membuat rute baru…");
            requestRoute(true);
            return;
        }

        // Keep the stable current route while confirmation is in progress.
        requestRoute(false);
    }

    protected void resetOffRouteConfirmation() {
        offRouteFixCount = 0;
        offRouteStartedAt = 0L;
        offRouteStartLat = Double.NaN;
        offRouteStartLng = Double.NaN;
    }

    /** Returns raw GPS distance to the nearby route corridor without changing map-match state. */
    protected double nearestRouteDistanceMeters(double lat, double lng) {
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
    protected void maybeUpdateRemainingRouteLine() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastRouteLineUpdateAt < 220L) return;

        double lineProgress = Double.isNaN(visualRouteProgressMeters)
                ? lastMatchedProgressMeters : visualRouteProgressMeters;
        int lineSegment = Double.isNaN(visualRouteProgressMeters)
                ? lastMatchedSegmentIndex : visualRouteSegmentIndex;
        boolean segmentChanged = lineSegment != lastRenderedRouteIndex;
        boolean progressed = Double.isNaN(lastRenderedRouteProgressMeters) ||
                (!Double.isNaN(lineProgress) &&
                        lineProgress - lastRenderedRouteProgressMeters >= NavigationRoutePolicy.ROUTE_LINE_PROGRESS_STEP_M);
        if (!segmentChanged && !progressed) return;
        updateRemainingRouteLine(false);
    }

    protected void updateRemainingRouteLine(boolean force) {
        if (!styleReady || style == null) return;
        if (!force) {
            double lineProgress = Double.isNaN(visualRouteProgressMeters)
                    ? lastMatchedProgressMeters : visualRouteProgressMeters;
            int lineSegment = Double.isNaN(visualRouteProgressMeters)
                    ? lastMatchedSegmentIndex : visualRouteSegmentIndex;
            boolean segmentChanged = lineSegment != lastRenderedRouteIndex;
            boolean progressed = Double.isNaN(lastRenderedRouteProgressMeters) ||
                    (!Double.isNaN(lineProgress) &&
                            lineProgress - lastRenderedRouteProgressMeters >= NavigationRoutePolicy.ROUTE_LINE_PROGRESS_STEP_M);
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
        } catch (Exception ignored) { TransivaDiagnostics.error(this,"navigation","NON_FATAL_EXCEPTION",ignored); }
    }

    /**
     * Build only the route still in front of the vehicle. The first coordinate
     * is interpolated from continuous matched progress (+ a tiny visual cut),
     * not from the beginning of the current segment. Therefore a blue tail can
     * no longer remain behind or directly underneath the vehicle icon.
     */
    protected String remainingRouteGeoJson() {
        synchronized (routePoints) {
            if (routePoints.size() < 2) return pendingRouteGeoJson;
            try {
                double progress = !Double.isNaN(visualRouteProgressMeters)
                        ? visualRouteProgressMeters
                        : (Double.isNaN(lastMatchedProgressMeters)
                            ? routeProgressMeters(Math.max(0, routeProgressIndex))
                            : lastMatchedProgressMeters);
                double total = routeProgressMeters(routePoints.size() - 1);
                double lineStartProgress = Math.max(0d, Math.min(total, progress + NavigationRoutePolicy.ROUTE_LINE_CUT_AHEAD_M));

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

    protected int segmentForProgressLocked(double progressMeters) {
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

    protected void drawPendingRoute() {
        if (!styleReady || style == null || pendingRouteGeoJson.isEmpty()) return;
        updateRemainingRouteLine(true);
    }


    protected void setRoutePoints(JSONArray points) {
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
    protected SnapPoint snapToRoute(double lat, double lng) {
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
            final double maxForward = Math.max(NavigationRoutePolicy.ROUTE_MATCH_FORWARD_BASE_M, speedMps * dtSec * 2.2d + 22d);

            int startIndex;
            int endIndex;
            if (Double.isNaN(previousProgress)) {
                startIndex = 0;
                endIndex = Math.min(routePoints.size() - 2, 220);
            } else {
                startIndex = Math.max(0, segmentForProgressLocked(
                        Math.max(0d, previousProgress - NavigationRoutePolicy.ROUTE_MATCH_BACKWARD_ALLOWANCE_M - 18d)) - 2);
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
                    if (delta < -NavigationRoutePolicy.ROUTE_MATCH_BACKWARD_ALLOWANCE_M) {
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

            if (bestDistance > NavigationRoutePolicy.ROUTE_MATCH_MAX_DISTANCE_M) {
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
}
