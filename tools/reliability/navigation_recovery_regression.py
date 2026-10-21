#!/usr/bin/env python3
"""Deterministic recovery/ETA checks plus existing real HTTP/session routing regression."""
from pathlib import Path
import os,subprocess,tempfile,runpy
root=Path(__file__).resolve().parents[2];app=root/'app/src/main/java/com/transiva/app'
# Existing production transport, cancellation, lifecycle, voice and heading tests.
fixtures=runpy.run_path(str(Path(__file__).with_name('navigation_upgrade_regression.py')))
files=fixtures['files'];files.pop('com/transiva/app/NavigationUpgradeProbe.java')
files['android/content/SharedPreferences.java']=files['android/content/SharedPreferences.java'].replace('public Map<String,?> getAll()', 'public String getString(String k,String d){return (String)values.getOrDefault(k,d);}public Map<String,?> getAll()').replace('public Editor putLong(', 'public Editor putString(String k,String v){set.put(k,v);return this;}public Editor putLong(')
probe='''package com.transiva.app;
public class RecoveryProbe{
 static int checks;static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
 public static void main(String[] args)throws Exception{
 NavigationRecoveryPolicy p=new NavigationRecoveryPolicy();check(!p.needed()&&!p.due(10000),"healthy route never requests recovery");p.failed(1000);check(!p.due(2999)&&p.due(3000),"failure retries after two seconds even with retained geometry");p.failed(3000);check(!p.due(6999)&&p.due(7000),"repeated failures back off");for(int i=0;i<10;i++)p.failed(7000);check(!p.due(36999)&&p.due(37000),"maximum recovery delay stays thirty seconds");p.require(8000);check(!p.due(8000),"new GPS evidence cannot erase active network backoff");p.networkAvailable(9000);check(p.due(9000),"network restoration wakes pending recovery");p.reset();check(!p.due(100000),"successful route clears pending retries");p.require(100000);check(p.due(100000),"deviation schedules immediate fresh recovery");p.reset();p.networkAvailable(100001);check(!p.due(100001),"network callback alone never replaces healthy alternative");
 NavigationEtaEstimator e=new NavigationEtaEstimator();check(e.seconds(1000,500,50,1000,100)==50,"provider ETA remains before speed evidence");for(int i=1;i<=30;i++)e.offer(i*1000,5,5,true);double eta=e.seconds(30000,500,50,1000,100);check(eta>50&&eta<=87.5,"observed slower movement adjusts ETA conservatively");check(e.seconds(60000,500,50,1000,100)==50,"stale speed falls back to provider ETA");e.reset();for(int i=1;i<=30;i++)e.offer(i*1000,2,60,true);check(e.seconds(30000,500,50,1000,100)==50,"weak GPS cannot distort ETA");e.reset();for(int i=1;i<=30;i++)e.offer(i*1000,2,5,false);check(e.seconds(30000,500,50,1000,100)==50,"off-route movement cannot train ETA");e.reset();for(int i=1;i<=30;i++)e.offer(i*1000,0,5,true);check(e.seconds(30000,500,50,1000,100)>50&&e.seconds(30000,500,50,1000,100)<=230,"confirmed stationary time adds a finite bounded ETA delay");
 android.app.Activity context=new android.app.Activity();SessionManager.token="sensitive-navigation-test-token";String owner=NavigationSessionStore.identity(context);check(owner.length()==64&&!owner.contains(SessionManager.token),"checkpoint identity is hashed without bearer credentials");
 org.json.JSONObject checkpoint=new org.json.JSONObject().put("owner",owner).put("saved",System.currentTimeMillis()).put("key","order-A|delivery");
 NavigationSessionStore.stopped=false;NavigationSessionStore.save(context,checkpoint);check(NavigationSessionStore.read(context)!=null,"same session can restore its route checkpoint");
 for(int i=0;i<250&&context.getSharedPreferences("nav_checkpoint_v2",0).getString("route","").isEmpty();i++)Thread.sleep(2);
 String persisted=context.getSharedPreferences("nav_checkpoint_v2",0).getString("route","");check(persisted.contains("order-A")&&!persisted.contains(SessionManager.token),"checkpoint is persisted asynchronously without its bearer token");
 SessionManager.token="replacement-login";check(NavigationSessionStore.read(context)==null,"replacement login cannot restore old navigation");SessionManager.token="sensitive-navigation-test-token";
 checkpoint.put("saved",System.currentTimeMillis()-21600001);check(!NavigationSessionStore.valid(context,checkpoint),"expired checkpoint cannot restore stale navigation");
 checkpoint.put("saved",System.currentTimeMillis()+60000);check(!NavigationSessionStore.valid(context,checkpoint),"future-dated checkpoint cannot pass age checks");
 NavigationSessionStore.clear(context);check(NavigationSessionStore.read(context)==null&&NavigationSessionStore.stopped,"ending navigation immediately invalidates memory even before disk deletion");
 org.json.JSONObject order=new org.json.JSONObject().put("order_id","trip-A").put("token","secret").put("pickup_lat",-0.8);org.json.JSONObject compact=NavigationSessionStore.compactOrder(order);check(compact.has("order_id")&&compact.has("pickup_lat")&&!compact.has("token"),"notification resume metadata excludes unneeded credentials");
 System.out.println("RECOVERY AND ETA: "+checks+" checks PASS");System.exit(0);}
}'''
with tempfile.TemporaryDirectory(prefix='navigation-recovery-') as temp:
 d=Path(temp);p=d/'com/transiva/app/RecoveryProbe.java';p.parent.mkdir(parents=True);p.write_text(probe)
 for name,value in files.items():q=d/name;q.parent.mkdir(parents=True,exist_ok=True);q.write_text(value)
 subprocess.run([os.environ.get('JAVAC_BIN','javac'),'-cp',os.environ['JSON_JAR'],'-d',str(d),*map(str,list(d.rglob('*.java'))+[app/(n+'.java') for n in fixtures['production']+['NavigationSessionStore','NavigationRecoveryPolicy','NavigationEtaEstimator']])],check=True)
 subprocess.run(['java','-cp',str(d)+os.pathsep+os.environ['JSON_JAR'],'com.transiva.app.RecoveryProbe'],check=True)
