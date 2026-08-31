package com.transiva.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class DriverOrderCancellationPolicyTest {
    @Test
    public void normalizeKeepsDatabaseStatusContract() {
        assertEquals("driver_accepted", DriverOrderCancellationPolicy.normalize(" Driver Accepted "));
        assertEquals("arrived_pickup", DriverOrderCancellationPolicy.normalize("arrived-pickup"));
    }

    @Test
    public void cancellationOnlyAllowedBeforeDeliveryStarts() {
        assertTrue(DriverOrderCancellationPolicy.canCancel("driver_accepted"));
        assertTrue(DriverOrderCancellationPolicy.canCancel("arrived_pickup"));
        assertFalse(DriverOrderCancellationPolicy.canCancel("on_delivery"));
        assertFalse(DriverOrderCancellationPolicy.canCancel("arrived_delivery"));
        assertFalse(DriverOrderCancellationPolicy.canCancel("finished"));
    }

    @Test
    public void reasonsAreNonEmpty() {
        String[] reasons = DriverOrderCancellationPolicy.reasons();
        assertTrue(reasons.length >= 4);
        for (String reason : reasons) {
            assertNotNull(reason);
            assertFalse(reason.trim().isEmpty());
        }
    }
}
