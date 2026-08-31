package com.transiva.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class DriverOrderCancellationPolicyTest {
    @Test
    public void cancellableStatusesRemainCompatible() {
        assertTrue(DriverOrderCancellationPolicy.canCancel("driver_accepted"));
        assertTrue(DriverOrderCancellationPolicy.canCancel("arrived_pickup"));
        assertTrue(DriverOrderCancellationPolicy.canCancel("accepted"));
        assertTrue(DriverOrderCancellationPolicy.canCancel("taken"));
    }

    @Test
    public void deliveryAndFinishedStatusesCannotBeCancelled() {
        assertFalse(DriverOrderCancellationPolicy.canCancel("on_delivery"));
        assertFalse(DriverOrderCancellationPolicy.canCancel("arrived_delivery"));
        assertFalse(DriverOrderCancellationPolicy.canCancel("finished"));
    }

    @Test
    public void normalizationIsStable() {
        assertEquals("driver_accepted", DriverOrderCancellationPolicy.normalize(" Driver-Accepted "));
    }
}
