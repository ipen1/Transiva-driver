package com.transiva.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.res.Configuration;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.lifecycle.Lifecycle;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class LifecycleThemeInstrumentedTest {
    @Test public void activity_survives_background_foreground_and_recreate() {
        try (ActivityScenario<TestHarnessActivity> s = ActivityScenario.launch(TestHarnessActivity.class)) {
            s.moveToState(Lifecycle.State.CREATED);
            s.moveToState(Lifecycle.State.RESUMED);
            s.recreate();
            s.onActivity(a -> assertFalse(a.isFinishing()));
        }
    }

    @Test public void semantic_tokens_change_between_light_and_dark_configuration() {
        Context base = ApplicationProvider.getApplicationContext();
        Configuration light = new Configuration(base.getResources().getConfiguration());
        light.uiMode = (light.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_NO;
        Configuration dark = new Configuration(base.getResources().getConfiguration());
        dark.uiMode = (dark.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_YES;
        Context lc = base.createConfigurationContext(light);
        Context dc = base.createConfigurationContext(dark);
        assertNotEquals(DriverThemeTokens.background(lc), DriverThemeTokens.background(dc));
        assertNotEquals(DriverThemeTokens.textPrimary(lc), DriverThemeTokens.textPrimary(dc));
    }
}
