package com.transiva.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class DriverRetryPolicyTest {
    @Test
    public void nonRetryableClientErrorHasNoDelay() {
        assertEquals(0L, DriverRetryPolicy.delayFor(400, 0, 0));
        assertEquals(0L, DriverRetryPolicy.delayFor(404, 0, 2));
    }

    @Test
    public void retryAfterIsRespectedAndCapped() {
        assertEquals(5_000L, DriverRetryPolicy.delayFor(429, 5, 0));
        assertEquals(60_000L, DriverRetryPolicy.delayFor(429, 120, 0));
    }

    @Test
    public void serverRetryDelayStaysWithinExpectedBounds() {
        long delay = DriverRetryPolicy.delayFor(500, 0, 0);
        assertTrue(delay >= 5_000L);
        assertTrue(delay <= 6_000L);
    }
}
