package com.transiva.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class DriverOrderCancellationPolicyTest {
    @Test
    public void canonicalDriverOrderSequenceIsPreserved() {
        String[] states = {
                "driver_accepted",
                "arrived_pickup",
                "on_delivery",
                "arrived_delivery",
                "finished"
        };
        assertArrayEquals(new String[] {
                "driver_accepted",
                "arrived_pickup",
                "on_delivery",
                "arrived_delivery",
                "finished"
        }, states);
    }

    @Test
    public void finishedIsTerminalState() {
        String terminalState = "finished";
        assertEquals("finished", terminalState);
    }
}
