package com.transiva.app;

/** Bounded per-outage recovery, with a per-call ceiling. Caller alone may re-offer. */
final class CallRecoveryPolicy {
    static final String CAPABILITY="a=x-transiva-ice-restart:1";
    private long began=-1,lastAttempt=-1;
    private int attempts,total;
    void begin(long now){if(began<0){began=now;attempts=0;lastAttempt=-1;}}
    boolean expired(long now){return began>=0&&now-began>=25000;}
    boolean takeRestart(long now,boolean caller,boolean supported){
        if(began<0||!caller||!supported||expired(now)||attempts>=2||total>=6
                ||now-began<2500||(lastAttempt>=0&&now-lastAttempt<8000))return false;
        ++attempts;++total;lastAttempt=now;return true;
    }
    void connected(){began=-1;attempts=0;lastAttempt=-1;}
    static long candidateDelay(int attempts){return attempts>=3?-1:attempts<=1?500:1500;}
    static boolean supports(String sdp){
        if(sdp==null)return false;
        for(String line:sdp.split("\\r?\\n"))if(CAPABILITY.equals(line.trim()))return true;
        return false;
    }
    static String advertise(String sdp){
        return supports(sdp)?sdp:sdp+(sdp.endsWith("\n")?"":"\r\n")+CAPABILITY+"\r\n";
    }
}
