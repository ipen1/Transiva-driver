#!/usr/bin/env python3
"""Actual endpoint source with isolated transaction/savepoint fixture; no production DB/network.
Usage: PHP_BIN=/path/php python3 wallet_final_regression.py /path/server
"""
import os,json,shutil,subprocess,tempfile,sys
from pathlib import Path
source=Path(sys.argv[1]).resolve();php=os.environ.get('PHP_BIN','php');checks=0
def verify(ok,title):
    global checks
    if not ok:raise AssertionError(title)
    checks+=1;print('PASS',title)
with tempfile.TemporaryDirectory(prefix='transiva-wallet-final-') as directory:
    root=Path(directory)
    for name in ('requestDriverWithdraw.php','driver_wallet_transfer.php','driver_wallet_operation_status.php','customer_wallet_operation_helper.php'):
        shutil.copy2(source/name,root/name)
    shutil.copy2(Path(__file__).with_name('driver_wallet_endpoint_fixture.php'),root/'fixture.php')
    (root/'config.php').write_text("<?php require_once __DIR__.'/fixture.php';")
    for name in ('wallet_helper.php','p0_security_helper.php','wallet_transfer_security.php','fcm_helper.php'):(root/name).write_text('<?php // isolated fixture\n')
    state_path,payload_path=root/'state.json',root/'payload.json'
    def seed():state_path.write_text(json.dumps({'users':{'1':{'username':'sender','role':'driver','balance':100000},'2':{'username':'receiver','role':'driver','balance':20000}},'receipts':[],'withdrawals':[],'requests':[],'ledger':[],'audit':[],'fees':[],'notifications':[]}))
    def call(action,payload,failure='',owner=1,engine=''):
        payload_path.write_text(json.dumps(payload));env=dict(os.environ,FIXTURE_STATE=str(state_path),FIXTURE_PAYLOAD=str(payload_path),FIXTURE_FAIL=failure,FIXTURE_OWNER=str(owner),FIXTURE_ENGINE=engine)
        result=subprocess.run([php,'-n',str(root/('requestDriverWithdraw.php' if action=='withdraw' else 'driver_wallet_'+action+'.php'))],cwd=root,env=env,capture_output=True,text=True)
        if result.returncode:raise AssertionError(result.stdout+result.stderr)
        return json.loads(result.stdout),json.loads(state_path.read_text())
    withdraw=dict(amount=10000,bank_name='Bank',account_name='Sender',account_number='1234',note='test',method='bank',request_id='wd-final-1')
    transfer=dict(recipient='receiver',amount=10000,note='test',request_id='transfer-final-1',quote_token='valid')
    def query(action,payload,**kwargs):return call('operation_status',dict(action=action,operation_id=payload.get('request_id',payload.get('idempotency_key')),operation_owner_id=str(kwargs.get('owner',1))),**kwargs)
    for action,payload in [('withdraw',withdraw),('transfer',transfer)]:
        seed();before=json.loads(state_path.read_text());r,state=query(action,payload)
        verify(r['operation_state']=='not_found' and not r.get('terminal') and state==before,action+' status absent performs no mutation')
        error,after=query(action,payload,failure='fetch')
        verify(not error['success'] and error['operation_state']=='unknown' and after==before,action+' failed status read never claims not_found')
        r,state=call(action,payload)
        verify(r['success'] and r['terminal'] and state['users']['1']['balance']==90000,action+' commits success receipt')
        if action=='transfer':verify(len(state['notifications'])==2 and all(json.loads(x)['user_id'] in (1,2) for x in state['notifications'].values()),'transfer atomically stores two token-free notification intents')
        r,replayed=call(action,dict(payload,quote_token='expired') if action=='transfer' else payload)
        verify(r['success'] and r['replayed'] and replayed==state,action+' successful retry never debits or enqueues again')
        r,after=query(action,payload)
        verify(r['operation_state']=='succeeded' and r['receipt']['operation_id']==payload.get('request_id',payload.get('idempotency_key')) and after==state,action+' status returns committed receipt read-only')
        for failure in (['ledger','transfer_receipt','notification_queue'] if action=='transfer' else ['hold']):
            seed();r,state=call(action,payload,failure)
            verify(not r['success'] and r['terminal'] and r['operation_state']=='rejected' and state['users']['1']['balance']==100000 and state['users']['2']['balance']==20000 and not state['ledger'] and not state['notifications'],action+' '+failure+' persists rejection without financial changes')
            r,after=call(action,payload)
            verify(not r['success'] and r['replayed'] and r['operation_state']=='rejected' and after==state,action+' '+failure+' same ID cannot succeed after rejection')
            r,after=query(action,payload)
            verify(r['operation_state']=='rejected' and r['terminal'] and after==state,action+' '+failure+' final rejection is queryable without mutation')
        seed();r,state=call(action,payload,'receipt')
        verify(not r['success'] and r['operation_state']=='unknown' and not state['receipts'] and state['users']['1']['balance']==100000 and not state['notifications'],action+' failed final receipt persistence stays unknown')
        seed();r,state=call(action,payload,'commit_unknown')
        verify(not r['success'] and r['operation_state']=='unknown' and state['users']['1']['balance']==90000,action+' ambiguous commit never declares rejection')
        status,after=query(action,payload)
        verify(status['operation_state']=='succeeded' and after==state,action+' ambiguous commit recovered by read-only lookup')
        if action=='transfer':
            seed();r,state=call(action,payload,'deadlock')
            verify(not r['success'] and r['operation_state']=='unknown' and not state['receipts'] and state['users']['1']['balance']==100000 and not state['notifications'],action+' lost transaction/savepoint never claims durable rejection')
        seed();large=dict(payload,amount=150000);r,state=call(action,large)
        verify(r['operation_state']=='rejected' and r['terminal'] and state['users']['1']['balance']==100000,action+' insufficient balance is a durable rejection')
        state['users']['1']['balance']=200000;state_path.write_text(json.dumps(state));r,after=call(action,large)
        verify(not r['success'] and r['replayed'] and after['users']['1']['balance']==200000,action+' topup cannot reactivate a rejected ID')
        seed();r,state=call(action,payload,engine='MyISAM')
        verify(not r['success'] and state['users']['1']['balance']==100000,action+' refuses nontransactional storage')
        seed();r,state=call(action,dict(payload,operation_owner_id='2'))
        verify(not r['success'] and not state['receipts'] and state['users']['1']['balance']==100000,action+' wrong owner cannot retarget operation')
    seed();r,state=call('transfer',transfer);status,after=query('transfer',transfer,owner=2)
    verify(status['operation_state']=='not_found' and after==state,'another account cannot read sender receipt')
    state['receipts']=[];state_path.write_text(json.dumps(state));status,after=query('transfer',transfer)
    verify(status['operation_state']=='succeeded' and after==state,'legacy transfer status is read without importing receipt')
    seed();r,state=call('transfer',dict(transfer,quote_token='expired'));r2,after=call('transfer',transfer)
    verify(r['operation_state']=='rejected' and r2['operation_state']=='rejected' and after['users']['1']['balance']==100000,'expired quote rejection remains terminal with a later valid quote')
    seed();r,state=call('withdraw',withdraw);r2,after=call('withdraw',dict(withdraw,amount=11000))
    verify(not r2['success'] and r2['operation_state']=='unknown' and after==state,'changed payload cannot overwrite a prior receipt')
    seed();state=json.loads(state_path.read_text());state['users']['2']['role']='customer';state_path.write_text(json.dumps(state));r,after=call('transfer',transfer)
    verify(not r['success'] and r['operation_state']=='rejected' and after['users']['1']['balance']==100000 and after['users']['2']['balance']==20000,'driver transfer rejects a customer recipient before debit')
    seed();r,state=call('transfer',transfer);r2,after=call('transfer',dict(transfer,note='changed'))
    verify(not r2['success'] and after==state,'changed transfer note cannot overwrite a committed receipt')
    seed();state=json.loads(state_path.read_text());state['receipts']={'1|driver_withdraw|'+withdraw['request_id']:json.dumps({'success':True,'message':'Legacy hold','balance':90000})};state['users']['1']['balance']=90000;state_path.write_text(json.dumps(state))
    r,after=query('withdraw',withdraw);verify(r['operation_state']=='succeeded' and after==state,'legacy driver withdrawal receipt is readable without another debit')
print('PASS',checks,'wallet final endpoint fixture scenarios')
