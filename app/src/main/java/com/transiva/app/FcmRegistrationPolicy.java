package com.transiva.app;
/** Pure policy shared by foreground scheduling and persistent retries. */
final class FcmRegistrationPolicy {
    static final long PERIOD=6L*60*60*1000;
    static boolean due(boolean dirty,String owner,String ackOwner,long now,long ackAt){
        long age=now-ackAt;return dirty||!owner.equals(ackOwner)||age<0||age>=PERIOD;
    }
    static boolean retryable(int status,String code){
        return status==0||status==408||status==429||status>=500
            ||(status==409&&"FCM_REGISTRATION_STALE".equals(code));
    }
    static boolean matchesTarget(String targetId,String targetScope,String targetSession,String userId,String role,String session){
        return (targetId.isEmpty()||targetId.equals(userId))&&(targetScope.isEmpty()||targetScope.equalsIgnoreCase(role))
            &&(targetSession.isEmpty()||targetSession.equals(session));
    }
    static boolean mayAcknowledge(String expected,String current,long revision,long latest,boolean stopped){
        return !stopped&&expected.equals(current)&&revision==latest;
    }
}
