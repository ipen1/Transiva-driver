package com.transiva.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

import android.app.Instrumentation;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;

/** Exercise a real task background transition without an intermediate test activity. */
final class HostLifecycleTestActions {
    private HostLifecycleTestActions() {}

    static void backgroundAndReturn(ActivityScenario<TestHarnessActivity> scenario) {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        AtomicReference<TestHarnessActivity> original = new AtomicReference<>();
        scenario.onActivity(original::set);
        assertEquals(Lifecycle.State.RESUMED, scenario.getState());
        try {
            ParcelFileDescriptor descriptor = instrumentation.getUiAutomation()
                    .executeShellCommand("input keyevent KEYCODE_HOME");
            if (descriptor == null) throw new IOException("Home command returned no descriptor");
            try (InputStream output = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
                byte[] buffer = new byte[256];
                while (output.read(buffer) != -1) { /* Wait for the input command to finish. */ }
            }
        } catch (IOException error) {
            throw new AssertionError("Could not send the host task to background", error);
        }
        awaitState(scenario, Lifecycle.State.CREATED);
        if (Build.VERSION.SDK_INT < 31) {
        ActivityManager manager = (ActivityManager) instrumentation.getTargetContext()
                .getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) throw new AssertionError("ActivityManager is unavailable");
        int hostTaskId = original.get().getTaskId();
        boolean restored = false;
        for (ActivityManager.AppTask task : manager.getAppTasks()) {
            ActivityManager.RecentTaskInfo info = task.getTaskInfo();
            if (info == null) continue;
            int taskId = Build.VERSION.SDK_INT >= 29 ? info.taskId : info.id;
            if (taskId == hostTaskId) {
                // Restore the actual ActivityScenario task; do not resolve another task by Intent.
                task.moveToFront();
                restored = true;
                break;
            }
        }
        if (!restored) throw new AssertionError("Original host task not found: " + hostTaskId);
        } else {
            Intent foreground = new Intent(instrumentation.getTargetContext(), TestHarnessActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            instrumentation.getTargetContext().startActivity(foreground);
        }
        awaitState(scenario, Lifecycle.State.RESUMED);
        scenario.onActivity(activity -> {
            assertSame("Foreground must restore the same host instance", original.get(), activity);
            assertFalse(activity.isFinishing());
            assertFalse(activity.isDestroyed());
        });
        original.set(null);
    }

    private static void awaitState(ActivityScenario<TestHarnessActivity> scenario,
                                   Lifecycle.State expected) {
        long deadline = SystemClock.elapsedRealtime() + 20000L;
        Lifecycle.State last = null;
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                last = scenario.getState();
                if (last == expected) return;
                if (last == Lifecycle.State.DESTROYED) break;
            } catch (IllegalStateException transitionInProgress) {
                // ActivityScenario may briefly have no steady state between callbacks.
            }
            SystemClock.sleep(50L);
        }
        assertEquals("Host lifecycle transition timed out", expected, last);
    }
}
