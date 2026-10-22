package com.transiva.app;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Two monotonic stages per maneuver; distance noise never rewinds an announcement. */
public final class NavigationVoicePolicy {
    private final Map<Integer,Integer> announced=new HashMap<>();
    private final Set<Integer> cruising=new HashSet<>();
    private long last=-100000;
    public void reset(){announced.clear();cruising.clear();last=-100000;}
    static double early(double speed,boolean arrival){return arrival?200:speed>=50d/3.6?500:200;}
    static double near(double speed){return Math.max(20,Math.min(60,Math.max(0,speed)*3));}
    public boolean shouldSpeak(int index,double meters,double speed,boolean arrival,long now){return shouldSpeak(index,meters,speed,arrival,true,now);}
    public boolean shouldSpeak(int index,double meters,double speed,boolean arrival,boolean confirmed,long now){
        if(index<0||!Double.isFinite(meters)||meters<0||!Double.isFinite(speed))return false;
        int stage=arrival?(meters<=25&&confirmed?2:meters>25&&meters<=200?1:0):(meters<=near(speed)?2:meters<=early(speed,false)?1:0);
        if(stage==0||stage<=announced.getOrDefault(index,0)||now-last<(stage==2?1200:3000))return false;
        announced.put(index,stage);last=now;return true;
    }
    boolean cruise(int index,double meters,double speed,long now){
        if(index<0||!Double.isFinite(meters)||meters<=early(speed,false)+50||cruising.contains(index)||now-last<2500)return false;
        cruising.add(index);last=now;return true;
    }
    void retry(int index){announced.remove(index);cruising.remove(index);}
}
