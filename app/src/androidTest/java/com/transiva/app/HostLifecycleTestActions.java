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
        final int hostTaskId = original.get().getTaskId();
        // ActivityScenario.launch can return while the host is still stopped or transitioning.
        // Establish a genuine foreground baseline before sending Home.
        Lifecycle.State initial = pollForState(scenario, Lifecycle.State.RESUMED, 1500L);
        if (initial != Lifecycle.State.RESUMED) {
            if (initial == Lifecycle.State.DESTROYED) {
                throw new AssertionError("Original host was destroyed before the background transition");
            }
            focusOriginalTask(instrumentation, hostTaskId);
            awaitState(scenario, Lifecycle.State.RESUMED);
        }
        verifyOriginalHost(scenario, original.get(), hostTaskId);
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
        focusOriginalTask(instrumentation, hostTaskId);
        awaitState(scenario, Lifecycle.State.RESUMED);
        verifyOriginalHost(scenario, original.get(), hostTaskId);
    }

    private static void focusOriginalTask(Instrumentation instrumentation, int hostTaskId) {
        instrumentation.waitForIdleSync();
        ActivityManager manager = (ActivityManager) instrumentation.getTargetContext()
                .getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) throw new AssertionError("ActivityManager is unavailable");
        for (ActivityManager.AppTask task : manager.getAppTasks()) {
            ActivityManager.RecentTaskInfo info = task.getTaskInfo();
            if (info == null) continue;
            int taskId = Build.VERSION.SDK_INT >= 29 ? info.taskId : info.id;
            if (taskId == hostTaskId) {
                // Restore the actual ActivityScenario task; do not resolve another task by Intent.
                if (Build.VERSION.SDK_INT >= 29) {
                    // Execute as the emulator shell, rather than as the background app UID.
                    // Focus the original task without launching or recreating an Activity.
                    String command = "am task focus " + hostTaskId;
                    String result = runShell(instrumentation, command);
                    android.util.Log.i("HostLifecycleTest", command + " -> " + result);
                    if (!result.contains("Setting focus to task " + hostTaskId)) {
                        throw new AssertionError("Task-focus command failed on API " + Build.VERSION.SDK_INT + ": " + result);
                    }
                } else {
                    // Older emulators can leave moveToFront() in STARTED without
                    // resuming the host. Deliver an explicit foreground request
                    // inside this exact task, reusing its existing top instance.
                    Intent resume = new Intent(instrumentation.getTargetContext(), TestHarnessActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    instrumentation.runOnMainSync(() -> task.startActivity(
                            instrumentation.getTargetContext(), resume, null));
                }
                return;
            }
        }
        throw new AssertionError("Original host task not found: " + hostTaskId);
    }

    private static void verifyOriginalHost(ActivityScenario<TestHarnessActivity> scenario,
                                           TestHarnessActivity original, int hostTaskId) {
        scenario.onActivity(activity -> {
            assertSame("Foreground must restore the same host instance", original, activity);
            assertEquals("Foreground must restore the original host task", hostTaskId, activity.getTaskId());
            assertFalse(activity.isFinishing());
            assertFalse(activity.isDestroyed());
        });
    }

    private static String runShell(Instrumentation instrumentation, String command) {
        try {
            ParcelFileDescriptor descriptor = instrumentation.getUiAutomation().executeShellCommand(command);
            if (descriptor == null) throw new IOException("Shell returned no descriptor");
            try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    if (output.size() < 262144) output.write(buffer, 0, Math.min(length, 262144 - output.size()));
                }
                return output.toString("UTF-8");
            }
        } catch (IOException error) {
            throw new AssertionError("Lifecycle test shell command failed: " + command, error);
        }
    }

    private static Lifecycle.State pollForState(ActivityScenario<TestHarnessActivity> scenario,
                                                 Lifecycle.State expected, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        Lifecycle.State last = null;
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                last = scenario.getState();
                if (last == expected || last == Lifecycle.State.DESTROYED) return last;
            } catch (IllegalStateException transitionInProgress) {
                // ActivityScenario may briefly have no steady state between callbacks.
            } catch (NullPointerException transitionInProgress) {
                // ActivityScenario 1.6.x can expose the same transient gap as a
                // checkNotNull NPE while STARTED is being mapped to a steady state.
                // Only this exact framework race is retryable; unrelated NPEs fail.
                String message = transitionInProgress.getMessage();
                if (message == null || !message.contains("Could not get current state of activity")
                        || !message.contains("transition is incomplete")) throw transitionInProgress;
            }
            SystemClock.sleep(50L);
        }
        return last;
    }

    private static void awaitState(ActivityScenario<TestHarnessActivity> scenario,
                                   Lifecycle.State expected) {
        Lifecycle.State last = pollForState(scenario, expected, 20000L);
        if (last == expected) return;
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        String activityDump = runShell(instrumentation, "dumpsys activity activities");
        android.util.Log.e("HostLifecycleTest", "Transition " + last + " -> " + expected + " failed\n"
                + activityDump + "\n" + runShell(instrumentation, "dumpsys window windows"));
        StringBuilder summary = new StringBuilder();
        for (String line : activityDump.split("\n")) {
            if (line.contains("mResumedActivity") || line.contains("mFocusedStack")
                    || line.contains("mTopResumedActivity") || line.contains("Hist #")) {
                if (summary.length() < 3000) summary.append("\n").append(line.trim());
            }
        }
        String diagnosis = summary.toString();
        assertEquals("Host lifecycle transition timed out" + diagnosis, expected, last);
    }
}
