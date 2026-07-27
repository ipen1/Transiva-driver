package com.transiva.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Fetches OSRM routes from native Android instead of WebView JavaScript.
 * This avoids WebView CORS/fetch failures and keeps the last good route on screen.
 */
public final class StableRouteEngine {
    private static final String OSRM = "https://router.project-osrm.org/route/v1/driving/";
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final long CACHE_TTL_MS = 180000L;
    private static volatile Result cacheResult;
    private static volatile double cacheFromLat, cacheFromLng, cacheToLat, cacheToLng;
    private static volatile long cacheAt;

    private StableRouteEngine() {}

    public static final class Result {
        public final JSONArray latLngPoints;
        public final double distanceMeters;
        public final double durationSeconds;
        public final JSONArray maneuvers;

        Result(JSONArray latLngPoints, double distanceMeters, double durationSeconds,
               JSONArray maneuvers) {
            this.latLngPoints = latLngPoints;
            this.distanceMeters = distanceMeters;
            this.durationSeconds = durationSeconds;
            this.maneuvers = maneuvers == null ? new JSONArray() : maneuvers;
        }

        public String pointsJson() {
            return latLngPoints == null ? "[]" : latLngPoints.toString();
        }
    }

    public static Result fetch(double fromLat, double fromLng, double toLat, double toLng) throws Exception {
        if (!valid(fromLat, fromLng) || !valid(toLat, toLng)) {
            throw new IllegalArgumentException("Invalid route coordinates");
        }

        Result cached = getCached(fromLat, fromLng, toLat, toLng);
        if (cached != null) return cached;

        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection connection = null;
            try {
                String endpoint = OSRM
                        + String.format(Locale.US, "%.7f,%.7f;%.7f,%.7f", fromLng, fromLat, toLng, toLat)
                        + "?overview=full&geometries=geojson&steps=true&alternatives=false";
                connection = (HttpURLConnection) new URL(endpoint).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setUseCaches(false);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("User-Agent", "Transiva-Android/1.0");

                int code = connection.getResponseCode();
                InputStream stream = code >= 200 && code < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                String raw = readAll(stream);
                if (code < 200 || code >= 300) {
                    throw new IllegalStateException("Route HTTP " + code);
                }

                JSONObject root = new JSONObject(raw);
                JSONArray routes = root.optJSONArray("routes");
                if (routes == null || routes.length() == 0) {
                    throw new IllegalStateException("No route returned");
                }
                JSONObject route = routes.getJSONObject(0);
                JSONObject geometry = route.optJSONObject("geometry");
                JSONArray coordinates = geometry == null ? null : geometry.optJSONArray("coordinates");
                if (coordinates == null || coordinates.length() < 2) {
                    throw new IllegalStateException("Route geometry empty");
                }

                JSONArray points = new JSONArray();
                for (int i = 0; i < coordinates.length(); i++) {
                    JSONArray lngLat = coordinates.optJSONArray(i);
                    if (lngLat == null || lngLat.length() < 2) continue;
                    double lng = lngLat.optDouble(0, Double.NaN);
                    double lat = lngLat.optDouble(1, Double.NaN);
                    if (Double.isNaN(lat) || Double.isNaN(lng)) continue;
                    JSONArray latLng = new JSONArray();
                    latLng.put(lat);
                    latLng.put(lng);
                    points.put(latLng);
                }
                if (points.length() < 2) throw new IllegalStateException("Route points empty");

                JSONArray maneuvers = parseManeuvers(route);
                Result result = new Result(points,
                        route.optDouble("distance", 0d),
                        route.optDouble("duration", 0d),
                        maneuvers);
                cacheResult = result;
                cacheFromLat = fromLat; cacheFromLng = fromLng;
                cacheToLat = toLat; cacheToLng = toLng;
                cacheAt = System.currentTimeMillis();
                return result;
            } catch (Exception e) {
                last = e;
                if (attempt < 1) {
                    try { Thread.sleep(180L); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw ie;
                    }
                }
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        throw last != null ? last : new IllegalStateException("Route failed");
    }


    /**
     * Compact OSRM turn instructions. distance_from_start is based on step
     * distance, which is good enough for a lightweight navigation banner.
     */
    private static JSONArray parseManeuvers(JSONObject route) throws Exception {
        JSONArray out = new JSONArray();
        JSONArray legs = route.optJSONArray("legs");
        if (legs == null) return out;

        double cumulative = 0d;
        for (int li = 0; li < legs.length(); li++) {
            JSONObject leg = legs.optJSONObject(li);
            if (leg == null) continue;
            JSONArray steps = leg.optJSONArray("steps");
            if (steps == null) continue;

            for (int si = 0; si < steps.length(); si++) {
                JSONObject step = steps.optJSONObject(si);
                if (step == null) continue;
                JSONObject man = step.optJSONObject("maneuver");
                if (man == null) {
                    cumulative += Math.max(0d, step.optDouble("distance", 0d));
                    continue;
                }

                JSONObject item = new JSONObject();
                item.put("distance_from_start", cumulative);
                item.put("step_distance", Math.max(0d, step.optDouble("distance", 0d)));
                item.put("name", step.optString("name", ""));
                item.put("type", man.optString("type", ""));
                item.put("modifier", man.optString("modifier", ""));

                JSONArray loc = man.optJSONArray("location"); // [lng,lat]
                if (loc != null && loc.length() >= 2) {
                    item.put("lng", loc.optDouble(0, 0d));
                    item.put("lat", loc.optDouble(1, 0d));
                }
                out.put(item);
                cumulative += Math.max(0d, step.optDouble("distance", 0d));
            }
        }
        return out;
    }

    private static Result getCached(double fromLat, double fromLng, double toLat, double toLng) {
        Result r = cacheResult;
        if (r == null || System.currentTimeMillis() - cacheAt > CACHE_TTL_MS) return null;
        if (meters(fromLat, fromLng, cacheFromLat, cacheFromLng) > 80d) return null;
        if (meters(toLat, toLng, cacheToLat, cacheToLng) > 30d) return null;
        return r;
    }

    private static double meters(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371000d;
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1), dl = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dp/2d)*Math.sin(dp/2d) +
                Math.cos(p1)*Math.cos(p2)*Math.sin(dl/2d)*Math.sin(dl/2d);
        return r * 2d * Math.atan2(Math.sqrt(a), Math.sqrt(1d-a));
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) out.append(line);
        reader.close();
        return out.toString();
    }

    private static boolean valid(double lat, double lng) {
        return Double.isFinite(lat) && Double.isFinite(lng)
                && lat >= -90d && lat <= 90d && lng >= -180d && lng <= 180d
                && !(lat == 0d && lng == 0d);
    }
}
