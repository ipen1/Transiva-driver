package com.transiva.app;

import android.content.Intent;
import android.view.View;
import android.widget.FrameLayout;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Runs in the debug target only; no account, location, FCM or media session is needed. */
@RunWith(AndroidJUnit4.class)
public class DriverWindowInsetsTest {
    @Test public void barsAndKeyboardAreAppliedOnceAndRestoreWithoutAccumulation() {
        Intent intent = new Intent(ApplicationProvider.getApplicationContext(), TestHarnessActivity.class);
        try (ActivityScenario<TestHarnessActivity> scenario = ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> {
                FrameLayout child = new FrameLayout(activity);
                child.setPadding(12,12,12,12);
                activity.setContentView(child);
                DriverResponsiveUi.apply(activity);
                View content = activity.findViewById(android.R.id.content);
                final int[] childBottom = {-1};
                ViewCompat.setOnApplyWindowInsetsListener(child, (v,insets) -> {
                    childBottom[0] = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.ime()).bottom;
                    return insets;
                });
                WindowInsetsCompat keyboard = new WindowInsetsCompat.Builder()
                        .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0,24,0,48))
                        .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0,0,0,300))
                        .setVisible(WindowInsetsCompat.Type.ime(), true).build();
                ViewCompat.dispatchApplyWindowInsets(content, keyboard);
                assertEquals(24, content.getPaddingTop());
                assertEquals(300, content.getPaddingBottom());
                assertEquals(0, childBottom[0]);
                assertEquals(12, child.getPaddingBottom());
                DriverResponsiveUi.apply(activity);
                ViewCompat.dispatchApplyWindowInsets(content, keyboard);
                assertEquals(300, content.getPaddingBottom());
                WindowInsetsCompat closed = new WindowInsetsCompat.Builder(keyboard)
                        .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE)
                        .setVisible(WindowInsetsCompat.Type.ime(), false).build();
                ViewCompat.dispatchApplyWindowInsets(content, closed);
                assertEquals(48, content.getPaddingBottom());
                assertEquals(24, content.getPaddingTop());
            });
        }
    }
}
