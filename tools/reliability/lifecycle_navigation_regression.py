#!/usr/bin/env python3
"""Host regressions for actual lifecycle helper and extracted production target-resolution methods.
Uses Android collaborators, not an emulator. JSON_JAR must point to org.json 20240303.
"""
import os,subprocess,tempfile
from pathlib import Path
root=Path(__file__).resolve().parents[2]
app=root/'app/src/main/java/com/transiva/app';test=root/'app/src/androidTest/java/com/transiva/app'
activity=(app/'DriverNavigationActivity.java').read_text()
base=(app/'DriverNavigationActivityLayer1.java').read_text()
# Execute unchanged method bodies instead of reimplementing the target-resolution algorithm.
read_order=activity[activity.index('    protected void readOrder()'):activity.index('    protected void readIdentity()')]
targets=base[base.index('    protected double targetLat()'):base.index('    protected String normalizeVehicle(')]
files={
'org/junit/Assert.java':'''package org.junit;public class Assert{public static void assertEquals(Object a,Object b){assertEquals("",a,b);}public static void assertEquals(String m,Object a,Object b){if(!java.util.Objects.equals(a,b))throw new AssertionError(m+" expected="+a+" actual="+b);}public static void assertSame(String m,Object a,Object b){if(a!=b)throw new AssertionError(m);}public static void assertFalse(boolean b){if(b)throw new AssertionError("Expected false");}}''',
'android/os/Build.java':'package android.os;public class Build{public static int SDK_INT=36;public static class VERSION{public static int SDK_INT=36;}}',
'android/os/SystemClock.java':'package android.os;public class SystemClock{public static long now;public static long elapsedRealtime(){return now;}public static void sleep(long ms){now+=ms;}}',
'android/os/ParcelFileDescriptor.java':'package android.os;public class ParcelFileDescriptor{public String text="";public static class AutoCloseInputStream extends java.io.ByteArrayInputStream{public AutoCloseInputStream(ParcelFileDescriptor p){super(p.text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}}}',
'android/content/Context.java':'package android.content;public class Context{public static final String ACTIVITY_SERVICE="activity";public Object getSystemService(String s){return new android.app.ActivityManager();}}',
'android/content/Intent.java':'package android.content;public class Intent{public static final int FLAG_ACTIVITY_REORDER_TO_FRONT=0x20000,FLAG_ACTIVITY_SINGLE_TOP=0x20000000;public int flags;public Class<?> target;public Intent(Context c,Class<?> t){target=t;}public Intent addFlags(int f){flags|=f;return this;}}',
'android/app/ActivityManager.java':'package android.app;public class ActivityManager{public static int starts;public static boolean missing;public static android.content.Intent intent;public static class RecentTaskInfo{public int id=1,taskId=1;}public static class AppTask{public RecentTaskInfo getTaskInfo(){return new RecentTaskInfo();}public void moveToFront(){throw new AssertionError("Legacy moveToFront cannot guarantee RESUMED");}public void startActivity(android.content.Context c,android.content.Intent i,Object options){if(!Instrumentation.onMain)throw new AssertionError("Not on main thread");starts++;intent=i;}}public java.util.List<AppTask> getAppTasks(){return missing?java.util.Collections.emptyList():java.util.Arrays.asList(new AppTask());}}',
'android/app/Instrumentation.java':'package android.app;public class Instrumentation{public static boolean onMain;public static int idle,focus,dumps;public void waitForIdleSync(){idle++;}public void runOnMainSync(Runnable r){onMain=true;try{r.run();}finally{onMain=false;}}public android.content.Context getTargetContext(){return new android.content.Context();}public Automation getUiAutomation(){return new Automation();}public static class Automation{public android.os.ParcelFileDescriptor executeShellCommand(String s){android.os.ParcelFileDescriptor p=new android.os.ParcelFileDescriptor();if(s.startsWith("am task focus")){focus++;p.text="Setting focus to task 1";}if(s.startsWith("dumpsys"))dumps++;return p;}}}',
'android/widget/TextView.java':'package android.widget;public class TextView{public String text;public void setText(String t){text=t;}}',
'android/util/Log.java':'package android.util;public class Log{public static int i(String a,String b){return 0;}public static int e(String a,String b){return 0;}}',
'androidx/lifecycle/Lifecycle.java':'package androidx.lifecycle;public class Lifecycle{public enum State{DESTROYED,INITIALIZED,CREATED,STARTED,RESUMED}}',
'androidx/test/platform/app/InstrumentationRegistry.java':'package androidx.test.platform.app;public class InstrumentationRegistry{public static android.app.Instrumentation getInstrumentation(){return new android.app.Instrumentation();}}',
'com/transiva/app/TestHarnessActivity.java':'package com.transiva.app;public class TestHarnessActivity{public int getTaskId(){return 1;}public boolean isFinishing(){return false;}public boolean isDestroyed(){return false;}}',
'androidx/test/core/app/ActivityScenario.java':'''package androidx.test.core.app;import java.util.*;import androidx.lifecycle.Lifecycle;public class ActivityScenario<T>{public final Deque<Object> states=new ArrayDeque<>();public Object repeat;public T activity;public ActivityScenario(T a){activity=a;}public Lifecycle.State getState(){Object x=states.isEmpty()?repeat:states.removeFirst();if(x instanceof RuntimeException)throw (RuntimeException)x;return (Lifecycle.State)x;}public void onActivity(java.util.function.Consumer<T> action){action.accept(activity);}}''',
'com/transiva/app/TransivaDiagnostics.java':'package com.transiva.app;public class TransivaDiagnostics{public static void error(Object c,String a,String b,Exception e){throw new RuntimeException(e);}}',
'com/transiva/app/NavigationDiagnostics.java':'package com.transiva.app;public class NavigationDiagnostics{public static void error(Object c,String s,Exception e){throw new RuntimeException(e);}}',
'com/transiva/app/TargetProbe.java':'''package com.transiva.app;import org.json.JSONObject;import java.util.*;
class TargetProbe{
 JSONObject order=new JSONObject();String targetMode="pickup";double driverLat,driverLng,explicitTargetLat,explicitTargetLng;
 static class Intent{final Map<String,Object> values=new HashMap<>();String getStringExtra(String k){Object v=values.get(k);return v instanceof String?(String)v:null;}double getDoubleExtra(String k,double d){Object v=values.get(k);return v instanceof Number?((Number)v).doubleValue():d;}}
 final Intent intent=new Intent();Intent getIntent(){return intent;}
 static String first(String... values){for(String s:values)if(s!=null&&!s.trim().isEmpty())return s;return "";}
 static boolean valid(double a,double b){return Double.isFinite(a)&&Double.isFinite(b)&&Math.abs(a)<=90&&Math.abs(b)<=180&&a!=0&&b!=0;}
'''+read_order+targets+'}\n',
'com/transiva/app/RegressionProbe.java':'''package com.transiva.app;
import androidx.test.core.app.ActivityScenario;import androidx.lifecycle.Lifecycle.State;import java.lang.reflect.*;
public class RegressionProbe{
 static int checks;
 static void check(boolean v,String s){if(!v)throw new AssertionError(s);checks++;System.out.println("PASS "+s);}
 static NullPointerException gap(){return new NullPointerException("Could not get current state of activity TestHarnessActivity due to the transition is incomplete. Current stage = STARTED");}
 static void await(ActivityScenario<TestHarnessActivity> s,State target)throws Throwable{Method m=HostLifecycleTestActions.class.getDeclaredMethod("awaitState",ActivityScenario.class,State.class);m.setAccessible(true);try{m.invoke(null,s,target);}catch(InvocationTargetException e){throw e.getCause();}}
 static ActivityScenario<TestHarnessActivity> scenario(Object... steps){ActivityScenario<TestHarnessActivity> s=new ActivityScenario<>(new TestHarnessActivity());for(Object o:steps)s.states.add(o);s.repeat=State.RESUMED;return s;}
 static TargetProbe target(String mode,String state)throws Exception{TargetProbe p=new TargetProbe();p.intent.values.put("order_json","{\\"status\\":\\""+state+"\\",\\"pickup_lat\\":-0.8,\\"pickup_lng\\":120.1,\\"delivery_lat\\":-0.9,\\"delivery_lng\\":120.2}");if(mode!=null)p.intent.values.put("target",mode);p.intent.values.put("target_lat",-1.1);p.intent.values.put("target_lng",120.4);p.readOrder();return p;}
 public static void main(String[] args)throws Throwable{
  android.os.SystemClock.now=0;await(scenario(gap(),State.RESUMED),State.RESUMED);check(true,"API36 incomplete-STARTED NPE recovers within existing deadline");
  await(scenario(new IllegalStateException("moving"),State.CREATED),State.CREATED);check(true,"existing IllegalStateException transition retry retained");
  boolean failed=false;try{await(scenario(new NullPointerException("application bug")),State.RESUMED);}catch(NullPointerException e){failed=true;}check(failed,"unrelated NPE is never swallowed");
  failed=false;try{await(scenario(new NullPointerException()),State.RESUMED);}catch(NullPointerException e){failed=true;}check(failed,"null-message NPE remains a failure");
  ActivityScenario<TestHarnessActivity> stuck=scenario();stuck.repeat=gap();failed=false;try{await(stuck,State.RESUMED);}catch(AssertionError e){failed=true;}check(failed,"permanently incomplete transition still fails after deadline");
  failed=false;try{await(scenario(State.DESTROYED),State.RESUMED);}catch(AssertionError e){failed=true;}check(failed,"destroyed Activity cannot pass foreground assertion");
  await(scenario(State.RESUMED),State.RESUMED);check(true,"normal stable lifecycle state still passes");
  HostLifecycleTestActions.backgroundAndReturn(scenario(State.RESUMED,gap(),State.CREATED,gap(),State.RESUMED));check(true,"full background/foreground helper preserves host identity and task checks");
  check(android.app.ActivityManager.starts==0&&android.app.Instrumentation.focus==1,"modern API keeps shell focus and never relaunches host");
  for(int api:new int[]{24,26}){
   android.os.Build.VERSION.SDK_INT=api;int starts=android.app.ActivityManager.starts;
   HostLifecycleTestActions.backgroundAndReturn(scenario(State.RESUMED,State.CREATED,gap(),State.RESUMED));
   check(android.app.ActivityManager.starts==starts+1,"API"+api+" restores via exact AppTask on main thread");
   android.content.Intent resume=android.app.ActivityManager.intent;
   check(resume.target==TestHarnessActivity.class&&resume.flags==(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),"API"+api+" reuses host without clearing or creating another task");
  }
  check(android.app.Instrumentation.idle==3,"each foreground request waits for stopped host main queue");
  ActivityScenario<TestHarnessActivity> neverResumes=scenario(State.RESUMED,State.CREATED);neverResumes.repeat=gap();int dumps=android.app.Instrumentation.dumps;
  failed=false;try{HostLifecycleTestActions.backgroundAndReturn(neverResumes);}catch(AssertionError e){failed=true;}
  check(failed,"legacy foreground request without real RESUMED still fails");
  check(android.app.Instrumentation.dumps>=dumps+2,"legacy timeout includes activity and window diagnostics");
  android.app.ActivityManager.missing=true;int starts=android.app.ActivityManager.starts;failed=false;
  try{HostLifecycleTestActions.backgroundAndReturn(scenario(State.RESUMED,State.CREATED));}catch(AssertionError e){failed=true;}
  check(failed&&android.app.ActivityManager.starts==starts,"missing original task fails without starting a replacement");
  android.app.ActivityManager.missing=false;android.os.Build.VERSION.SDK_INT=36;
  TargetProbe p=target("waypoint_2","on_delivery");check(p.targetMode.equals("waypoint_2")&&p.targetLat()==-1.1&&p.targetLng()==120.4,"Trip waypoint intent resolves to explicit stop, not final delivery");
  p=target(" WAYPOINT_3 ","driver_accepted");check(p.targetMode.equals("waypoint_3")&&p.targetLat()==-1.1,"waypoint remains explicit before pickup and ignores case/whitespace");
  p=target("pickup","on_delivery");check(p.targetMode.equals("pickup")&&p.targetLat()==-0.8&&p.targetLng()==120.1,"explicit pickup remains unchanged");
  p=target("delivery","driver_accepted");check(p.targetMode.equals("delivery")&&p.targetLat()==-0.9&&p.targetLng()==120.2,"explicit delivery remains unchanged");
  for(String st:new String[]{"arrived_pickup","on_delivery","arrived_delivery"}){p=target(null,st);check(p.targetMode.equals("delivery")&&p.targetLat()==-0.9,"existing status-to-delivery mapping retained: "+st);}
  p=target(null,"driver_accepted");check(p.targetMode.equals("pickup"),"existing accepted-to-pickup mapping retained");
  p=target("waypoint_bad","on_delivery");check(p.targetMode.equals("delivery"),"malformed waypoint falls back to existing status rule");
  check(NavigationInstructionController.destinationLabel("pickup").equals("penjemputan"),"pickup banner labels actual destination");
  check(NavigationInstructionController.destinationLabel("waypoint_2").equals("tujuan tambahan"),"waypoint banner labels additional stop");
  android.widget.TextView badge=new android.widget.TextView();org.json.JSONArray maneuvers=new org.json.JSONArray().put(new org.json.JSONObject().put("distance_from_start",0).put("type","arrive").put("modifier","left"));
  new NavigationInstructionController(badge,"waypoint_2").update(maneuvers,0);check(badge.text.contains("Tiba di tujuan tambahan"),"arrival beats left modifier and names additional stop");
  android.os.SystemClock.now+=300;new NavigationInstructionController(badge,"pickup").update(maneuvers,0);check(badge.text.contains("Tiba di penjemputan"),"pickup arrival never announces delivery");
  System.out.println("LIFECYCLE/TARGET: "+checks+" checks PASS");
 }
}'''
}
with tempfile.TemporaryDirectory(prefix='transiva-lifecycle-target-') as temp:
 d=Path(temp)
 for n,s in files.items():p=d/n;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s)
 sources=list(d.rglob('*.java'))+[test/'HostLifecycleTestActions.java',app/'NavigationInstructionController.java']
 subprocess.run([os.environ.get('JAVAC_BIN','javac'),'-cp',os.environ['JSON_JAR'],'-d',str(d/'classes'),*map(str,sources)],check=True)
 subprocess.run(['java','-cp',str(d/'classes')+os.pathsep+os.environ['JSON_JAR'],'com.transiva.app.RegressionProbe'],check=True)
