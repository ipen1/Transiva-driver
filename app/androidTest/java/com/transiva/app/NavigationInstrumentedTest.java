package com.transiva.app;

import static org.junit.Assert.*;
import android.view.View;
import android.widget.LinearLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.transiva.app.driver.ui.DriverBottomNavigation;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class NavigationInstrumentedTest {
    @Test public void bottom_navigation_has_five_stable_destinations_and_survives_recreate() {
        try(ActivityScenario<TestHarnessActivity> s=ActivityScenario.launch(TestHarnessActivity.class)){
            s.onActivity(a -> {
                View v=DriverBottomNavigation.build(a, DriverBottomNavigation.ActiveItem.HOME);
                assertTrue(v instanceof LinearLayout);
                assertEquals(5, ((LinearLayout)v).getChildCount());
            });
            s.recreate();
        }
    }
}
