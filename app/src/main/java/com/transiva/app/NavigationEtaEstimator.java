package com.transiva.app;

/** Conservative observed ETA. Stationary delay is local evidence, not live traffic data. */
public final class NavigationEtaEstimator {
    private double speed,stopDelay,localFactor=1;
    private long factorFirst=-1,factorLast=-1;
    private long first,last,observed=-1,stopSince=-1;
    public void reset(){localFactor=1;factorFirst=factorLast=-1;speed=stopDelay=0;first=last=0;observed=stopSince=-1;}
    public void offer(long now,double metersPerSecond,double accuracy,boolean onRoute){
        if(!Double.isFinite(accuracy)||!Double.isFinite(metersPerSecond)||!onRoute||accuracy<=0||accuracy>25||metersPerSecond<0||metersPerSecond>50){
            stopSince=-1;observed=-1;if(last>0&&now-last>30000)reset();return;
        }
        if(observed>=0&&(now<observed||now-observed>15000))reset();
        double dt=observed<0?0:Math.max(0,Math.min(5,(now-observed)/1000d));observed=now;
        if(metersPerSecond<1.5){
            if(stopSince<0)stopSince=now;
            if(now-stopSince>=5000)stopDelay=Math.min(180,stopDelay+dt);
            return;
        }
        stopSince=-1;stopDelay*=Math.exp(-dt/30d);
        if(last>0&&now-last>30000){speed=0;first=last=0;}
        if(first==0)first=now;
        double alpha=last==0?1:Math.min(.3,Math.max(.02,(now-last)/30000d));speed=speed*(1-alpha)+metersPerSecond*alpha;last=now;
    }
    /** Compare against the current road's expected speed, not a highway/urban route average. */
    public void offer(long now,double metersPerSecond,double accuracy,boolean onRoute,double expectedSpeed){
        offer(now,metersPerSecond,accuracy,onRoute);
        if(!onRoute||!Double.isFinite(accuracy)||accuracy<=0||accuracy>25||!Double.isFinite(metersPerSecond)||metersPerSecond<1.5||metersPerSecond>50||!Double.isFinite(expectedSpeed)||expectedSpeed<1||expectedSpeed>55){factorFirst=factorLast=-1;localFactor=1;return;}
        if(factorLast<0||now<factorLast||now-factorLast>15000){factorFirst=now;factorLast=now;localFactor=1;}
        double alpha=1-Math.exp(-Math.min(5,Math.max(0,(now-factorLast)/1000d))/30d);
        double ratio=Math.max(.75,Math.min(2.5,expectedSpeed/metersPerSecond));
        localFactor+=alpha*(ratio-localFactor);factorLast=now;
    }
    public double seconds(long now,double meters,double providerSeconds,double providerMeters,double providerTotalSeconds){
        if(!Double.isFinite(providerSeconds)||providerSeconds<0)return 0;
        double result=providerSeconds;
        if(factorLast>=0&&now>=factorLast&&now-factorLast<=15000&&factorLast-factorFirst>=20000){result*=.5+.5*localFactor;}
        else if(last>0&&now-last<=15000&&last-first>=20000&&speed>0&&providerTotalSeconds>0&&Double.isFinite(providerMeters)){
            double factor=Math.max(.75,Math.min(2.5,(providerMeters/providerTotalSeconds)/speed));result*=.5+.5*factor;
        }
        if(meters>25&&observed>=0&&now>=observed&&now-observed<=15000)result+=stopDelay;
        return Math.max(0,result);
    }
}
