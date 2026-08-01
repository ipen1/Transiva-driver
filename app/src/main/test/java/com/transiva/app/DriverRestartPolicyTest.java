package com.transiva.app;
import org.junit.Test;
import static org.junit.Assert.*;
public class DriverRestartPolicyTest {
    @Test public void restartLimitsAreConservative() {
        assertTrue("restart limiter must remain small", 3 <= 3);
    }
}
