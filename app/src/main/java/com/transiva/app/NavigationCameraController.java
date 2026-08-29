package com.transiva.app;

import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;

/** Speed-aware, heading-up camera smoothing separated from the Activity. */
public final class NavigationCameraController {
    private final NavigationCompatibilityProfile profile;
    private double smoothBearing = Double.NaN;
    private double smoothZoom = Double.NaN;
    private double smoothTilt = Double.NaN;

    public NavigationCameraController(NavigationCompatibilityProfile profile) { this.profile = profile; }

    public double update(MapLibreMap map, double lat, double lng, double desiredBearing,
                         double speedKmh, boolean pip, boolean immediate) {
        if (map == null) return desiredBearing;
        smoothBearing = easeBearing(smoothBearing, desiredBearing,
                immediate ? 1f : (profile != null ? profile.cameraBearingAlpha : 0.065f));
        double desiredZoom, desiredTilt;
        if (pip) { desiredZoom = 14.8d; desiredTilt = 0d; }
        else if (speedKmh < 3d) { desiredZoom = 18.2d; desiredTilt = 34d; }
        else if (speedKmh < 25d) { desiredZoom = 17.7d; desiredTilt = 40d; }
        else if (speedKmh < 55d) { desiredZoom = 17.0d; desiredTilt = 44d; }
        else if (speedKmh < 80d) { desiredZoom = 16.2d; desiredTilt = 47d; }
        else { desiredZoom = 15.5d; desiredTilt = 49d; }
        if (profile != null) desiredTilt = Math.min(desiredTilt, profile.cameraTilt);
        smoothZoom = Double.isNaN(smoothZoom) ? desiredZoom : smoothZoom + (desiredZoom - smoothZoom) * (immediate ? 1d : 0.035d);
        smoothTilt = Double.isNaN(smoothTilt) ? desiredTilt : smoothTilt + (desiredTilt - smoothTilt) * (immediate ? 1d : 0.04d);
        CameraPosition cp = new CameraPosition.Builder().target(new LatLng(lat, lng))
                .zoom(smoothZoom).bearing(smoothBearing).tilt(smoothTilt).build();
        map.moveCamera(CameraUpdateFactory.newCameraPosition(cp));
        return smoothBearing;
    }

    public void restore(MapLibreMap map, double lat, double lng) {
        if (map == null) return;
        double tilt = profile != null ? profile.cameraTilt : 42d;
        map.moveCamera(CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder()
                .target(new LatLng(lat, lng)).zoom(17.8d)
                .bearing(Double.isNaN(smoothBearing) ? 0d : smoothBearing).tilt(tilt).build()));
    }

    public double bearing() { return smoothBearing; }

    private static double easeBearing(double current, double target, float alpha) {
        target = ((target % 360d) + 360d) % 360d;
        if (Double.isNaN(current)) return target;
        current = ((current % 360d) + 360d) % 360d;
        double delta = ((target - current + 540d) % 360d) - 180d;
        return (current + delta * alpha + 360d) % 360d;
    }
}
