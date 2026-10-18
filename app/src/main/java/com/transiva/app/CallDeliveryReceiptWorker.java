package com.transiva.app;
import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
import org.json.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** Best-effort informational receipts. Stable event IDs make response-loss retries idempotent. */
public final class CallDeliveryReceiptWorker extends Worker {
    private volatile HttpURLConnection connection;
    public CallDeliveryReceiptWorker(@NonNull Context c,@NonNull WorkerParameters p){super(c,p);}
    @NonNull @Override public Result doWork(){
        Context c=getApplicationContext();if(FcmTokenCoordinator.disabled())return Result.success();
        FcmTokenCoordinator.Owner owner=FcmTokenCoordinator.owner(c);
        JSONArray batch=new JSONArray();Set<String> ids=new HashSet<>();
        synchronized(CallDeliveryTrace.LOCK){
            JSONArray all=CallDeliveryTrace.json(CallDeliveryTrace.prefs(c).getString("pending","[]")),keep=new JSONArray();
            for(int i=0;i<all.length();i++)try{JSONObject e=all.getJSONObject(i);
                if(owner!=null&&owner.session.equals(e.optString("owner"))&&CallDeliveryPolicy.fresh(e.optLong("created_at"),System.currentTimeMillis())){
                    keep.put(e);if(batch.length()<16){JSONObject clean=new JSONObject(e.toString());clean.remove("owner");batch.put(clean);ids.add(e.getString("event_id"));}
                }
            }catch(Exception ignored){}
            if(!CallDeliveryTrace.prefs(c).edit().putString("pending",keep.toString()).commit())return Result.retry();
        }
        if(owner==null||batch.length()==0)return Result.success();int status=0;
        try{
            HttpURLConnection http=open(c);connection=http;
            try{
                if(isStopped()||!FcmTokenCoordinator.current(c,owner))return Result.success();
                http.setRequestMethod("POST");http.setDoOutput(true);http.setInstanceFollowRedirects(false);http.setUseCaches(false);http.setConnectTimeout(6000);http.setReadTimeout(8000);
                http.setRequestProperty("Authorization","Bearer "+owner.bearer);http.setRequestProperty("X-App-Scope",FcmTokenCoordinator.role(c));http.setRequestProperty("X-Device-UUID",owner.uuid);http.setRequestProperty("Content-Type","application/json; charset=UTF-8");
                byte[] bytes=new JSONObject().put("events",batch).toString().getBytes(StandardCharsets.UTF_8);http.setFixedLengthStreamingMode(bytes.length);
                try(OutputStream out=http.getOutputStream()){out.write(bytes);}
                status=http.getResponseCode();String raw=read(status>=200&&status<300?http.getInputStream():http.getErrorStream());
                JSONObject response=new JSONObject(raw);
                if(status>=200&&status<300&&response.optBoolean("success",false)){
                    Set<String> acknowledged=values(response.optJSONArray("accepted")),rejected=values(response.optJSONArray("rejected"));
                    acknowledged.retainAll(ids);rejected.retainAll(ids);Set<String> done=new HashSet<>(acknowledged);done.addAll(rejected);
                    if(done.size()!=ids.size())throw new IOException("INCOMPLETE_RECEIPT_ACK");
                    if(isStopped()||!FcmTokenCoordinator.current(c,owner))return Result.success();
                    if(!remove(c,done,rejected.isEmpty()?"":"RECEIPT_REJECTED",!acknowledged.isEmpty(),owner))return Result.retry();
                    if(CallDeliveryTrace.json(CallDeliveryTrace.prefs(c).getString("pending","[]")).length()>0&&!CallDeliveryTrace.enqueue(c))return Result.retry();
                    return Result.success();
                }
                return failure(c,ids,status,"RECEIPT_HTTP_"+status,owner);
            }finally{http.disconnect();if(connection==http)connection=null;}
        }catch(Exception error){return failure(c,ids,0,"RECEIPT_IO_FAILED",owner);}
    }
    private Result failure(Context c,Set<String> ids,int status,String code,FcmTokenCoordinator.Owner owner){
        if(isStopped())return Result.retry();
        if(!FcmTokenCoordinator.current(c,owner))return Result.success();
        CallDeliveryTrace.prefs(c).edit().putString("receipt_error",code).putString("receipt_error_owner",owner.session).apply();
        if(CallDeliveryPolicy.retry(status,getRunAttemptCount()))return Result.retry();
        return remove(c,ids,code,false,owner)?Result.success():Result.retry();
    }
    private boolean remove(Context c,Set<String> ids,String error,boolean ack,FcmTokenCoordinator.Owner owner){
        synchronized(CallDeliveryTrace.LOCK){JSONArray all=CallDeliveryTrace.json(CallDeliveryTrace.prefs(c).getString("pending","[]")),keep=new JSONArray();
            for(int i=0;i<all.length();i++)try{JSONObject e=all.getJSONObject(i);if(!ids.contains(e.optString("event_id")))keep.put(e);}catch(Exception ignored){}
            android.content.SharedPreferences.Editor edit=CallDeliveryTrace.prefs(c).edit().putString("pending",keep.toString()).putString("receipt_error",error).putString("receipt_error_owner",owner.session);
            if(ack)edit.putLong("receipt_ack_at",System.currentTimeMillis()).putString("receipt_ack_owner",owner.session);return edit.commit();
        }
    }
    private static Set<String> values(JSONArray a){Set<String>s=new HashSet<>();if(a!=null)for(int i=0;i<a.length();i++)s.add(a.optString(i));return s;}
    private static String read(InputStream stream)throws IOException{
        if(stream==null)return "{}";try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[2048];int n;while((n=in.read(b))!=-1){if(out.size()+n>32768)throw new IOException("RESPONSE_TOO_LARGE");out.write(b,0,n);}return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    private static HttpURLConnection open(Context c)throws IOException{return com.transiva.app.driver.data.DriverConnectionRepository.open(DriverApiConfig.endpoint("call_delivery_receipt.php"));}
    @Override public void onStopped(){HttpURLConnection http=connection;if(http!=null)http.disconnect();super.onStopped();}
}
