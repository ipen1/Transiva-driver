package com.transiva.app;
import android.content.Context;
import java.util.Map;
/** Reject calls already in flight for a previous account/session. Legacy payloads remain compatible. */
final class FcmSessionTarget {
    static boolean accept(Context c,Map<String,String> data){
        if(data==null)return true;
        boolean call="webrtc_call".equalsIgnoreCase(value(data,"type"));
        boolean order="transiva_order".equalsIgnoreCase(value(data,"type"))&&"new_order".equalsIgnoreCase(value(data,"event"));
        if(!call&&!order)return true;
        SessionManager s=new SessionManager(c);String token=s.getToken();
        String user=s.getId();if(user==null||user.isEmpty())user=s.getUserId();
        boolean ok=s.isLoggedIn()&&token!=null&&!token.isEmpty()&&FcmRegistrationPolicy.matchesTarget(
            value(data,"target_user_id"),value(data,"target_app_scope"),value(data,"target_session_hash"),user,s.getRole(),FcmTokenCoordinator.hash(token));
        if(call)FcmTokenCoordinator.prefs(c).edit().putString("last_call_push",ok?"RECEIVED":"OWNER_MISMATCH").putLong("last_call_push_at",System.currentTimeMillis()).apply();
        if(call&&"incoming_call".equalsIgnoreCase(value(data,"event")))CallDeliveryTrace.record(c,data,ok?"TARGET_VALIDATED":"TARGET_REJECTED",ok?"":"OWNER_MISMATCH");
        if(!ok)android.util.Log.w("TransivaFcmRegistration",call?"CALL_PUSH_OWNER_MISMATCH":"ORDER_PUSH_OWNER_MISMATCH");
        return ok;
    }
    private static String value(Map<String,String>d,String k){String v=d.get(k);return v==null?"":v.trim();}
}
