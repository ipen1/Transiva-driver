package com.transiva.app;

import android.location.Location;
import android.os.SystemClock;

final class TripArrivalLocation {
    static final int RADIUS_METERS = 100;

    static Location verified(Location fix) {
        if (fix == null || !fix.hasAccuracy() || !Float.isFinite(fix.getAccuracy())
                || fix.getAccuracy() <= 0 || fix.getAccuracy() > 35
                || !NavigationTargetPolicy.valid(fix.getLatitude(), fix.getLongitude())) return null;
        long time = fix.getElapsedRealtimeNanos();
        long age = SystemClock.elapsedRealtimeNanos() - time;
        return time > 0 && age >= 0 && age <= 15_000_000_000L ? new Location(fix) : null;
    }

    static float distance(Location fix, NavigationTargetPolicy.Target target) {
        if (fix == null || !target.valid()) return -1;
        float[] distance = new float[1];
        Location.distanceBetween(fix.getLatitude(), fix.getLongitude(), target.lat, target.lng, distance);
        return distance[0];
    }
}
