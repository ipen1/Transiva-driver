package com.transiva.app;

import org.junit.Test;

import static org.junit.Assert.*;

public class NavigationRoutePolicyTest {
    @Test public void routeRefreshOccursByAgeOrMovement() {
        assertFalse(NavigationRoutePolicy.shouldRefreshRoute(10_000L, 0L, 10f));
        assertTrue(NavigationRoutePolicy.shouldRefreshRoute(15_000L, 0L, 10f));
        assertTrue(NavigationRoutePolicy.shouldRefreshRoute(1_000L, 0L, 35f));
    }

    @Test public void hardOffRouteThresholdIsConservative() {
        assertFalse(NavigationRoutePolicy.hardOffRoute(119.9d));
        assertTrue(NavigationRoutePolicy.hardOffRoute(120d));
    }

    @Test public void matchingWindowRemainsSane() {
        assertTrue(NavigationRoutePolicy.ROUTE_MATCH_BACKWARD_ALLOWANCE_M >= 0d);
        assertTrue(NavigationRoutePolicy.ROUTE_MATCH_FORWARD_BASE_M > NavigationRoutePolicy.ROUTE_MATCH_BACKWARD_ALLOWANCE_M);
        assertTrue(NavigationRoutePolicy.ROUTE_MATCH_MAX_DISTANCE_M >= NavigationRoutePolicy.ROUTE_MATCH_FORWARD_BASE_M);
    }
}
