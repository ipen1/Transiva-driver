package com.transiva.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class RichNotificationInstrumentedTest {
    @Test
    public void recognizesRichNotificationTypesOnly() {
        assertTrue(TransivaRichNotificationManager.isRichType("transiva_rich"));
        assertTrue(TransivaRichNotificationManager.isRichType("rich_notification"));
        assertTrue(TransivaRichNotificationManager.isRichType("  TRANSIVA_RICH  "));
        assertFalse(TransivaRichNotificationManager.isRichType("promo"));
        assertFalse(TransivaRichNotificationManager.isRichType("order"));
        assertFalse(TransivaRichNotificationManager.isRichType("call"));
        assertFalse(TransivaRichNotificationManager.isRichType("chat"));
        assertFalse(TransivaRichNotificationManager.isRichType(null));
    }
}
