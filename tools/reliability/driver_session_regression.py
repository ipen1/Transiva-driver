#!/usr/bin/env python3
"""Executes the production session owner and UI scope with isolated lifecycle/auth collaborators."""
import os,subprocess,tempfile
from pathlib import Path
root=Path(__file__).resolve().parents[2]
app=root/'app/src/main/java/com/transiva/app'
javac=os.environ.get('JAVAC_BIN','javac');java=os.environ.get('JAVA_BIN','java')
files={
'android/content/Context.java':'package android.content;public class Context{public Context getApplicationContext(){return this;}}',
'android/app/Activity.java':'package android.app;public class Activity extends android.content.Context{public boolean finishing,destroyed;public boolean isFinishing(){return finishing;}public boolean isDestroyed(){return destroyed;}}',
'android/os/Looper.java':'package android.os;public class Looper{public static Looper getMainLooper(){return new Looper();}}',
'android/os/Handler.java':'package android.os;import java.util.*;public class Handler{static final List<Runnable> queue=new ArrayList<>();public Handler(Looper l){}public void post(Runnable r){queue.add(r);}public void removeCallbacksAndMessages(Object o){queue.clear();}public static void flush(){for(Runnable r:new ArrayList<>(queue)){r.run();}queue.clear();}}',
'com/transiva/app/SessionManager.java':'package com.transiva.app;public class SessionManager{static String token="A",id="1",role="driver";static boolean logged=true;public SessionManager(android.content.Context c){}public String getToken(){return token;}public String getId(){return id;}public String getUserId(){return id;}public String getRole(){return role;}public String normalizeRole(String r){return r;}public boolean isLoggedIn(){return logged;}}',
'com/transiva/app/DeviceIdentityManager.java':'package com.transiva.app;public class DeviceIdentityManager{static String uuid="device";public static String getInstallationUuid(android.content.Context c){return uuid;}}',
'com/transiva/app/DriverNetworkExecutor.java':'package com.transiva.app;public class DriverNetworkExecutor{private static final java.util.concurrent.ExecutorService E=java.util.concurrent.Executors.newSingleThreadExecutor();public static java.util.concurrent.ExecutorService executor(){return E;}}',
'com/transiva/app/SessionProbe.java':'''package com.transiva.app;
import android.content.Context;import android.app.Activity;import android.os.Handler;import java.net.*;
public class SessionProbe{
 static int checks,writes;
 static void check(boolean v,String s){if(!v)throw new AssertionError(s);checks++;}
 static class Connection extends HttpURLConnection{boolean disconnected;Connection(String url)throws Exception{super(new URL(url));}public void connect(){}public boolean usingProxy(){return false;}public void disconnect(){disconnected=true;}}
 public static void main(String[] args)throws Exception{
  Context context=new Context();DriverSessionOwner owner=DriverSessionOwner.capture(context);
  check(owner.current(),"initial identity accepted");Connection c=new Connection("https://transiva.my.id/server/test");owner.pin(c);
  check("Bearer A".equals(c.getRequestProperty("Authorization")),"captured credential pinned");
  check(!c.getInstanceFollowRedirects(),"redirects disabled for credentials");
  SessionManager.token="B";check(!owner.current(),"replacement token rejected");
  check(!owner.runIfCurrent(()->writes++ )&&writes==0,"late account write rejected");
  boolean rejected=false;try{owner.pin(new Connection("https://transiva.my.id/server/test"));}catch(java.io.IOException expected){rejected=true;}check(rejected,"old request cannot retarget new login");
  SessionManager.token="A";SessionManager.id="2";check(!owner.current(),"different account with same token rejected");SessionManager.id="1";
  rejected=false;try{owner.pin(new Connection("https://other.invalid/test"));}catch(java.io.IOException expected){rejected=true;}check(rejected,"foreign host rejected");
  owner.close();for(int i=0;i<100&&!c.disconnected;i++)Thread.sleep(10);check(c.disconnected&&!owner.current(),"close disconnects owned request");
  Activity a=new Activity();DriverUiScope scope=new DriverUiScope(a);scope.post(()->writes++);Handler.flush();check(writes==1,"live callback delivered");
  scope.post(()->writes++);SessionManager.token="B";Handler.flush();check(writes==1,"queued old-session callback discarded");
  SessionManager.token="A";scope.post(()->writes++);a.destroyed=true;Handler.flush();check(writes==1,"destroyed Activity callback discarded");
  a.destroyed=false;scope.post(()->writes++);scope.close();Handler.flush();check(writes==1,"closed scope callback discarded");
  SessionManager.logged=false;check(!DriverSessionOwner.capture(context).current(),"logged-out identity rejected");
  System.out.println("PASS "+checks+" production session/lifecycle scenarios");System.exit(0);
 }
}'''}
with tempfile.TemporaryDirectory(prefix='driver-session-') as directory:
 d=Path(directory)
 for name,value in files.items():
  p=d/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(value)
 sources=list(d.rglob('*.java'))+[app/(name+'.java') for name in ['DriverSessionOwner','DriverUiScope','NetworkSafetyRules']]
 subprocess.run([javac,'-d',str(d/'classes'),*map(str,sources)],check=True)
 subprocess.run([java,'-cp',str(d/'classes'),'com.transiva.app.SessionProbe'],check=True)
