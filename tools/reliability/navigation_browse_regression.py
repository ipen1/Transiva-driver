#!/usr/bin/env python3
"""Run production camera/pose/heading/ETA/matching/nested gesture regressions on host SDK collaborators.
Not an Android SDK build or a substitute for device tests.
"""
from pathlib import Path
import ast,os,subprocess,tempfile
root=Path(__file__).resolve().parents[2];app=root/'app/src/main/java/com/transiva/app'
motion=Path(__file__).with_name('navigation_motion_regression.py')
ns={'__file__':str(motion)};exec(motion.read_text().split("files['com/transiva/app/MotionProbe.java']")[0],ns)
files=ns['files'];method=ns['method'];base=ns['base']
files['android/view/ViewParent.java']='package android.view;public interface ViewParent{void requestDisallowInterceptTouchEvent(boolean value);}'
files['android/view/MotionEvent.java']='package android.view;public class MotionEvent{public static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3,ACTION_POINTER_DOWN=5;private final int a;public MotionEvent(int v){a=v;}public int getActionMasked(){return a;}}'
files['android/widget/FrameLayout.java']='package android.widget;public class FrameLayout{public android.view.ViewParent parent;public int dispatched;public FrameLayout(){}public FrameLayout(android.content.Context c){}public void setTag(Object t){}public void requestDisallowInterceptTouchEvent(boolean b){if(parent!=null)parent.requestDisallowInterceptTouchEvent(b);}public android.view.ViewParent getParent(){return parent;}public boolean dispatchTouchEvent(android.view.MotionEvent e){dispatched++;return true;}protected void onDetachedFromWindow(){}}'
files['com/transiva/app/HostVehicleProbe.java']='''package com.transiva.app;class HostVehicleProbe {
 org.maplibre.android.maps.MapLibreMap map;NavigationVehicleController vehicleController;boolean displayInitialized;double displayLat,displayLng,driverLat=-.8,driverLng=120,smoothMarkerBearing=45;
'''+method((app/'DriverNavigationActivity.java').read_text(),'protected void updateVehicleSource(')+'}'
files['com/transiva/app/BrowseProbe.java']=Path(__file__).with_name('navigation_browse_probe.java.fixture').read_text()
parsed=ast.parse(base.read_text());production=ast.literal_eval(next(n.value for n in parsed.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='production' for t in n.targets)))
production+=['NavigationRenderedHeading','NavigationFrameController','NavigationCameraController','NavigationVehicleController','TripVehicleMotionController','NavigationGestureContainer']
with tempfile.TemporaryDirectory(prefix='nav-browse-') as tmp:
 d=Path(tmp)
 for name,content in files.items():p=d/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
 subprocess.run([os.environ.get('JAVAC_BIN','javac'),'-cp',os.environ['JSON_JAR'],'-d',str(d/'classes'),*map(str,list(d.rglob('*.java'))+[app/(n+'.java') for n in production])],check=True)
 subprocess.run([os.environ.get('JAVA_BIN','java'),'-cp',str(d/'classes')+os.pathsep+os.environ['JSON_JAR'],'com.transiva.app.BrowseProbe'],check=True,timeout=30)
