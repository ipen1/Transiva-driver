package com.transiva.app;
import android.app.*;
import android.content.*;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.filters.SdkSuppress;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
/** Notification rendering only: no production login, FCM send, or permission changes. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion=26)
@androidx.annotation.RequiresApi(26)
public class IncomingCallNotificationTest {
    private Intent call(Context c,String id){return new Intent(c,WebRtcCallActivity.class).putExtra("incoming",true).putExtra("call_id",id).putExtra("caller_name","CI caller").putExtra("call_type","video").putExtra(IncomingCallNotifier.DEADLINE,SystemClock.elapsedRealtime()+10000);}
    @Test public void silentCallHasActionsAndBoundedTimeout(){Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();IncomingCallNotifier.channels(c);Notification n=IncomingCallNotifier.build(c,call(c,"ci-render"),false);assertEquals(Notification.CATEGORY_CALL,n.category);assertNotNull(n.contentIntent);assertNotNull(n.actions);assertTrue(n.actions.length>=2);assertTrue(n.getTimeoutAfter()>0&&n.getTimeoutAfter()<=10000);assertEquals(0,n.flags&Notification.FLAG_INSISTENT);}
    @Test public void fallbackSoundIsOwnedBySystem(){Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();IncomingCallNotifier.channels(c);Notification n=IncomingCallNotifier.build(c,call(c,"ci-fallback"),true);assertTrue((n.flags&Notification.FLAG_INSISTENT)!=0);assertTrue(n.getTimeoutAfter()<=10000);}
    @Test public void sameCallKeepsOneContentIntent(){Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();IncomingCallNotifier.channels(c);Notification a=IncomingCallNotifier.build(c,call(c,"ci-same"),false),b=IncomingCallNotifier.build(c,call(c,"ci-same"),false);assertEquals(a.contentIntent,b.contentIntent);}
    @Test public void differentCallsHaveDifferentContentIntents(){Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();IncomingCallNotifier.channels(c);Notification a=IncomingCallNotifier.build(c,call(c,"ci-a"),false),b=IncomingCallNotifier.build(c,call(c,"ci-b"),false);assertNotEquals(a.contentIntent,b.contentIntent);}
}
