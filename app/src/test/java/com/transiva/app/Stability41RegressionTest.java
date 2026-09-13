package com.transiva.app;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

/** Stability 4.1 / WebRTC Regression 2.0 / FCM session hardening invariants. */
public class Stability41RegressionTest {
    private static Map<String,String> data(String... kv) {
        Map<String,String> m = new HashMap<>();
        for (int i=0;i+1<kv.length;i+=2) m.put(kv[i],kv[i+1]);
        return m;
    }

    @Test public void endpointDoesNotDuplicateSlash(){
        assertEquals("https://transiva.my.id/server/login.php", DriverApiConfig.endpoint("/login.php"));
    }
    @Test public void endpointHandlesNull(){ assertEquals(DriverApiConfig.BASE_URL, DriverApiConfig.endpoint(null)); }
    @Test public void fcmMessageIdWinsDedupeKey(){ assertEquals("abc", DriverFcmPolicy.dedupeKey(" abc ", data("type","x"))); }
    @Test public void fcmFallbackDedupeKeyIsStable(){
        Map<String,String> d=data("type","webrtc_call","event","incoming_call","call_id","7");
        assertEquals(DriverFcmPolicy.dedupeKey(null,d), DriverFcmPolicy.dedupeKey("",d));
    }
    @Test public void forceLogoutIsTerminal(){ assertTrue(DriverFcmPolicy.isTerminalSessionEvent("force_logout", data())); }
    @Test public void deviceResetIsTerminal(){ assertTrue(DriverFcmPolicy.isTerminalSessionEvent("device_reset", data())); }
    @Test public void forceLogoutFlagIsTerminal(){ assertTrue(DriverFcmPolicy.isTerminalSessionEvent("general", data("force_logout","1"))); }
    @Test public void normalOrderIsNotTerminal(){ assertFalse(DriverFcmPolicy.isTerminalSessionEvent("new_order", data())); }
    @Test public void onlyIncomingCallMayLaunchCallUi(){
        assertTrue(DriverFcmPolicy.shouldLaunchIncomingCall("webrtc_call", data("event","incoming_call","call_id","99")));
        assertFalse(DriverFcmPolicy.shouldLaunchIncomingCall("webrtc_call", data("event","accepted","call_id","99")));
    }
    @Test public void incomingCallRequiresCallId(){ assertFalse(DriverFcmPolicy.shouldLaunchIncomingCall("webrtc_call", data("event","incoming_call"))); }
    @Test public void callEndedIsTerminal(){ assertTrue(DriverFcmPolicy.isCallTerminalEvent(data("event","call_ended"))); }
    @Test public void callRejectedIsTerminal(){ assertTrue(DriverFcmPolicy.isCallTerminalEvent(data("event","rejected"))); }
    @Test public void sdpUpdateIsNotTerminal(){ assertFalse(DriverFcmPolicy.isCallTerminalEvent(data("event","candidate"))); }
    @Test public void differentActiveCallIsRejected(){ assertFalse(WebRtcCallPolicy.sameCall("A","B")); }
    @Test public void sameActiveCallIsAllowed(){ assertTrue(WebRtcCallPolicy.sameCall("A","A")); }
    @Test public void missingNewCallIdDoesNotMutateActiveCall(){ assertTrue(WebRtcCallPolicy.sameCall("A","")); }
    @Test public void incomingRoleRemainsImmutableForActiveCall(){ assertFalse(WebRtcCallPolicy.immutableIncoming(true,false,true)); }
    @Test public void firstIntentDefinesIncomingRole(){ assertTrue(WebRtcCallPolicy.immutableIncoming(false,false,true)); }
    @Test public void autoAcceptNeedsLiveIncomingCall(){ assertTrue(WebRtcCallPolicy.mayAutoAccept(true,false,false,"C1")); }
    @Test public void autoAcceptBlockedAfterEnd(){ assertFalse(WebRtcCallPolicy.mayAutoAccept(true,false,true,"C1")); }
    @Test public void outgoingExistingCallMayResume(){ assertTrue(WebRtcCallPolicy.shouldResume(false,false,"C1")); }
    @Test public void unansweredIncomingCallMustNotResumeMedia(){ assertFalse(WebRtcCallPolicy.shouldResume(true,false,"C1")); }
    @Test public void acceptedIncomingCallMayResumeMedia(){ assertTrue(WebRtcCallPolicy.shouldResume(true,true,"C1")); }
}
