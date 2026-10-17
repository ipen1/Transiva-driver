package com.transiva.app;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class PushExpiryPolicyTest {
    private Map<String,String> data(String... values){Map<String,String> d=new HashMap<>();for(int n=0;n<values.length;n+=2)d.put(values[n],values[n+1]);return d;}
    @Test public void expiredCallCannotRestartRinging(){assertEquals(0,PushExpiryPolicy.remainingMillis(data("call_expires_epoch","1"),2000,true));}
    @Test public void lateCallUsesOnlyRemainingTime(){assertEquals(7000,PushExpiryPolicy.remainingMillis(data("call_expires_epoch","10"),3000,true));}
    @Test public void invalidCallDeadlineIsRejected(){assertEquals(0,PushExpiryPolicy.remainingMillis(data("call_expires_epoch","bad"),3000,true));}
    @Test public void overflowingCallDeadlineIsRejected(){assertEquals(0,PushExpiryPolicy.remainingMillis(data("call_expires_epoch",Long.toString(Long.MAX_VALUE)),3000,true));}
    @Test public void callCannotRingLongerThan45Seconds(){assertEquals(45000,PushExpiryPolicy.remainingMillis(data("call_expires_epoch","9999"),3000,true));}
    @Test public void legacyCallHasBoundedFallback(){assertEquals(45000,PushExpiryPolicy.remainingMillis(data(),3000,true));}
    @Test public void expiredOfferIsDropped(){assertTrue(PushExpiryPolicy.expiredOrder(data("event","new_order","offer_expires_epoch","1"),3000));}
    @Test public void liveOfferRemainsVisible(){assertFalse(PushExpiryPolicy.expiredOrder(data("event","new_order","offer_expires_epoch","10"),3000));}
    @Test public void malformedOfferDateIsDropped(){assertTrue(PushExpiryPolicy.expiredOrder(data("event","new_order","offer_expired_at","bad"),3000));}
    @Test public void legacyOfferDateUsesServerTimezone(){assertEquals(1000,PushExpiryPolicy.remainingMillis(data("offer_expired_at","1970-01-01 08:00:10"),9000,false));}
    @Test public void statusUpdateWithoutOfferDeadlineIsPreserved(){assertFalse(PushExpiryPolicy.expiredOrder(data("event","driver_accepted"),3000));}
}
