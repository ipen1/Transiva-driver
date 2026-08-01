package com.transiva.app;
import org.junit.Test;
import static org.junit.Assert.*;
public class DriverRequestGateTest {
    @Test public void sameTransactionCannotEnterTwice() {
        String key = "accept:order-100";
        assertTrue(DriverRequestGate.enter(key));
        assertFalse(DriverRequestGate.enter(key));
        DriverRequestGate.leave(key);
        assertTrue(DriverRequestGate.enter(key));
        DriverRequestGate.leave(key);
    }
}
