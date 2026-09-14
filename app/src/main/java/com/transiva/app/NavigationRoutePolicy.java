package com.transiva.app;

/** Tunable route/matching thresholds extracted from DriverNavigationActivity. */
public final class NavigationRoutePolicy {
    private NavigationRoutePolicy() {}

    public static final long ROUTE_REFRESH_MS = 15_000L;
    public static final float ROUTE_REFRESH_DISTANCE_M = 35f;
    // Fast but GPS-safe rerouting. Urban alternate roads can be only 20-40 m apart,
    // so waiting for 45-120 m deviation made the old route linger too long.
    public static final double OFF_ROUTE_BASE_DISTANCE_M = 28d;
    public static final double OFF_ROUTE_HARD_DISTANCE_M = 75d;
    public static final double OFF_ROUTE_MIN_TRAVEL_M = 12d;
    public static final long OFF_ROUTE_CONFIRM_MS = 2_200L;
    public static final long REROUTE_COOLDOWN_MS = 7_000L;
    public static final double ROUTE_MATCH_BACKWARD_ALLOWANCE_M = 8d;
    public static final double ROUTE_MATCH_FORWARD_BASE_M = 42d;
    public static final double ROUTE_MATCH_MAX_DISTANCE_M = 42d;
    public static final double ROUTE_LINE_CUT_AHEAD_M = 4.5d;
    public static final double ROUTE_LINE_PROGRESS_STEP_M = 3.0d;

    public static boolean shouldRefreshRoute(long nowMs, long lastRequestMs, float movedMeters) {
        return nowMs - lastRequestMs >= ROUTE_REFRESH_MS || movedMeters >= ROUTE_REFRESH_DISTANCE_M;
    }

    public static boolean hardOffRoute(double distanceMeters) {
        return distanceMeters >= OFF_ROUTE_HARD_DISTANCE_M;
    }
}
