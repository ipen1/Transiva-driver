<?php
// CLI-only deterministic DB/FCM fixture. Never loads production config or sends money/push.
if(PHP_SAPI!=='cli')exit(1);
class FixtureConnection { public array $state; public ?array $before=null; public ?array $savepoint=null; public int $insertId=0; function __construct(){ $this->state=json_decode(file_get_contents(getenv('FIXTURE_STATE')),true); } }
class FixtureStatement { public $conn; public string $sql; public array $params=[],$result=[],$outputs=[]; public int $affected=0,$position=0; function __construct($c,$s){$this->conn=$c;$this->sql=preg_replace('/\s+/',' ',trim($s));} }
function wallet_prepare($c,$sql){return new FixtureStatement($c,$sql);}
function mysqli_prepare($c,$sql){return wallet_prepare($c,$sql);}
function mysqli_stmt_bind_param($s,$types,&...$values){$s->params=[];foreach($values as &$v)$s->params[]=&$v;return true;}
function mysqli_stmt_bind_result($s,&...$values){foreach($values as &$v)$s->outputs[]=&$v;return true;}
function mysqli_stmt_execute($s){
 $c=$s->conn;$q=$s->sql;$p=$s->params;$s->affected=0;
 if(str_contains($q,'information_schema.TABLES')){$s->result=[[getenv('FIXTURE_ENGINE')?:'InnoDB']];}
 elseif(str_contains($q,'information_schema.STATISTICS')){$s->result=[['unique_slot','user_id,action_name,idem_key']];}
 elseif(str_starts_with($q,'INSERT INTO api_idempotency')){$key=$p[0].'|'.$p[1].'|'.$p[2];if(!isset($c->state['receipts'][$key])){$c->state['receipts'][$key]=$p[3];$s->affected=1;}}
 elseif(str_starts_with($q,'SELECT response_json FROM api_idempotency')){$key=count($p)===2?$p[0].'|driver_withdraw|'.$p[1]:$p[0].'|'.$p[1].'|'.$p[2];$s->result=isset($c->state['receipts'][$key])?[[$c->state['receipts'][$key]]]:[];}
 elseif(str_starts_with($q,'UPDATE api_idempotency')){if(getenv('FIXTURE_FAIL')==='receipt')return false;$key=$p[1].'|'.$p[2].'|'.$p[3];$c->state['receipts'][$key]=$p[0];$s->affected=1;}
 elseif(str_starts_with($q,'SELECT response_json FROM wallet_transfer_requests')){$r=$c->state['requests'][$p[1]]??null;$s->result=$r&&$r['sender']==$p[0]&&$r['status']==='success'?[[$r['response']??null]]:[];}
 elseif(str_starts_with($q,'SELECT status,response_json FROM wallet_transfer_requests')){$r=$c->state['requests'][$p[0]]??null;$s->result=$r&&$r['sender']==$p[1]?[[$r['status'],$r['response']??null]]:[];}
 elseif(str_starts_with($q,'SELECT r.amount')){$r=$c->state['requests'][$p[0]]??null;$s->result=$r&&$r['sender']==$p[1]?[[$r['amount'],$c->state['users'][$r['receiver']]['username'],$r['status'],$r['response']??null]]:[];}
 elseif(str_starts_with($q,'SELECT COUNT(*) FROM customer_withdrawals')){$s->result=[[count($c->state['withdrawals'])]];}
 elseif(str_starts_with($q,'SELECT fcm_token FROM users')){if(getenv('FIXTURE_FAIL')==='token_lookup')return false;$u=$c->state['users'][$p[0]]??null;$s->result=$u?[[$u['fcm_token']??'']]:[];}
 elseif(str_starts_with($q,'SELECT id, username, role, balance FROM users')){$u=$c->state['users'][$p[0]]??null;$s->result=$u?[[$p[0],$u['username'],$u['role'],$u['balance']]]:[];}
 elseif(str_starts_with($q,'SELECT id, username, balance FROM users')){$u=$c->state['users'][$p[0]]??null;$s->result=$u?[[$p[0],$u['username'],$u['balance']]]:[];}
 elseif(str_starts_with($q,'UPDATE users')){if(!isset($c->state['users'][$p[1]]))return false;$c->state['users'][$p[1]]['balance']=$p[0];$s->affected=1;}
 elseif(str_starts_with($q,'INSERT INTO driver_withdrawals')){if(getenv('FIXTURE_FAIL')==='hold')return false;$c->insertId=count($c->state['withdrawals'])+1;$c->state['withdrawals'][]=['id'=>$c->insertId,'owner'=>$p[0],'amount'=>$p[2]];$s->affected=1;}
 elseif(str_starts_with($q,'INSERT INTO customer_withdrawals')){$c->insertId=count($c->state['withdrawals'])+1;$c->state['withdrawals'][]=['id'=>$c->insertId,'owner'=>$p[0],'amount'=>$p[2]];$s->affected=1;}
 elseif(str_starts_with($q,'INSERT INTO wallet_transfer_requests')){if(isset($c->state['requests'][$p[0]]))return false;$c->state['requests'][$p[0]]=['sender'=>$p[1],'receiver'=>$p[2],'amount'=>$p[3],'status'=>'processing'];$s->affected=1;}
 elseif(str_starts_with($q,'UPDATE wallet_transfer_requests')){if(getenv('FIXTURE_FAIL')==='transfer_receipt')return false;$r=&$c->state['requests'][$p[2]];$r['status']='success';$r['response']=$p[1];$r['reference']=$p[0];$s->affected=1;}
 elseif(str_starts_with($q,'INSERT INTO wallet_platform_ledger')){$c->state['fees'][]=$p[1];$s->affected=1;}
 elseif(str_starts_with($q,'INSERT INTO order_notification_outbox')){if(getenv('FIXTURE_FAIL')==='notification_queue')return false;$c->state['notifications'][$p[0]]=$p[1];$s->affected=1;}
 else throw new RuntimeException('Fixture query unsupported: '.$q);
 return true;
}
function mysqli_stmt_fetch($s){if(getenv('FIXTURE_FAIL')==='fetch')return false;if(!isset($s->result[$s->position]))return null;$row=$s->result[$s->position++];foreach($s->outputs as $i=>&$v)$v=$row[$i];return true;}
function mysqli_stmt_close($s){}
function mysqli_stmt_affected_rows($s){return $s->affected;}
function mysqli_stmt_error($s){return 'injected';}
function mysqli_insert_id($c){return $c->insertId;}
function mysqli_errno($c){return 1062;}
function mysqli_query($c,$query){
 if($query==='SAVEPOINT customer_wallet_business'){$c->savepoint=$c->state;return true;}
 if($query==='ROLLBACK TO SAVEPOINT customer_wallet_business'){
  if(getenv('FIXTURE_FAIL')==='deadlock'){$c->state=$c->before??$c->state;$c->before=null;$c->savepoint=null;return false;}
  if($c->before===null||$c->savepoint===null)return false;$c->state=$c->savepoint;return true;
 }
 throw new RuntimeException('Fixture SQL unsupported: '.$query);
}
function mysqli_begin_transaction($c){if($c->before!==null)throw new RuntimeException('Nested transaction forbidden');$c->before=$c->state;return true;}
function mysqli_commit($c){if(getenv('FIXTURE_FAIL')==='commit_unknown'){$c->before=null;return false;}$c->before=null;return true;}
function mysqli_rollback($c){if($c->before!==null)$c->state=$c->before;$c->before=null;return true;}
function wallet_input(){return json_decode(file_get_contents(getenv('FIXTURE_PAYLOAD')),true);}
function wallet_bootstrap($c){}
function wallet_security_bootstrap($c){}
function p0_require_driver($c){return ['id'=>(int)(getenv('FIXTURE_OWNER')?:1),'username'=>'sender'];}
function p0_idempotency_key($key=''){return $key;}
function p0_client_ip(){return '127.0.0.1';}
function p0_rate_limit($c,...$args){if($c->before!==null)throw new RuntimeException('Rate limiter nested in money transaction');}
function wallet_user($c,$id,$username,$lock){return isset($c->state['users'][$id])?array_merge(['id'=>$id],$c->state['users'][$id]):null;}
function wallet_add_log($c,...$args){if(in_array(getenv('FIXTURE_FAIL'),['ledger','deadlock'],true))throw new RuntimeException('injected ledger failure');$c->state['ledger'][]=$args;}
function wallet_money($n){return 'Rp'.$n;}
function transiva_money($n){return wallet_money($n);}
function wallet_reference($s){return $s.'-fixture';}
function wallet_transfer_rules($c,$lock){return ['enabled'=>true,'minimum_amount'=>10000,'free_limit'=>5,'fee_amount'=>500];}
function wallet_monthly_transfer_count($c,$id){return 0;}
function wallet_rate_limit($c,$id){}
function wallet_audit($c,$id,$action,$status,$detail){$c->state['audit'][]=$status;}
function wallet_verify_quote($token){if($token==='expired')throw new RuntimeException('Quote expired');$p=wallet_input();return ['sender_id'=>1,'receiver_id'=>2,'amount'=>$p['amount'],'request_id'=>$p['request_id'],'receiver_username'=>'receiver','fee'=>0];}
function wallet_client_ip(){return '127.0.0.1';}
function wallet_user_agent(){return 'fixture';}
function transiva_get_user_by_id($c,$id){if(getenv('FIXTURE_FAIL')==='notification_lookup')throw new RuntimeException('injected lookup failure');return ['fcm_token'=>'fixture'];}
function transiva_send_fcm_token(...$args){throw new RuntimeException('injected push failure');}
function wallet_out($ok,$message,$extra=[],$code=200){global $conn;if($conn->before!==null)mysqli_rollback($conn);file_put_contents(getenv('FIXTURE_STATE'),json_encode($conn->state));echo json_encode(array_merge(['success'=>$ok,'message'=>$message],$extra));exit;}
$conn=new FixtureConnection();

register_shutdown_function(function(){global $conn;if($conn->before!==null)mysqli_rollback($conn);file_put_contents(getenv('FIXTURE_STATE'),json_encode($conn->state));});
