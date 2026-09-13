package com.transiva.app;

import static org.junit.Assert.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class RichNotificationInstrumentedTest {
    @Test public void rich_type_is_explicit_and_never_matches_order_or_call() {
        assertTrue(TransivaRichNotificationManager.isRichType("transiva_rich"));
        assertTrue(TransivaRichNotificationManager.isRichType("rich_notification"));
        assertFalse(TransivaRichNotificationManager.isRichType("transiva_order"));
        assertFalse(TransivaRichNotificationManager.isRichType("webrtc_call"));
        assertFalse(TransivaRichNotificationManager.isRichType("driver_chat"));
    }
}
