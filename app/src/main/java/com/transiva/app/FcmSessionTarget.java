package com.transiva.app;
import android.content.Context;
import java.util.Map;
/** Reject calls already in flight for a previous account/session. Legacy payloads remain compatible. */
final class FcmSessionTarget {
    static boolean accept(Context c,Map<String,String> data){
        if(data==null||!"webrtc_call".equalsIgnoreCase(value(data,"type")))return true;
        SessionManager s=new SessionManager(c);String token=s.getToken();
        String user=s.getId();if(user==null||user.isEmpty())user=s.getUserId();
        boolean ok=s.isLoggedIn()&&token!=null&&!token.isEmpty()&&FcmRegistrationPolicy.matchesTarget(
            value(data,"target_user_id"),value(data,"target_app_scope"),value(data,"target_session_hash"),user,s.getRole(),FcmTokenCoordinator.hash(token));
        FcmTokenCoordinator.prefs(c).edit().putString("last_call_push",ok?"RECEIVED":"OWNER_MISMATCH").putLong("last_call_push_at",System.currentTimeMillis()).apply();
        if(!ok)android.util.Log.w("TransivaFcmRegistration","CALL_PUSH_OWNER_MISMATCH");
        return ok;
    }
    private static String value(Map<String,String>d,String k){String v=d.get(k);return v==null?"":v.trim();}
}
