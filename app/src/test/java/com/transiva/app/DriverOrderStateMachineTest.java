package com.transiva.app;

import org.junit.Test;

import static org.junit.Assert.*;

public class DriverOrderStateMachineTest {
    @Test public void preservesCanonicalOperationalSequence() {
        assertEquals("taken", DriverOrderStateMachine.normalize("driver_accepted"));
        assertEquals("arrived_pickup", DriverOrderStateMachine.normalize("arrived_pickup"));
        assertEquals("on_delivery", DriverOrderStateMachine.normalize("on_delivery"));
        assertEquals("arrived_delivery", DriverOrderStateMachine.normalize("arrived_delivery"));
        assertEquals("finished", DriverOrderStateMachine.normalize("finished"));
    }

    @Test public void legacyAliasesNormalizeWithoutChangingCoreVocabulary() {
        assertEquals("taken", DriverOrderStateMachine.normalize("assigned"));
        assertEquals("arrived_pickup", DriverOrderStateMachine.normalize("pickup_arrived"));
        assertEquals("on_delivery", DriverOrderStateMachine.normalize("picked_up"));
        assertEquals("arrived_delivery", DriverOrderStateMachine.normalize("delivery_arrived"));
        assertEquals("finished", DriverOrderStateMachine.normalize("completed"));
    }

    @Test public void endpointsRemainStableForMainOrder() {
        assertEquals("driverArrivedPickup.php", DriverOrderStateMachine.endpoint("arrived_pickup", false));
        assertEquals("driverStartDelivery.php", DriverOrderStateMachine.endpoint("on_delivery", false));
        assertEquals("driverArrivedDelivery.php", DriverOrderStateMachine.endpoint("arrived_delivery", false));
        assertEquals("finishOrder.php", DriverOrderStateMachine.endpoint("finished", false));
    }

    @Test public void onlyForwardOperationalTransitionsAreAllowed() {
        assertTrue(DriverOrderStateMachine.canTransition("driver_accepted", "arrived_pickup"));
        assertTrue(DriverOrderStateMachine.canTransition("arrived_pickup", "on_delivery"));
        assertTrue(DriverOrderStateMachine.canTransition("on_delivery", "arrived_delivery"));
        assertTrue(DriverOrderStateMachine.canTransition("arrived_delivery", "finished"));
        assertFalse(DriverOrderStateMachine.canTransition("driver_accepted", "finished"));
        assertFalse(DriverOrderStateMachine.canTransition("on_delivery", "arrived_pickup"));
        assertFalse(DriverOrderStateMachine.canTransition("finished", "arrived_delivery"));
    }

    @Test public void pickupUsesUnifiedEndpointForEveryStage() {
        assertEquals("driver_update_unified_status.php", DriverOrderStateMachine.endpoint("arrived_pickup", true));
        assertEquals("driver_update_unified_status.php", DriverOrderStateMachine.endpoint("finished", true));
    }
}
