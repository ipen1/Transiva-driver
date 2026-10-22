#!/usr/bin/env python3
"""Real navigation provider helper with isolated transport; no production DB or upstream calls."""
from pathlib import Path
import os,shutil,subprocess,sys,tempfile
server=Path(sys.argv[1]).resolve();php=os.environ.get('PHP_BIN','php')
# The bundle's new helper depends on the unchanged baseline order-route validator.
with tempfile.TemporaryDirectory(prefix='transiva-nav-server-') as temp:
 root=Path(temp)
 (root/'tmp').mkdir()
 for name in ['driver_navigation_route_helper.php','driver_navigation_comfort.php','transiva_server_route.php','schema_runtime_guard.php']:shutil.copy2(server/name,root/name)
 (root/'probe.php').write_text('''<?php
require 'driver_navigation_route_helper.php';$checks=0;
function check($v,$label){global $checks;if(!$v)throw new RuntimeException($label);$checks++;echo "PASS $label\\n";}
$points=[['latitude'=>-.8,'longitude'=>120],['latitude'=>-.79,'longitude'=>120]];
function route($meters){return ['distance'=>$meters,'duration'=>100,'legs'=>[['distance'=>$meters,'duration'=>100,'steps'=>[]]],'geometry'=>['coordinates'=>[[120,-.8],[120,-.79]]]];}
$reply=['code'=>'Ok','waypoints'=>[['distance'=>0],['distance'=>0]],'routes'=>[route(1000),route(1200)]];
$config=['car'=>['https://car.example.test/route/v1/driving/'],'motor'=>[]];
$p=dnr_profile('motor',$config);check($p['used']==='car'&&$p['fallback'],'unavailable motor graph is explicitly labelled car fallback');
$config['motor']=['https://motor.example.test/route/v1/motorcycle/'];$p=dnr_profile('motor',$config);check($p['used']==='motor'&&!$p['fallback'],'configured motorcycle graph preserves motor profile');
$failed=false;try{dnr_profile('bike',$config);}catch(TransivaRouteException $e){$failed=$e->getCode()===422;}check($failed,'unknown profile cannot become a hidden cycling route');
$failed=false;try{dnr_profile('motor',['motor'=>['http://unsafe.test/route/']]);}catch(TransivaRouteException $e){$failed=true;}check($failed,'insecure configured provider is rejected');
$failed=false;try{dnr_profile('car',['car'=>['https://user:secret@provider.test/route/']]);}catch(TransivaRouteException $e){$failed=true;}check($failed,'credential-bearing provider URL rejected');
$url='';$transport=function($u)use(&$url,$reply){$url=$u;return $reply;};
$r=dnr_route($points,'motor',$config,true,180,$transport);check(count($r['routes'])===2&&$r['routes'][1]['distance']===1200,'alternative longer route is preserved for explicit driver choice');
check(str_contains($url,'alternatives=true')&&str_contains($url,'bearings=180,45;'),'provider request includes alternatives and current origin heading');
check($r['transiva_navigation']['used_profile']==='motor'&&!$r['transiva_navigation']['profile_fallback'],'response truthfully identifies configured graph');
check($r['transiva_navigation']['traffic_aware']===false,'OSRM ETA is not misrepresented as live traffic');
$config['motor']=[];$r=dnr_route($points,'motor',$config,true,null,$transport);check($r['transiva_navigation']['used_profile']==='car'&&$r['transiva_navigation']['profile_fallback'],'motor-to-car fallback survives server response contract');
$calls=0;$transport=function($u)use(&$calls,$reply){$calls++;return $reply;};$config['car']=['https://cache-'.bin2hex(random_bytes(6)).'.example.test/route/v1/driving/'];
$r=dnr_route($points,'car',$config,false,null,$transport);$r=dnr_route($points,'car',$config,false,null,$transport);check($calls===1,'unchanged navigation request can reuse bounded provider cache');
$r=dnr_route($points,'car',$config,true,null,$transport);check($calls===2,'forced reroute bypasses server cache as well as client cache');
$config['car']=['https://bad.example.test/route/','https://good.example.test/route/'];$calls=0;$transport=function($u)use(&$calls,$reply){$calls++;return $calls===1?null:$reply;};
$r=dnr_route($points,'car',$config,true,null,$transport);check($calls===2&&count($r['routes'])===2,'bounded second provider recovers first-provider outage');
$bad=$reply;$bad['routes'][1]['legs'][0]['distance']=2000;$r=dnr_route($points,'car',$config,true,null,fn($u)=>$bad);check(count($r['routes'])===1,'invalid alternative metrics are filtered without fabricating a route');
$failed=false;try{dnr_route($points,'car',$config,true,null,fn($u)=>['code'=>'NoRoute']);}catch(TransivaRouteException $e){$failed=$e->getCode()===503;}check($failed,'provider outage remains a real route failure');
$failed=false;try{dnr_route([['latitude'=>999,'longitude'=>120],$points[1]],'car',$config,true,null,fn($u)=>$reply);}catch(TransivaRouteException $e){$failed=true;}check($failed,'invalid coordinates fail before upstream routing');

$before=count(glob(sys_get_temp_dir().'/transiva_navigation_v1/[a-f0-9][a-f0-9].json'));
for($i=0;$i<350;$i++){$cfg=['car'=>['https://bounded-'.$i.'.example.test/route/'],'motor'=>[]];dnr_route($points,'car',$cfg,true,null,fn($u)=>$reply);}
check(count(glob(sys_get_temp_dir().'/transiva_navigation_v1/[a-f0-9][a-f0-9].json'))<=256,'coordinate and heading cache has at most 256 data files');
$cfg=['car'=>['https://heading.example.test/route/'],'motor'=>[]];$lastUrl='';dnr_route($points,'car',$cfg,true,359.9,function($u)use(&$lastUrl,$reply){$lastUrl=$u;return $reply;});check(str_contains($lastUrl,'bearings=0,45;'),'rounded origin heading is an integer normalized below 360');
$stale=sys_get_temp_dir().'/transiva_navigation_v1/'.str_repeat('a',64).'.json';file_put_contents($stale,'{}');touch($stale,time()-300);@unlink(sys_get_temp_dir().'/transiva_navigation_v1/.cleanup');dnr_cleanup();check(!is_file($stale),'legacy expired coordinate cache files are removed');
echo "NAVIGATION SERVER: $checks checks PASS\\n";
''')
 r=subprocess.run([php,'-n','-d','sys_temp_dir='+str(root/'tmp'),str(root/'probe.php')],cwd=root,capture_output=True,text=True)
 print(r.stdout,end='')
 if r.returncode:raise RuntimeError(r.stderr+r.stdout)
