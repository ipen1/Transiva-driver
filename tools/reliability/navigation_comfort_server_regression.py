#!/usr/bin/env python3
"""Surface-aware routing contract and degraded fallback; no network or production DB writes."""
from pathlib import Path
import os,subprocess,sys,tempfile,shutil
server=Path(sys.argv[1]).resolve();php=os.environ.get('PHP_BIN','php')
with tempfile.TemporaryDirectory(prefix='nav-comfort-server-') as temp:
 d=Path(temp);(d/'tmp').mkdir()
 for name in ['driver_navigation_route_helper.php','driver_navigation_route_config.php','driver_navigation_comfort.php','transiva_server_route.php','schema_runtime_guard.php']:shutil.copy2(server/name,d/name)
 (d/'probe.php').write_text(r'''<?php
require 'driver_navigation_route_helper.php';$n=0;
function ck($ok,$msg){global $n;if(!$ok)throw new RuntimeException($msg);$n++;echo "PASS $msg\n";}
$points=[['latitude'=>-.8,'longitude'=>120],['latitude'=>-.79,'longitude'=>120]];
function reply($distance=1400,$seconds=180){return ['code'=>'Ok','waypoints'=>[['location'=>[120,-.8],'distance'=>0],['location'=>[120,-.79],'distance'=>0]],'routes'=>[['distance'=>$distance,'duration'=>$seconds,'geometry'=>['coordinates'=>[[120,-.8],[120,-.79]]],'legs'=>[['distance'=>$distance,'duration'=>$seconds,'steps'=>[]]]]]];}
$cfg=['comfort_urls'=>['https://comfort.example/route'],'car'=>['https://car.example/route/'],'motor'=>[],'require_motor'=>true];
$payload=dnc_payload($points,'motor',359.9);$opts=$payload['costing_options']['motorcycle'];
ck($payload['costing']==='motorcycle','motor uses a dedicated motorcycle cost model');
ck($opts['exclude_unpaved']===true&&$opts['use_trails']===0.0&&$opts['use_tracks']===0.0,'motor avoids unpaved, tracks and trails in the graph search');
ck($opts['shortest']===false,'pure shortest-distance costing cannot disable comfort penalties');
ck($opts['use_tolls']===0.0,'motor does not favor tolled roads');
ck($payload['locations'][0]['heading']===0&&$payload['alternates']===2,'heading normalized and alternatives retained');
ck(dnc_payload($points,'car',null)['costing_options']['auto']['exclude_unpaved']===true,'car also excludes unpaved through-roads');
ck(dnc_payload(array_merge($points,[$points[0]]),'car',null)['alternates']===0,'multipoint request respects provider alternative support');
$calls=0;$seen=null;
$transport=function($url)use(&$calls,&$seen){$calls++;parse_str((string)parse_url($url,PHP_URL_QUERY),$q);$seen=json_decode($q['json'],true);return reply();};
$r=dnr_route($points,'motor',$cfg,true,180,$transport);
ck($calls===1&&$seen['costing']==='motorcycle','comfort provider is called before distance-only fallback');
ck($r['routes'][0]['distance']===1400,'longer comfortable provider path survives unchanged');
ck($r['transiva_navigation']['road_preference']==='avoid_unpaved'&&$r['transiva_navigation']['used_profile']==='motor','comfort and actual motor model remain explicit');
ck($r['transiva_navigation']['traffic_aware']===false,'road comfort is never claimed as real-time traffic');
ck($r['waypoints'][0]['distance']===0.0,'snapping distance is derived from actual provider location');
$bad=reply();$bad['waypoints'][1]['location']=[121,-.79];ck(dnc_normalize($bad,$points)===null,'far snapped destination is rejected');
$bad=reply();$bad['routes'][0]['legs'][0]['distance']=100;ck(dnc_normalize($bad,$points)===null,'inconsistent leg totals are rejected');
$bad=reply();unset($bad['waypoints'][1]['location']);ck(dnc_normalize($bad,$points)===null,'missing snapped position cannot be fabricated');
$bad=reply();$bad['routes'][0]['duration']=INF;ck(dnc_normalize($bad,$points)===null,'non-finite ETA is rejected');
foreach(['http://host/route','https://user@host/route','https://:secret@host/route','https://host/route?key=secret','https://host/route#x'] as $url)ck(!dnc_url($url),'unsafe comfort URL rejected: '.parse_url($url,PHP_URL_SCHEME));
$calls=0;$fallback=function($url)use(&$calls){$calls++;return str_contains($url,'comfort.example')?null:reply(1000,120);};
$r=dnr_route($points,'car',$cfg,true,null,$fallback);
ck($calls===2&&$r['transiva_navigation']['road_preference']==='unverified','outage yields explicit degraded car route instead of false smooth-road promise');
$failed=false;try{dnr_route($points,'motor',$cfg,true,null,$fallback);}catch(TransivaRouteException $e){$failed=$e->routeCode==='MOTOR_PROFILE_UNAVAILABLE';}ck($failed,'strict motor never silently falls back to car');
$calls=0;$cfg['comfort_urls']=['https://cache-comfort.example/route'];$t=function($url)use(&$calls){$calls++;return reply();};
dnr_route($points,'car',$cfg,false,null,$t);dnr_route($points,'car',$cfg,false,null,$t);ck($calls===1,'comfort response uses bounded existing route cache');
$cfg['comfort_urls']=[];dnr_route($points,'car',$cfg,false,null,$t);ck($calls===2,'changed comfort policy cannot reuse old cached route');
putenv('TRANSIVA_NAV_MOTOR_URL=https://named-motor.example/route/');putenv('TRANSIVA_NAV_MOTOR_GRAPH_VERIFIED=0');$real=require 'driver_navigation_route_config.php';ck(!$real['motor']&&$real['require_motor'],'motor URL name alone cannot attest to a dedicated graph');
putenv('TRANSIVA_NAV_MOTOR_GRAPH_VERIFIED=1');$real=require 'driver_navigation_route_config.php';ck(count($real['motor'])===1,'operator-verified custom motor graph is accepted');
$failed=false;try{dnr_route($points,'car',$cfg,true,NAN,$t);}catch(TransivaRouteException $e){$failed=true;}ck($failed,'non-finite heading rejected before routing');
echo "COMFORT SERVER: $n checks PASS\n";
''')
 r=subprocess.run([php,'-n','-d','sys_temp_dir='+str(d/'tmp'),str(d/'probe.php')],cwd=d,capture_output=True,text=True)
 print(r.stdout,end='')
 if r.returncode:raise RuntimeError(r.stderr)
