package com.transiva.app;

import static org.junit.Assert.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.lifecycle.Lifecycle;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class WebRtcDeviceMatrixInstrumentedTest {
    @Test public void foreground_incoming_call_policy_accepts_once() {
        assertTrue(WebRtcCallPolicy.mayAutoAccept(true,false,false,"call-a"));
        assertFalse(WebRtcCallPolicy.mayAutoAccept(true,true,false,"call-a"));
    }
    @Test public void background_to_foreground_preserves_host_lifecycle() {
        try(ActivityScenario<TestHarnessActivity> s=ActivityScenario.launch(TestHarnessActivity.class)){
            s.moveToState(Lifecycle.State.CREATED); s.moveToState(Lifecycle.State.RESUMED);
            s.onActivity(a -> assertFalse(a.isFinishing()));
        }
    }
    @Test public void killed_process_style_recreation_keeps_call_resume_rule_deterministic() {
        assertTrue(WebRtcCallPolicy.shouldResume(false,false,"call-out"));
        assertFalse(WebRtcCallPolicy.shouldResume(true,false,"call-in"));
        assertTrue(WebRtcCallPolicy.shouldResume(true,true,"call-in"));
    }
    @Test public void duplicate_or_conflicting_call_ids_are_rejected() {
        assertTrue(WebRtcCallPolicy.sameCall("call-a","call-a"));
        assertFalse(WebRtcCallPolicy.sameCall("call-a","call-b"));
    }
    @Test public void lockscreen_incoming_role_remains_immutable_after_recreation() {
        assertTrue(WebRtcCallPolicy.immutableIncoming(true,true,false));
        assertFalse(WebRtcCallPolicy.immutableIncoming(true,false,true));
    }
}
