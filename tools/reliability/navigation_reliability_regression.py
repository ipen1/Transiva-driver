#!/usr/bin/env python3
"""Production GPS reacquisition, FGS acknowledgement and 60/90/120Hz scheduling.
Uses Android/map collaborators; does not measure real-device GPU FPS.
"""
from pathlib import Path
import ast,os,subprocess,tempfile
root=Path(__file__).resolve().parents[2];app=root/'app/src/main/java/com/transiva/app'
motion=Path(__file__).with_name('navigation_motion_regression.py');ns={'__file__':str(motion)}
exec(motion.read_text().split("files['com/transiva/app/MotionProbe.java']")[0],ns);files=ns['files'];base=ns['base']
files.pop('com/transiva/app/NavigationCompatibilityProfile.java')
files['com/transiva/app/DevicePerformanceProfile.java']='''package com.transiva.app;public class DevicePerformanceProfile{
 public enum Tier{HIGH,NORMAL,LOW,VERY_LOW}public enum UserMode{AUTO,HIGH,NORMAL,LOW}
 public static Tier detected=Tier.HIGH;public static UserMode selected=UserMode.AUTO;public Tier tier=detected;public long visualFrameMs=50,navigationGpsMs=1000,navigationNetworkMs=2500;public float navigationMinDistanceM=1;
 public static DevicePerformanceProfile get(android.content.Context c){return new DevicePerformanceProfile();}public static UserMode getSelectedMode(android.content.Context c){return selected;}}
'''
files['android/view/Display.java']='''package android.view;public class Display{public Mode current=new Mode(1,1080,2400,60);public Mode[] modes={current};public Mode getMode(){return current;}public Mode[] getSupportedModes(){return modes;}public static class Mode{int id,w,h;float hz;public Mode(int n,int x,int y,float f){id=n;w=x;h=y;hz=f;}public int getModeId(){return id;}public int getPhysicalWidth(){return w;}public int getPhysicalHeight(){return h;}public float getRefreshRate(){return hz;}}}'''
files['android/view/WindowManager.java']='package android.view;public class WindowManager{public Display display=new Display();public Display getDefaultDisplay(){return display;}public static class LayoutParams{public int preferredDisplayModeId;public float preferredRefreshRate;}}'
files['android/view/Window.java']='package android.view;public class Window{public WindowManager.LayoutParams params=new WindowManager.LayoutParams();public int writes;public WindowManager.LayoutParams getAttributes(){return params;}public void setAttributes(WindowManager.LayoutParams p){params=p;writes++;}}'
files['android/app/Activity.java']=files['android/app/Activity.java'].replace('public boolean finishing,','public android.view.Window window=new android.view.Window();public android.view.WindowManager wm=new android.view.WindowManager();public android.view.Window getWindow(){return window;}public android.view.WindowManager getWindowManager(){return wm;}public boolean finishing,')
files['android/app/Service.java']=files['android/app/Service.java'].replace('public boolean stopped;','public static boolean failPromotion;public boolean stopped;').replace('public void startForeground(int n,Notification x){}','public void startForeground(int n,Notification x){if(failPromotion)throw new SecurityException("simulated foreground promotion denial");}')
files['com/transiva/app/HostMotionProbe.java']=files['com/transiva/app/HostMotionProbe.java'].replace('int visualRouteSegmentIndex,','int guidanceCalls,lineCalls,visualRouteSegmentIndex,').replace('void maybeUpdateRemainingRouteLine(){}','void maybeUpdateRemainingRouteLine(){lineCalls++;}').replace('void updateInstructionBanner(double p){instructionProgress=p;}','void updateInstructionBanner(double p){instructionProgress=p;guidanceCalls++;}')
files['com/transiva/app/DriverNavigationActivityLayer2.java']=files['com/transiva/app/DriverNavigationActivityLayer2.java'].replace(' void setRoutePoints(', ' int matches;void setRoutePoints(').replace('SnapPoint snapToRoute(double a,double b){','SnapPoint snapToRoute(double a,double b){matches++;')
files['org/maplibre/android/style/sources/GeoJsonSource.java']=files['org/maplibre/android/style/sources/GeoJsonSource.java'].replace('public String json;','public int writes;public String json;').replace('public void setGeoJson(String s){json=s;}','public void setGeoJson(String s){json=s;writes++;}')
files['com/transiva/app/TransivaDiagnostics.java']='package com.transiva.app;public class TransivaDiagnostics{public static int promotionFailures;public static void error(android.content.Context c,String a,String b,Throwable t){if("GUIDANCE_FOREGROUND_FAILED".equals(b)){promotionFailures++;return;}throw new RuntimeException(t);}}'
files['android/widget/TextView.java']=files['android/widget/TextView.java'].replace('public String text=', 'public int maxLines;public String description;public void setMaxLines(int v){maxLines=v;}public void setContentDescription(String v){description=v;}public String text=')
files['android/widget/Toast.java']=files['android/widget/Toast.java'].replace('public void show(){}','public static int shown;public void show(){shown++;}')
method=ns['method'];options=(app/'NavigationOptionsController.java').read_text()
files['com/transiva/app/HostOptionsProbe.java']='package com.transiva.app;import android.widget.*;class HostOptionsProbe{android.app.Activity activity=new android.app.Activity();TextView sound=new TextView();boolean enabled=true,ready,backgroundFailed,warned;'+method(options,'void guidanceFailure(')+method(options,'private void updateVoice(')+'}'
files['com/transiva/app/ReliabilityProbe.java']=Path(__file__).with_name('navigation_reliability_probe.java.fixture').read_text()
parsed=ast.parse(base.read_text());production=ast.literal_eval(next(n.value for n in parsed.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='production' for t in n.targets)))
production+=['NavigationRenderedHeading','NavigationFrameController','NavigationCameraController','NavigationVehicleController','TripVehicleMotionController','NavigationCompatibilityProfile','NavigationDisplayPolicy']
with tempfile.TemporaryDirectory(prefix='nav-reliability-') as tmp:
 d=Path(tmp)
 for name,content in files.items():p=d/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
 subprocess.run([os.environ.get('JAVAC_BIN','javac'),'-cp',os.environ['JSON_JAR'],'-d',str(d/'classes'),*map(str,list(d.rglob('*.java'))+[app/(n+'.java') for n in production])],check=True)
 subprocess.run([os.environ.get('JAVA_BIN','java'),'-cp',str(d/'classes')+os.pathsep+os.environ['JSON_JAR'],'com.transiva.app.ReliabilityProbe'],check=True,timeout=30)
