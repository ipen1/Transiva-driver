package com.transiva.app;

/**
 * Legacy compatibility helper. Regional/cluster definitions are no longer stored in the APK.
 * The server/database (transiva_regions + transiva_clusters) is the single source of truth.
 */
public final class TransivaCluster {
    private TransivaCluster() {}

    public static double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(1e-12, 1 - a)));
    }
}
