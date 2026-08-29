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
import android.os.SystemClock;

/** GPS/network provider arbitration for native navigation. */
public final class NavigationLocationController {
    public interface Callback {
        void onSmoothFix(Location raw, SmoothLocationEngine.Fix fix);
        void onPermissionRequired();
    }

    private final Activity activity;
    private final SmoothLocationEngine smooth;
    private final Callback callback;
    private LocationManager manager;
    private LocationListener listener;
    private Location lastAccepted;
    private long lastGpsAcceptedAt;
    private float lastGpsAcceptedAccuracy = Float.MAX_VALUE;

    public NavigationLocationController(Activity activity, SmoothLocationEngine smooth, Callback callback) {
        this.activity = activity;
        this.smooth = smooth;
        this.callback = callback;
    }

    public boolean hasPermission() {
        return Build.VERSION.SDK_INT < 23 ||
                activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    public void start() {
        if (!hasPermission()) {
            if (callback != null) callback.onPermissionRequired();
            return;
        }
        stop();
        try {
            manager = (LocationManager) activity.getSystemService(Activity.LOCATION_SERVICE);
            if (manager == null) return;
            listener = new LocationListener() {
                @Override public void onLocationChanged(Location raw) {
                    if (!shouldAccept(raw)) return;
                    SmoothLocationEngine.Fix fix = smooth.offer(raw);
                    if (fix == null) return;
                    if (LocationManager.GPS_PROVIDER.equals(raw.getProvider())) {
                        lastGpsAcceptedAt = SystemClock.elapsedRealtime();
                        lastGpsAcceptedAccuracy = raw.hasAccuracy() ? raw.getAccuracy() : 50f;
                    }
                    lastAccepted = new Location(fix.location);
                    if (callback != null) callback.onSmoothFix(raw, fix);
                }
                @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
                @Override public void onProviderEnabled(String provider) {}
                @Override public void onProviderDisabled(String provider) {}
            };
            DevicePerformanceProfile perf = DevicePerformanceProfile.get(activity);
            try { manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, perf.navigationGpsMs, perf.navigationMinDistanceM, listener, Looper.getMainLooper()); }
            catch (Throwable t) { TransivaDiagnostics.error(activity, "navigation", "NAV_GPS_PROVIDER_START_FAILED", t); }
            try { manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, perf.navigationNetworkMs, perf.navigationMinDistanceM, listener, Looper.getMainLooper()); }
            catch (Throwable t) { TransivaDiagnostics.error(activity, "navigation", "NAV_NETWORK_PROVIDER_START_FAILED", t); }
        } catch (Throwable t) {
            TransivaDiagnostics.error(activity, "navigation", "NAV_LOCATION_START_FAILED", t);
        }
    }

    public void stop() {
        try { if (manager != null && listener != null) manager.removeUpdates(listener); }
        catch (Throwable t) { TransivaDiagnostics.error(activity, "navigation", "NAV_LOCATION_STOP_FAILED", t); }
        listener = null;
    }

    public Location bestLastKnown() {
        if (!hasPermission()) return null;
        try {
            LocationManager lm = (LocationManager) activity.getSystemService(Activity.LOCATION_SERVICE);
            if (lm == null) return null;
            Location gps = null, net = null;
            try { gps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER); }
            catch (Throwable t) { TransivaDiagnostics.error(activity, "navigation", "NAV_LAST_GPS_FAILED", t); }
            try { net = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER); }
            catch (Throwable t) { TransivaDiagnostics.error(activity, "navigation", "NAV_LAST_NETWORK_FAILED", t); }
            if (gps == null) return net;
            if (net == null) return gps;
            return net.getTime() > gps.getTime() ? net : gps;
        } catch (Throwable t) {
            TransivaDiagnostics.error(activity, "navigation", "NAV_LAST_LOCATION_FAILED", t);
            return null;
        }
    }

    private boolean shouldAccept(Location raw) {
        if (raw == null) return false;
        String provider = raw.getProvider();
        if (LocationManager.GPS_PROVIDER.equals(provider)) return true;
        if (LocationManager.NETWORK_PROVIDER.equals(provider)) {
            long age = SystemClock.elapsedRealtime() - lastGpsAcceptedAt;
            float networkAcc = raw.hasAccuracy() ? Math.max(1f, raw.getAccuracy()) : 100f;
            if (lastGpsAcceptedAt > 0L && age < 5500L && lastGpsAcceptedAccuracy <= 45f) return false;
            if (lastAccepted != null && lastAccepted.hasAccuracy() && age < 9000L &&
                    networkAcc > Math.max(55f, lastAccepted.getAccuracy() * 1.8f)) return false;
        }
        return true;
    }
}
