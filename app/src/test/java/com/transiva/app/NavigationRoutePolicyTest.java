package com.transiva.app;

import org.junit.Test;

import static org.junit.Assert.*;

/** Regression tests for the production navigation reroute policy. */
public class NavigationRoutePolicyTest {
    @Test public void routeRefreshOccursByAgeOrMovement() {
        assertFalse(NavigationRoutePolicy.shouldRefreshRoute(
                NavigationRoutePolicy.ROUTE_REFRESH_MS - 1L, 0L,
                NavigationRoutePolicy.ROUTE_REFRESH_DISTANCE_M - 1f));
        assertTrue(NavigationRoutePolicy.shouldRefreshRoute(
                NavigationRoutePolicy.ROUTE_REFRESH_MS, 0L, 10f));
        assertTrue(NavigationRoutePolicy.shouldRefreshRoute(
                1_000L, 0L, NavigationRoutePolicy.ROUTE_REFRESH_DISTANCE_M));
    }

    @Test public void hardOffRouteThresholdMatchesCurrentPolicy() {
        assertFalse(NavigationRoutePolicy.hardOffRoute(
                NavigationRoutePolicy.OFF_ROUTE_HARD_DISTANCE_M - 0.1d));
        assertTrue(NavigationRoutePolicy.hardOffRoute(
                NavigationRoutePolicy.OFF_ROUTE_HARD_DISTANCE_M));
        assertTrue(NavigationRoutePolicy.hardOffRoute(
                NavigationRoutePolicy.OFF_ROUTE_HARD_DISTANCE_M + 25d));
    }

    @Test public void rerouteThresholdsRemainFastButGpsSafe() {
        assertTrue(NavigationRoutePolicy.OFF_ROUTE_BASE_DISTANCE_M > 0d);
        assertTrue(NavigationRoutePolicy.OFF_ROUTE_HARD_DISTANCE_M
                > NavigationRoutePolicy.OFF_ROUTE_BASE_DISTANCE_M);
        assertTrue(NavigationRoutePolicy.OFF_ROUTE_MIN_TRAVEL_M > 0d);
        assertTrue(NavigationRoutePolicy.OFF_ROUTE_CONFIRM_MS >= 1_500L);
        assertTrue(NavigationRoutePolicy.REROUTE_COOLDOWN_MS
                > NavigationRoutePolicy.OFF_ROUTE_CONFIRM_MS);
    }

    @Test public void matchingWindowRemainsSane() {
        assertTrue(NavigationRoutePolicy.ROUTE_MATCH_BACKWARD_ALLOWANCE_M >= 0d);
        assertTrue(NavigationRoutePolicy.ROUTE_MATCH_FORWARD_BASE_M
                > NavigationRoutePolicy.ROUTE_MATCH_BACKWARD_ALLOWANCE_M);
        assertTrue(NavigationRoutePolicy.ROUTE_MATCH_MAX_DISTANCE_M
                >= NavigationRoutePolicy.ROUTE_MATCH_FORWARD_BASE_M);
    }
}
