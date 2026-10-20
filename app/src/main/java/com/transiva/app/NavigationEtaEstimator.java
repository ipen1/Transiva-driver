package com.transiva.app;
/** Observed on-route moving speed adjusts provider ETA conservatively; never claims live traffic. */
public final class NavigationEtaEstimator {
 private double speed;private long first,last;
 public void reset(){speed=0;first=last=0;}
 public void offer(long now,double metersPerSecond,double accuracy,boolean onRoute){
  if(!onRoute||accuracy>25||metersPerSecond<1.5||metersPerSecond>50){if(last>0&&now-last>30000)reset();return;}
  if(last>0&&now-last>30000)reset();if(first==0)first=now;
  double alpha=last==0?1:Math.min(.3,Math.max(.02,(now-last)/30000d));speed=speed*(1-alpha)+metersPerSecond*alpha;last=now;
 }
 public double seconds(long now,double meters,double providerSeconds,double providerMeters,double providerTotalSeconds){
  if(last==0||now-last>15000||last-first<20000||speed<=0||providerTotalSeconds<=0)return providerSeconds;
  double expected=providerMeters/providerTotalSeconds;double factor=Math.max(.75,Math.min(2.5,expected/speed));
  return Math.max(0,providerSeconds*(.5+.5*factor));
 }
}
