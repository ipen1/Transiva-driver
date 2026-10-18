package com.transiva.app;
/** Pure policy, independent of Android, used by diagnostics and receipt retries. */
final class CallDeliveryPolicy {
    static final long RETAIN=24L*60*60*1000;
    static boolean stage(String s){return s!=null&&!s.isEmpty()&&("|POLL_DISCOVERED|PUSH_RECEIVED|TARGET_VALIDATED|TARGET_REJECTED|CALL_EXPIRED|DUPLICATE_IGNORED|NOTIFICATION_BLOCKED|NOTIFICATION_FAILED|NOTIFICATION_POSTED|SERVICE_REQUESTED|SERVICE_DENIED|SERVICE_STARTED|RINGING_STARTED|RINGING_UNCONFIRMED|CALL_UI_OPENED|ACCEPT_REQUESTED|REJECT_REQUESTED|ALERT_STOPPED|").contains("|"+s+"|");}
    static boolean fresh(long created,long now){return created>0&&now>=created&&now-created<RETAIN;}
    static boolean retry(int http,int attempt){return attempt<7&&(http==0||http==408||http==429||http>=500);}
    static boolean networkDue(long now,long last){return last<0||now<last||now-last>=15000;}
    static String readiness(boolean session,boolean registered,boolean permission,String error){
        if(!session)return "Sesi notifikasi belum siap. Ketuk untuk memeriksa.";
        if(!permission)return "Aktifkan notifikasi agar panggilan bisa masuk.";
        if(registered)return "";
        return error==null||error.isEmpty()?"Menyiapkan notifikasi…":"Notifikasi belum siap. Ketuk untuk memeriksa.";
    }
}
