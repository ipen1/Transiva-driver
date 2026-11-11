package com.transiva.app;
import android.content.Context;
import java.util.Map;
/** Reject calls already in flight for a previous account/session. Legacy payloads remain compatible. */
final class FcmSessionTarget {
    static boolean accept(Context c,Map<String,String> data){
        if(data==null)return true;
        boolean call="webrtc_call".equalsIgnoreCase(value(data,"type"));
        boolean order="transiva_order".equalsIgnoreCase(value(data,"type"))&&"new_order".equalsIgnoreCase(value(data,"event"));
        boolean merchantChat="merchant_driver_chat".equalsIgnoreCase(value(data,"type"));
        if(!call&&!order&&!merchantChat)return true;
        String targetId=value(data,"target_user_id");
        String targetScope=value(data,"target_app_scope");
        String targetSession=value(data,"target_session_hash");
        if(merchantChat&&(targetId.isEmpty()||!"driver".equalsIgnoreCase(targetScope)||targetSession.isEmpty()))return false;
        SessionManager s=new SessionManager(c);String token=s.getToken();
        String user=s.getId();if(user==null||user.isEmpty())user=s.getUserId();
        boolean ok=s.isLoggedIn()&&token!=null&&!token.isEmpty()&&FcmRegistrationPolicy.matchesTarget(
            targetId,targetScope,targetSession,user,s.getRole(),FcmTokenCoordinator.hash(token));
        if(call)FcmTokenCoordinator.prefs(c).edit().putString("last_call_push",ok?"RECEIVED":"OWNER_MISMATCH").putLong("last_call_push_at",System.currentTimeMillis()).apply();
        if(call&&"incoming_call".equalsIgnoreCase(value(data,"event")))CallDeliveryTrace.record(c,data,ok?"TARGET_VALIDATED":"TARGET_REJECTED",ok?"":"OWNER_MISMATCH");
        if(!ok)android.util.Log.w("TransivaFcmRegistration",call?"CALL_PUSH_OWNER_MISMATCH":merchantChat?"MERCHANT_CHAT_PUSH_OWNER_MISMATCH":"ORDER_PUSH_OWNER_MISMATCH");
        return ok;
    }
    private static String value(Map<String,String>d,String k){String v=d.get(k);return v==null?"":v.trim();}
}
