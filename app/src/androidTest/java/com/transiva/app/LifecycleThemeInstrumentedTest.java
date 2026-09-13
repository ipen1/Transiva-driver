package com.transiva.app;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.lifecycle.Lifecycle;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class LifecycleThemeInstrumentedTest {
    @Test public void activity_survives_background_foreground_and_recreate() {
        Context app = ApplicationProvider.getApplicationContext();
        boolean original = DriverAppSettings.isDarkMode(app);
        try {
            DriverAppSettings.setDarkMode(app, false);
            try (ActivityScenario<TestHarnessActivity> s = ActivityScenario.launch(TestHarnessActivity.class)) {
                s.moveToState(Lifecycle.State.CREATED);
                s.moveToState(Lifecycle.State.RESUMED);
                s.recreate();
                s.onActivity(a -> assertFalse(a.isFinishing()));
            }
        } finally {
            DriverAppSettings.setDarkMode(app, original);
        }
    }

    @Test public void semantic_tokens_follow_transiva_preference_not_system_configuration() {
        Context app = ApplicationProvider.getApplicationContext();
        boolean original = DriverAppSettings.isDarkMode(app);
        try {
            DriverAppSettings.setDarkMode(app, false);
            int lightBackground = DriverThemeTokens.background(app);
            int lightText = DriverThemeTokens.textPrimary(app);
            assertFalse(DriverThemeTokens.isDark(app));

            DriverAppSettings.setDarkMode(app, true);
            int darkBackground = DriverThemeTokens.background(app);
            int darkText = DriverThemeTokens.textPrimary(app);
            assertTrue(DriverThemeTokens.isDark(app));

            assertNotEquals(lightBackground, darkBackground);
            assertNotEquals(lightText, darkText);
        } finally {
            DriverAppSettings.setDarkMode(app, original);
        }
    }
}
