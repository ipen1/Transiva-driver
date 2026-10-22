#!/usr/bin/env python3
"""Production navigation-state endpoint with isolated DB/auth collaborators; no live DB writes."""
from pathlib import Path
import os,sys,subprocess,tempfile,shutil,json
server=Path(sys.argv[1]).resolve();php=os.environ.get('PHP_BIN','php');checks=0
with tempfile.TemporaryDirectory(prefix='nav-state-') as temp:
 d=Path(temp)
 for name in ['driver_navigation_state.php','driver_navigation_state_helper.php']:shutil.copy2(server/name,d/name)
 (d/'native_auth.php').write_text('''<?php
$fixture=json_decode(getenv('NAV_FIXTURE'),true);$_SERVER['REQUEST_METHOD']=$fixture['method']??'POST';
class NavStmt{public function bind_param(...$args){}public function execute(){}public function close(){}public function get_result(){return $this;}public function fetch_assoc(){global $fixture;return $fixture['row']??null;}}
class NavDB{public function prepare($sql){if(stripos($sql,'SELECT ')!==0||stripos($sql,'FOR UPDATE')!==false)throw new RuntimeException('Unexpected mutation/lock');return new NavStmt();}}
$conn=new NavDB();function native_input(){global $fixture;return $fixture['input']??[];}function native_require_driver($c){return ['id'=>7,'username'=>'DriverA'];}
function native_table_exists($c,$t){return true;}function native_out($ok,$msg,$data=[],$status=200){echo json_encode(array_merge(['success'=>$ok,'status_code'=>$status],$data));exit;}
''')
 (d/'p0_security_helper.php').write_text('<?php function p0_rate_limit($c,$k,$id,$n,$seconds){if($n!==30||$seconds!==60)throw new RuntimeException("Unbounded poll");}')
 (d/'ride_ecosystem_helper.php').write_text('<?php function eco_get($c,$id,$provision){if($provision)throw new RuntimeException("Unexpected schema write");return ["waypoints"=>[["sequence"=>1,"latitude"=>-0.79,"longitude"=>120,"status"=>"pending"]]];}')
 def request(payload,row,method='POST'):
  env=os.environ.copy();env['NAV_FIXTURE']=json.dumps({'input':payload,'row':row,'method':method});r=subprocess.run([php,'-n',str(d/'driver_navigation_state.php')],capture_output=True,text=True,env=env);assert r.returncode==0,r.stderr;return json.loads(r.stdout)
 def check(value,label):
  global checks
  assert value,label;checks+=1;print('PASS '+label)
 row={'id':2,'order_id':'A','driver':' DriverA ','status':'on_delivery','delivery_lat':-.79,'delivery_lng':120,'delivery_otp':'secret','token':'secret'}
 r=request({'order_id':'A'},row);check(r['success'] and r['order']['delivery_lat']==-.79,'assigned driver reads current destination');check('delivery_otp' not in r['order'] and 'token' not in r['order'],'navigation response excludes OTP and credentials');check(r['order']['source']=='orders' and r['order']['ecosystem']['waypoints'],'canonical source and stop list survive compact response')
 r=request({'order_id':'A'},dict(row,driver='other'));check(not r['success'] and r['status_code']==404,'other driver cannot read navigation state')
 r=request({'order_id':'A','source':'pickup_orders'},dict(row,driver='',driver_username='DriverA'));check(r['success'] and r['order']['source']=='pickup_orders','TransSend ownership uses driver_username fallback')
 r=request({'order_id':'A','source':'orders;DROP TABLE orders'},row);check(not r['success'] and r['status_code']==422,'source table input is restricted to allowlist')
 check(request({'order_id':'A'},row,'GET')['status_code']==405,'only authenticated POST contract is accepted');check(request({'order_id':''},row)['status_code']==422,'empty order identifier is rejected');check(request({'order_id':'A'},None)['status_code']==404,'missing order cannot return unrelated navigation')
 for state in ['finished','cancelled_by_customer']:
  r=request({'order_id':'A'},dict(row,status=state));check(r['order']['status']==state,'terminal '+state+' is returned unchanged')
 # Exercise actual profile helper, with no HTTP or DB dependency.
 for name in ['driver_navigation_route_helper.php','driver_navigation_comfort.php','transiva_server_route.php','schema_runtime_guard.php']:shutil.copy2(server/name,d/name)
 (d/'strict.php').write_text('''<?php require 'driver_navigation_route_helper.php';try{dnr_profile('motor',['motor'=>[],'car'=>['https://car.example/route/'],'require_motor'=>true]);exit(1);}catch(TransivaRouteException $e){if($e->routeCode!=='MOTOR_PROFILE_UNAVAILABLE')exit(2);}echo 'OK';''')
 r=subprocess.run([php,'-n',str(d/'strict.php')],capture_output=True,text=True);check(r.returncode==0 and r.stdout=='OK','strict motor configuration refuses silent car fallback')
 print('NAVIGATION STATE SERVER: '+str(checks)+' checks PASS')
