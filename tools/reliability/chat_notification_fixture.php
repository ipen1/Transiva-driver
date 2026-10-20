<?php
// In-memory collaborators for executing the real endpoints; no DB credentials/network.
class mysqli {}
class NoticeResult { public array $rows; function __construct(array $r){$this->rows=$r;} }
class NoticeStatement { public string $sql;public array $args=[];public array $rows=[];function __construct(string $s){$this->sql=$s;} }
$fixture=json_decode(file_get_contents(getenv('NOTICE_INPUT')),true,512,JSON_THROW_ON_ERROR);$conn=new mysqli();$delivered=[];
function native_require_driver($c){return ['id'=>1,'username'=>'driver-a','role'=>'driver'];}
function native_out($success,$message,$extra=[],$status=200,$code=''){echo json_encode(array_merge(['success'=>$success],$extra));exit;}
function native_table_exists($c,$table){return in_array($table,['orders','pickup_orders','chat_messages'],true);}
function native_column_exists($c,$table,$col){return in_array($col,['driver','room_id'],true);}
function mysqli_query($c,$sql){global $fixture;$max=max(array_merge([0],array_column($fixture['messages'],'id')));return new NoticeResult([['newest'=>$max]]);}
function mysqli_free_result($r){}
function mysqli_prepare($c,$sql){return new NoticeStatement($sql);}
function mysqli_stmt_bind_param($s,$types,&...$args){$s->args=$args;return true;}
function mysqli_stmt_execute($s){
 global $fixture;$sql=$s->sql;$p=$s->args;
 if(str_contains($sql,'FROM users')){$s->rows=[['id'=>$fixture['recipient_id']??1,'fcm_token'=>$fixture['token']??'TEST-TOKEN']];}
 elseif(str_contains($sql,'FROM chat_messages WHERE id=')){$s->rows=array_values(array_filter($fixture['messages'],fn($m)=>(int)$m['id']===(int)$p[0]));}
 elseif(str_contains($sql,'FROM chat_messages WHERE UPPER')){
  if(!str_contains($sql,'is_read=0')||!str_contains($sql,'INTERVAL 1 HOUR')||!str_contains($sql,'ORDER BY id ASC LIMIT 100'))throw new RuntimeException('Missing production query safeguards');
  $rooms=array_slice($p,0,-2);$last=$p[count($p)-2];$watermark=$p[count($p)-1];
  $rows=array_filter($fixture['messages'],fn($m)=>in_array(strtoupper(str_replace('_','-',$m['room_id'])),$rooms,true)&&$m['id']>$last&&$m['id']<=$watermark&&$m['sender_type']==='customer'&&$m['is_read']===0&&$m['age_seconds']>=0&&$m['age_seconds']<=3600);
  usort($rows,fn($a,$b)=>$a['id']<=>$b['id']);$s->rows=array_slice($rows,0,100);
 }else{
  $table=str_contains($sql,'`pickup_orders`')?'pickup_orders':'orders';
  $rows=array_values(array_filter($fixture['orders'],fn($r)=>($r['_source']??'orders')===$table));
  if(str_contains($sql,'SELECT *')){
   if(!str_contains($sql,'LOWER(TRIM(status)) NOT IN'))throw new RuntimeException('Missing active-order SQL filter');
   $rows=array_values(array_filter($rows,fn($r)=>$r['driver']===$p[0]&&chat_notice_active($r['status'])));
  }else $rows=array_values(array_filter($rows,fn($r)=>in_array((string)$r['id'],array_map('strval',$p),true)||in_array($r['order_id'],$p,true)||in_array($r['room_id'],$p,true)));
  $s->rows=$rows;
 }return true;
}
function mysqli_stmt_get_result($s){return new NoticeResult($s->rows);}
function mysqli_fetch_assoc($r){return array_shift($r->rows);}
function mysqli_stmt_close($s){}
function send_fcm_notification($token,$title,$body,$data){global $delivered;$delivered[]=$data;return ['success'=>true];}
if(!function_exists('ctype_digit')){function ctype_digit($value){return is_string($value)&&preg_match('/^[0-9]+$/D',$value)===1;}}
