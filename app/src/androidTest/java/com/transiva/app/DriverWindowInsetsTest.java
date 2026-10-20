package com.transiva.app;

import android.content.Intent;
import android.os.Build;
import android.view.View;
import android.widget.FrameLayout;
import androidx.core.graphics.Insets;
import androidx.core.view.OnApplyWindowInsetsListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Compat policy runs on every API; native typed IME dispatch requires Android 11+. */
@RunWith(AndroidJUnit4.class)
public class DriverWindowInsetsTest {
    private static final int BARS = WindowInsetsCompat.Type.systemBars();
    private static final int IME = WindowInsetsCompat.Type.ime();

    private void assertNoRemainingInsets(WindowInsetsCompat remaining) {
        assertNotNull(remaining);
        assertTrue("The padded parent must fully consume platform insets", remaining.isConsumed());
        if (Build.VERSION.SDK_INT >= 30) {
            assertEquals(Insets.NONE, remaining.getInsets(BARS | IME | WindowInsetsCompat.Type.displayCutout()));
        } else {
            // A native round trip loses synthetic typed IME on API <=29. AndroidX
            // can infer it again from the real root visible frame even on a consumed
            // wrapper. Verify the actual legacy dispatch contract instead.
            assertEquals(Insets.NONE, remaining.getSystemWindowInsets());
            assertEquals(Insets.NONE, remaining.getStableInsets());
        }
    }

    private void assertDispatchCompleted(WindowInsetsCompat remaining, int childCalls) {
        assertNoRemainingInsets(remaining);
        // No descendant should receive another inset application after root padding.
        assertEquals("Consumed root must stop descendant dispatch", 0, childCalls);
    }

    private ActivityScenario<TestHarnessActivity> launch() {
        return ActivityScenario.launch(new Intent(ApplicationProvider.getApplicationContext(), TestHarnessActivity.class));
    }

    private FrameLayout host(TestHarnessActivity activity) {
        FrameLayout child = new FrameLayout(activity);
        child.setPadding(12,12,12,12);
        activity.setContentView(child);
        DriverResponsiveUi.apply(activity);
        return child;
    }

    private WindowInsetsCompat insets(int keyboardHeight) {
        return new WindowInsetsCompat.Builder()
                .setInsets(BARS, Insets.of(0,24,0,48))
                .setInsets(IME, Insets.of(0,0,0,keyboardHeight))
                .setVisible(IME, keyboardHeight > 0).build();
    }

    @Test public void barsAndKeyboardAreAppliedOnceAndRestoreWithoutAccumulation() {
        try (ActivityScenario<TestHarnessActivity> scenario = launch()) {
            scenario.onActivity(activity -> {
                FrameLayout child = host(activity);
                View content = activity.findViewById(android.R.id.content);
                // Invoke the actual production listener before the framework round trip.
                // API <=29 cannot serialize a separate IME override into WindowInsets.
                OnApplyWindowInsetsListener listener = DriverResponsiveUi.createInsetsListener(activity, new int[]{0,0,0,0});
                WindowInsetsCompat keyboard = insets(300);
                assertEquals(300, keyboard.getInsets(IME).bottom);
                WindowInsetsCompat remaining = listener.onApplyWindowInsets(content, keyboard);
                assertEquals(24, content.getPaddingTop());
                assertEquals(300, content.getPaddingBottom());
                assertTrue(remaining.isConsumed());
                assertEquals(0, remaining.getInsets(BARS | IME).bottom);
                assertEquals(0, remaining.getInsets(BARS).top);
                assertEquals(12, child.getPaddingBottom());
                DriverResponsiveUi.apply(activity);
                listener.onApplyWindowInsets(content, keyboard);
                assertEquals(300, content.getPaddingBottom());
                listener.onApplyWindowInsets(content, insets(0));
                assertEquals(48, content.getPaddingBottom());
                assertEquals(24, content.getPaddingTop());
                listener.onApplyWindowInsets(content, keyboard);
                assertEquals(300, content.getPaddingBottom());
                assertEquals(12, child.getPaddingBottom());
            });
        }
    }

    @Test public void frameworkDispatchConsumesSystemBarsOnEveryApi() {
        try (ActivityScenario<TestHarnessActivity> scenario = launch()) {
            scenario.onActivity(activity -> {
                FrameLayout child = host(activity);
                View content = activity.findViewById(android.R.id.content);
                // Legacy native dispatch can infer the real root IME after losing
                // our synthetic typed override. Capture the actual listener input
                // rather than asserting that an unsupported override survived.
                final int[] nativeBottom = {0};
                OnApplyWindowInsetsListener production = DriverResponsiveUi.createInsetsListener(activity, new int[]{0,0,0,0});
                ViewCompat.setOnApplyWindowInsetsListener(content, (v, applied) -> {
                    nativeBottom[0] = Math.max(applied.getInsets(BARS | WindowInsetsCompat.Type.displayCutout()).bottom,
                            applied.getInsets(IME).bottom);
                    return production.onApplyWindowInsets(v, applied);
                });
                final int[] childCalls = {0};
                ViewCompat.setOnApplyWindowInsetsListener(child, (v, applied) -> {
                    childCalls[0]++;
                    assertNoRemainingInsets(applied);
                    return applied;
                });
                childCalls[0] = 0;
                WindowInsetsCompat dispatched = ViewCompat.dispatchApplyWindowInsets(content, insets(0));
                assertDispatchCompleted(dispatched, childCalls[0]);
                assertEquals(24, content.getPaddingTop());
                assertEquals(nativeBottom[0], content.getPaddingBottom());
                if (Build.VERSION.SDK_INT >= 30) assertEquals(48, nativeBottom[0]);
                assertEquals(12, child.getPaddingBottom());
                DriverResponsiveUi.apply(activity);
                childCalls[0] = 0;
                dispatched = ViewCompat.dispatchApplyWindowInsets(content, insets(0));
                assertDispatchCompleted(dispatched, childCalls[0]);
                assertEquals(nativeBottom[0], content.getPaddingBottom());
                if (Build.VERSION.SDK_INT >= 30) assertEquals(48, nativeBottom[0]);
            });
        }
    }

    @Test @SdkSuppress(minSdkVersion = 30)
    public void frameworkDispatchPreservesTypedImeOnAndroid11AndLater() {
        try (ActivityScenario<TestHarnessActivity> scenario = launch()) {
            scenario.onActivity(activity -> {
                FrameLayout child = host(activity);
                View content = activity.findViewById(android.R.id.content);
                final int[] childCalls = {0};
                ViewCompat.setOnApplyWindowInsetsListener(child, (v, applied) -> {
                    childCalls[0]++;
                    assertNoRemainingInsets(applied);
                    return applied;
                });
                childCalls[0] = 0;
                WindowInsetsCompat dispatched = ViewCompat.dispatchApplyWindowInsets(content, insets(300));
                assertDispatchCompleted(dispatched, childCalls[0]);
                assertEquals(300, content.getPaddingBottom());
                assertEquals(12, child.getPaddingBottom());
                childCalls[0] = 0;
                dispatched = ViewCompat.dispatchApplyWindowInsets(content, insets(0));
                assertDispatchCompleted(dispatched, childCalls[0]);
                assertEquals(48, content.getPaddingBottom());
            });
        }
    }
}
