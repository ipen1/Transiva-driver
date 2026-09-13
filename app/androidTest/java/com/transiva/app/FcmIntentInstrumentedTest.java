package com.transiva.app;

import static org.junit.Assert.*;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class FcmIntentInstrumentedTest {
    @Test public void duplicate_push_is_suppressed_on_second_delivery() {
        Map<String,String> d = new HashMap<>(); d.put("type","order"); d.put("order_id","matrix-"+System.nanoTime());
        String id = "fcm-"+System.nanoTime();
        assertFalse(DriverFcmDeduplicator.isDuplicate(ApplicationProvider.getApplicationContext(), id, d));
        assertTrue(DriverFcmDeduplicator.isDuplicate(ApplicationProvider.getApplicationContext(), id, d));
    }

    @Test public void incoming_call_requires_event_and_call_id() {
        Map<String,String> d = new HashMap<>(); d.put("event","incoming_call"); d.put("call_id","call-1");
        assertTrue(DriverFcmPolicy.shouldLaunchIncomingCall("webrtc_call", d));
        d.remove("call_id");
        assertFalse(DriverFcmPolicy.shouldLaunchIncomingCall("webrtc_call", d));
    }
}
