package com.transiva.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class DriverRetryPolicyTest {
    @Test
    public void retryAfterIsHonoredAndCapped() {
        assertEquals(5_000L, DriverRetryPolicy.delayFor(429, 5, 0));
        assertEquals(60_000L, DriverRetryPolicy.delayFor(429, 120, 0));
    }

    @Test
    public void nonRetryable4xxReturnsZero() {
        assertEquals(0L, DriverRetryPolicy.delayFor(400, 0, 1));
        assertEquals(0L, DriverRetryPolicy.delayFor(401, 0, 1));
        assertEquals(0L, DriverRetryPolicy.delayFor(404, 0, 1));
    }

    @Test
    public void serverBackoffStaysInsideExpectedBounds() {
        long delay = DriverRetryPolicy.delayFor(503, 0, 2);
        assertTrue(delay >= 20_000L);
        assertTrue(delay <= 60_000L);
    }
}
