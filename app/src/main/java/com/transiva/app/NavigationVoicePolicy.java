package com.transiva.app;
import java.util.HashMap;
import java.util.Map;

/** Monotonic announcement stages prevent noisy fixes from repeating or rewinding a prompt. */
public final class NavigationVoicePolicy {
    private final Map<Integer,Integer> announced=new HashMap<>();
    private long last=-100000;
    public void reset(){announced.clear();last=-100000;}
    public boolean shouldSpeak(int index,double meters,double speedMps,boolean arrival,long now){
        if(index<0||!Double.isFinite(meters)||meters<0)return false;
        double early=Math.max(140,Math.min(750,speedMps*25)),near=Math.max(35,Math.min(180,speedMps*7));
        int stage=meters<=18?3:meters<=near?2:meters<=early?1:0;
        if(arrival&&meters>18)return false;
        if(stage==0||stage<=announced.getOrDefault(index,0)||now-last<4500)return false;
        announced.put(index,stage);last=now;return true;
    }
}
