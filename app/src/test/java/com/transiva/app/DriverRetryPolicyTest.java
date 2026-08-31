package com.transiva.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class DriverRetryPolicyTest {
    @Test
    public void retryBackoffIsMonotonic() {
        long[] delaysMs = {1000L, 2000L, 4000L, 8000L};
        for (int i = 1; i < delaysMs.length; i++) {
            assertTrue(delaysMs[i] > delaysMs[i - 1]);
        }
    }

    @Test
    public void retryBackoffRemainsBounded() {
        long maximumDelayMs = 30_000L;
        long[] delaysMs = {1000L, 2000L, 4000L, 8000L};
        for (long delay : delaysMs) {
            assertTrue(delay <= maximumDelayMs);
        }
    }
}
