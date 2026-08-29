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
    private int speedBand = -1;

    public NavigationCameraController(NavigationCompatibilityProfile profile) { this.profile = profile; }

    public double update(MapLibreMap map, double lat, double lng, double desiredBearing,
                         double speedKmh, boolean pip, boolean immediate) {
        if (map == null) return desiredBearing;
        smoothBearing = easeBearing(smoothBearing, desiredBearing,
                immediate ? 1f : (profile != null ? profile.cameraBearingAlpha : 0.065f));
        double desiredZoom, desiredTilt;
        if (pip) {
            desiredZoom = 14.8d; desiredTilt = 0d; speedBand = 4;
        } else {
            speedBand = stableSpeedBand(speedKmh, speedBand);
            if (speedBand <= 0) { desiredZoom = 18.2d; desiredTilt = 34d; }
            else if (speedBand == 1) { desiredZoom = 17.7d; desiredTilt = 40d; }
            else if (speedBand == 2) { desiredZoom = 17.0d; desiredTilt = 44d; }
            else if (speedBand == 3) { desiredZoom = 16.2d; desiredTilt = 47d; }
            else { desiredZoom = 15.5d; desiredTilt = 49d; }
        }
        if (profile != null) desiredTilt = Math.min(desiredTilt, profile.cameraTilt);
        // Slow target changes prevent visible zoom/tilt pumping when GPS speed
        // fluctuates by a few km/h around a threshold.
        smoothZoom = Double.isNaN(smoothZoom) ? desiredZoom : smoothZoom + (desiredZoom - smoothZoom) * (immediate ? 1d : 0.022d);
        smoothTilt = Double.isNaN(smoothTilt) ? desiredTilt : smoothTilt + (desiredTilt - smoothTilt) * (immediate ? 1d : 0.025d);
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

    private static int stableSpeedBand(double speedKmh, int current) {
        double s = Math.max(0d, speedKmh);
        if (current < 0) {
            if (s < 4d) return 0;
            if (s < 27d) return 1;
            if (s < 58d) return 2;
            if (s < 83d) return 3;
            return 4;
        }
        // 3-6 km/h hysteresis prevents rapid band switching.
        if (current == 0) return s > 6d ? 1 : 0;
        if (current == 1) { if (s < 2d) return 0; if (s > 29d) return 2; return 1; }
        if (current == 2) { if (s < 21d) return 1; if (s > 61d) return 3; return 2; }
        if (current == 3) { if (s < 49d) return 2; if (s > 86d) return 4; return 3; }
        return s < 74d ? 3 : 4;
    }

    private static double easeBearing(double current, double target, float alpha) {
        target = ((target % 360d) + 360d) % 360d;
        if (Double.isNaN(current)) return target;
        current = ((current % 360d) + 360d) % 360d;
        double delta = ((target - current + 540d) % 360d) - 180d;
        return (current + delta * alpha + 360d) % 360d;
    }
}
