package com.transiva.app;
import org.junit.Test;
import static org.junit.Assert.*;
public class DriverRetryPolicyTest {
    @Test public void rateLimitHonorsRetryAfter() { assertEquals(5000L, DriverRetryPolicy.delayFor(429,5,0)); }
    @Test public void serverErrorsBackOff() { assertTrue(DriverRetryPolicy.delayFor(500,0,3) >= 8000L); }
    @Test public void normalErrorsDoNotRetry() { assertEquals(0L, DriverRetryPolicy.delayFor(400,0,0)); }
}
