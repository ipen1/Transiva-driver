package com.transiva.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;

/** Provider/lifecycle owner for DriverTripActivity location UI fixes. */
public final class TripLocationController {
    public interface Callback { void onLocation(Location location); void onPermissionRequired(); }
    private static final long GPS_PRIORITY_MS = 8000L;
    private static final long MAX_LOCATION_AGE_MS = 30000L;
    private static final long OUT_OF_ORDER_TOLERANCE_MS = 1500L;
    private final Activity activity;
    private final Callback callback;
    private LocationManager manager;
    private LocationListener listener;
    private Location lastAccepted;
    private long lastGpsFixAt;

    public TripLocationController(Activity activity, Callback callback) {
        this.activity = activity; this.callback = callback;
    }

    public void start() {
        if (Build.VERSION.SDK_INT >= 23 && activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (callback != null) callback.onPermissionRequired();
            return;
        }
        stop();
        try {
            manager = (LocationManager) activity.getSystemService(Activity.LOCATION_SERVICE);
            if (manager == null) return;
            listener = new LocationListener() {
                @Override public void onLocationChanged(Location l) {
                    if (!accept(l)) return;
                    if (LocationManager.GPS_PROVIDER.equals(l.getProvider())) lastGpsFixAt = System.currentTimeMillis();
                    lastAccepted = new Location(l);
                    if (callback != null) callback.onLocation(l);
                }
                @Override public void onStatusChanged(String p, int s, Bundle e) {}
                @Override public void onProviderEnabled(String p) {}
                @Override public void onProviderDisabled(String p) {}
            };
            Location last = bestLastKnown();
            if (last != null && fresh(last) && callback != null) callback.onLocation(last);
            DevicePerformanceProfile perf = DevicePerformanceProfile.get(activity);
            try { manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, perf.tripGpsMs, perf.tripMinDistanceM, listener, Looper.getMainLooper()); }
            catch (Throwable t) { TransivaDiagnostics.error(activity, "order", "TRIP_GPS_PROVIDER_START_FAILED", t); }
            try { manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, perf.tripNetworkMs, perf.tripMinDistanceM, listener, Looper.getMainLooper()); }
            catch (Throwable t) { TransivaDiagnostics.error(activity, "order", "TRIP_NETWORK_PROVIDER_START_FAILED", t); }
        } catch (Throwable t) { TransivaDiagnostics.error(activity, "order", "TRIP_LOCATION_START_FAILED", t); }
    }

    public void stop() {
        try { if (manager != null && listener != null) manager.removeUpdates(listener); }
        catch (Throwable t) { TransivaDiagnostics.error(activity, "order", "TRIP_LOCATION_STOP_FAILED", t); }
        listener = null;
    }

    private Location bestLastKnown() {
        if (manager == null) return null;
        Location gps = null, net = null;
        try { gps = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER); }
        catch (Throwable t) { TransivaDiagnostics.error(activity, "order", "TRIP_LAST_GPS_FAILED", t); }
        try { net = manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER); }
        catch (Throwable t) { TransivaDiagnostics.error(activity, "order", "TRIP_LAST_NETWORK_FAILED", t); }
        if (gps == null) return net; if (net == null) return gps;
        if (!fresh(net)) return gps; if (!fresh(gps)) return net;
        float ga = gps.hasAccuracy() ? gps.getAccuracy() : 9999f;
        float na = net.hasAccuracy() ? net.getAccuracy() : 9999f;
        return ga <= na ? gps : net;
    }

    private boolean fresh(Location l) {
        if (l == null) return false;
        if (Build.VERSION.SDK_INT >= 17) {
            long age = android.os.SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos();
            return age <= MAX_LOCATION_AGE_MS * 1_000_000L;
        }
        return System.currentTimeMillis() - l.getTime() <= MAX_LOCATION_AGE_MS;
    }

    private boolean accept(Location l) {
        if (l == null || !valid(l.getLatitude(), l.getLongitude())) return false;
        long now = System.currentTimeMillis();
        if (LocationManager.NETWORK_PROVIDER.equals(l.getProvider()) && lastGpsFixAt > 0L && now - lastGpsFixAt < GPS_PRIORITY_MS) return false;
        if (lastAccepted != null) {
            long nt = l.getTime(), ot = lastAccepted.getTime();
            if (nt > 0L && ot > 0L && nt + OUT_OF_ORDER_TOLERANCE_MS < ot) return false;
        }
        return true;
    }

    private static boolean valid(double lat,double lng){ return lat!=0d && lng!=0d && !Double.isNaN(lat) && !Double.isNaN(lng); }
}
