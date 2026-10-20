#!/usr/bin/env python3
"""Run real PHP chat endpoints/worker with isolated SQL/FCM collaborators (not live MySQL)."""
import json,os,shutil,subprocess,sys,tempfile
from pathlib import Path
server=Path(sys.argv[1]).resolve();php=os.environ.get('PHP_BIN','php');checks=0
with tempfile.TemporaryDirectory(prefix='transiva-chat-notice-') as temp:
 root=Path(temp)
 for f in ['get_driver_chat_updates.php','driver_chat_notification_status.php','chat_notification_helper.php','chat_notification_policy.php','chat_security_helper.php']:shutil.copy2(server/f,root/f)
 
 for endpoint in ['get_driver_chat_updates.php','driver_chat_notification_status.php']:
  p=root/endpoint;p.write_text(p.read_text().replace('catch(Throwable $error){','catch(Throwable $error){fwrite(STDERR,(string)$error);'))
 shutil.copy2(Path(__file__).with_name('chat_notification_fixture.php'),root/'native_auth.php')
 (root/'runner.php').write_text('''<?php $_SERVER['REQUEST_METHOD']='GET';$_GET=json_decode(getenv('NOTICE_QUERY'),true);if(getenv('NOTICE_MODE')==='worker'){require 'native_auth.php';require 'chat_notification_helper.php';chat_notification_deliver($conn,$fixture['payload']);echo json_encode(['deliveries'=>$delivered]);}else require getenv('NOTICE_MODE').'.php';''')
 def order(**kw):return dict(dict(id=1,user_id=2,order_id='O-1',room_id='ROOM-O-1',driver='driver-a',status='driver_accepted',_source='orders'),**kw)
 def message(**kw):return dict(dict(id=10,room_id='ROOM-O-1',sender_type='customer',message='hello',is_read=0,age_seconds=10),**kw)
 def call(mode='get_driver_chat_updates',orders=None,messages=None,query=None,**extra):
  fixture=dict(orders=[order()] if orders is None else orders,messages=[message()] if messages is None else messages,**extra)
  p=root/'input.json';p.write_text(json.dumps(fixture))
  env=dict(os.environ,NOTICE_INPUT=str(p),NOTICE_QUERY=json.dumps(query or {}),NOTICE_MODE=mode)
  r=subprocess.run([php,'-n',str(root/'runner.php')],cwd=root,env=env,capture_output=True,text=True)
  if r.returncode or r.stderr:raise AssertionError(r.stderr+r.stdout)
  return json.loads(r.stdout)
 def check(v,label):
  global checks
  if not v:raise AssertionError(label)
  checks+=1;print('PASS',label)
 r=call(query={'bootstrap':'1'});check(r['messages']==[] and r['last_id']==10 and r['notification_policy']==2,'fresh install bootstraps silently at server watermark')
 check(call(query={'last_id':10})['messages']==[],'persisted cursor never replays earlier rows')
 check(len(call(query={'last_id':9})['messages'])==1,'fresh unread active chat remains eligible')
 for status in ['finished','cancelled','canceled','expired','rejected','completed','merchant_rejected','']:
  check(call(orders=[order(status=status)])['messages']==[],f'poll suppresses terminal/invalid status {status!r}')
 check(call(messages=[message(is_read=1)])['messages']==[],'poll ignores already-read message')
 check(call(messages=[message(age_seconds=3601)])['messages']==[],'poll ignores stale message')
 check(call(messages=[message(age_seconds=-1)])['messages']==[],'poll rejects future timestamp')
 check(call(orders=[order(driver='driver-b')])['messages']==[],'poll cannot migrate to another driver')
 msgs=[message(id=i,room_id='ROOM-O-'+str(1+i%2)) for i in range(1,202)]
 orders=[order(),order(id=2,order_id='O-2',room_id='ROOM-O-2')];seen=[];last=0
 for _ in range(3):
  r=call(orders=orders,messages=msgs,query={'last_id':last});seen += [x['id'] for x in r['messages']];last=r['last_id']
 check(seen==list(range(1,202)),'global pagination preserves all 201 interleaved room messages')
 for state in ['driver_accepted','arrived_pickup','on_delivery','arrived_delivery']:
  check(len(call(orders=[order(status=state)])['messages'])==1,'standard order phase preserved: '+state)
 q={'chat_id':10,'room_id':'ROOM-O-1','order_id':'O-1','order_db_id':1}
 check(call(mode='driver_chat_notification_status',query=q)['eligible'],'fresh FCM alert validates with authenticated ownership')
 for label,kw in [('read',dict(messages=[message(is_read=1)])),('finished',dict(orders=[order(status='finished')])),('wrong account',dict(orders=[order(driver='driver-b')])),('old',dict(messages=[message(age_seconds=3601)])),('deleted',dict(messages=[])),('wrong room',dict(messages=[message(room_id='ROOM-OTHER')]))]:
  check(not call(mode='driver_chat_notification_status',query=q,**kw)['eligible'],'FCM validation rejects '+label)
 payload=dict(sender='customer',customer_id=2,driver='driver-a',title='Chat',body='hello',data=dict(chat_id='10',room_id='ROOM-O-1',order_id='O-1',order_db_id='1',order_source='orders'))
 r=call(mode='worker',payload=payload)
 check(len(r['deliveries'])==1 and r['deliveries'][0]['target_user_id']=='1' and r['deliveries'][0]['target_app_scope']=='driver','worker delivers fresh chat to current driver with target identity')
 check(int(r['deliveries'][0]['chat_expires_epoch'])-int(r['deliveries'][0]['chat_created_epoch'])==3600,'worker expiry is anchored to original message')
 for label,kw in [('read',dict(messages=[message(is_read=1)])),('finished',dict(orders=[order(status='finished')])),('old',dict(messages=[message(age_seconds=3601)])),('deleted',dict(messages=[])),('reassigned',dict(orders=[order(driver='driver-b')])),('wrong room',dict(messages=[message(room_id='ROOM-OTHER')]))]:
  check(call(mode='worker',payload=payload,**kw)['deliveries']==[],'worker consumes obsolete queued chat: '+label)
 p2=dict(payload,sender='driver',data=dict(payload['data'],sender_role='driver'))
 r=call(mode='worker',payload=p2,messages=[message(sender_type='driver')],recipient_id=2)
 check(len(r['deliveries'])==1 and r['deliveries'][0]['target_app_scope']=='customer','existing driver-to-customer chat delivery preserved')
 print('CHAT NOTIFICATION BACKEND:',checks,'checks PASS')
