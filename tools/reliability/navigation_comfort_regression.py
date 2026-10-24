#!/usr/bin/env python3
"""Production map reconciliation, waypoint payload, local-road ETA and frame statistics.
Android collaborators do not substitute for physical-device map/GPU tests.
"""
from pathlib import Path
import ast,os,subprocess,tempfile
root=Path(__file__).resolve().parents[2];app=root/'app/src/main/java/com/transiva/app'
base=Path(__file__).with_name('navigation_consistency_regression.py');ns={'__file__':str(base)}
exec(base.read_text().split("files['com/transiva/app/ConsistencyProbe.java']")[0],ns);files=ns['files']
def method(source,signature):
 a=source.index(signature);b=source.index('{',a);depth=1;c=b+1
 while depth:
  if source[c]=='{':depth+=1
  if source[c]=='}':depth-=1
  c+=1
 return source[a:c]
files['android/view/View.java']='package android.view;public class View{public Object parent;public int removes;public static final int VISIBLE=0,GONE=8;public void setVisibility(int v){}}'
files['android/widget/LinearLayout.java']='''package android.widget;public class LinearLayout extends android.view.View{java.util.List<android.view.View> list=new java.util.ArrayList<>();public int getChildCount(){return list.size();}public android.view.View getChildAt(int i){return list.get(i);}public void removeViewAt(int i){android.view.View v=list.remove(i);v.parent=null;v.removes++;}public void addView(android.view.View v,int i){if(v.parent!=null)throw new IllegalStateException("already attached");list.add(i,v);v.parent=this;}public void addView(android.view.View v){addView(v,list.size());}}'''
files['com/transiva/app/DriverUiScope.java']='''package com.transiva.app;class DriverUiScope{boolean accept=true;java.util.List<Runnable> callbacks=new java.util.ArrayList<>();boolean execute(Runnable r){if(!accept)return false;r.run();return true;}void post(Runnable r){callbacks.add(r);}void drain(){for(Runnable r:new java.util.ArrayList<>(callbacks))r.run();callbacks.clear();}}'''
files['com/transiva/app/driver/data/DriverApiClient.java']='''package com.transiva.app.driver.data;import org.json.*;public class DriverApiClient{public static JSONObject payload,response=new JSONObject();public static boolean fail;public static Runnable during;public DriverApiClient(com.transiva.app.SessionManager s){}public static class Result{public JSONObject body;Result(JSONObject b){body=b;}}public Result post(String endpoint,JSONObject body)throws Exception{payload=body;if(during!=null)during.run();if(fail)throw new Exception("rejected");return new Result(response);}}'''
# The other compiled controllers require endpoint client GET, whose outcome is not part of this probe.
files['com/transiva/app/driver/data/DriverApiClient.java']=files['com/transiva/app/driver/data/DriverApiClient.java'].replace('public Result post(', 'public void shutdown(){}public Result get(String endpoint)throws Exception{return new Result(response);}public Result post(')
files['com/transiva/app/WaypointProbe.java']='''package com.transiva.app;import android.location.Location;import android.view.View;import org.json.*;class WaypointProbe extends android.app.Activity{SessionManager session=new SessionManager(new android.app.Activity());JSONObject order=new JSONObject();boolean waypointArrivalBusy,updatingStatus;boolean tripStateFresh=true;long tripLastSync=android.os.SystemClock.elapsedRealtime(),tripMutationVersion;android.view.View progressBar=new android.view.View();DriverUiScope tripRouteScope=new DriverUiScope();Location fix;String id="one",state="on_delivery";int errors,renders,saves,requests;String orderId(){return id;}String status(){return state;}Location trustedArrivalFix(){return fix;}void tripInfo(String a,String b){errors++;}void saveActiveOrder(){saves++;}void renderOrder(){renders++;}void refreshButtons(){}void requestStableRoute(boolean f){requests++;}String first(String... values){for(String v:values)if(v!=null&&!v.isEmpty())return v;return "";}
'''+''.join(method((app/'DriverTripActivityLayer1.java').read_text(),signature) for signature in ['protected boolean tripStateReady(','protected void tripMutationStarted(','protected void tripNeedsSync('])+method((app/'DriverTripActivityLayer2.java').read_text(),'protected void markCurrentWaypointArrived(')+'}'
files['com/transiva/app/ComfortProbe.java']=Path(__file__).with_name('navigation_comfort_probe.java.fixture').read_text()
parsed=ast.parse(base.read_text());production=ast.literal_eval(next(n.value for n in parsed.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='production' for t in n.targets)))
production+=['TripMapContent','NavigationFrameStats','DriverEcosystemFeatures']
with tempfile.TemporaryDirectory(prefix='nav-comfort-') as tmp:
 d=Path(tmp)
 for name,content in files.items():p=d/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content)
 subprocess.run([os.environ.get('JAVAC_BIN','javac'),'-cp',os.environ['JSON_JAR'],'-d',str(d/'classes'),*map(str,list(d.rglob('*.java'))+[app/(n+'.java') for n in production])],check=True)
 subprocess.run([os.environ.get('JAVA_BIN','java'),'-cp',str(d/'classes')+os.pathsep+os.environ['JSON_JAR'],'com.transiva.app.ComfortProbe'],check=True,timeout=30)
