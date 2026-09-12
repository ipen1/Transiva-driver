package com.transiva.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DriverMessageStatusTest {

    @Test
    public void pendingOrderCannotSendChat() {
        assertFalse(DriverMessageStatus.canSend("pending"));
        assertEquals("Chat tersedia setelah order diterima",
                DriverMessageStatus.availabilityLabel("pending", false));
    }

    @Test
    public void acceptedAndActiveTripStatusesCanSendChat() {
        assertTrue(DriverMessageStatus.canSend("driver_accepted"));
        assertTrue(DriverMessageStatus.canSend("arrived_pickup"));
        assertTrue(DriverMessageStatus.canSend("on_delivery"));
        assertTrue(DriverMessageStatus.canSend("arrived_delivery"));
    }

    @Test
    public void completedAndCancelledOrdersAreReadOnly() {
        assertTrue(DriverMessageStatus.isEnded("completed"));
        assertTrue(DriverMessageStatus.isEnded("cancelled"));
        assertFalse(DriverMessageStatus.canSend("completed"));
        assertEquals("Riwayat • hanya dapat dibaca",
                DriverMessageStatus.availabilityLabel("cancelled", false));
    }

    @Test
    public void statusNormalizationHandlesSpacesAndHyphens() {
        assertEquals("driver_accepted", DriverMessageStatus.normalize(" Driver-Accepted "));
        assertEquals("arrived_pickup", DriverMessageStatus.normalize("arrived pickup"));
    }

    @Test
    public void foodPendingLabelWaitsForMerchant() {
        assertEquals("Menunggu Merchant",
                DriverMessageStatus.orderLabel("pending", "TransFood"));
    }

    @Test
    public void ridePendingLabelWaitsForDriver() {
        assertEquals("Menunggu Driver",
                DriverMessageStatus.orderLabel("pending", "TransRide"));
    }
}
