package com.transiva.app;
/** Monotonic bounded recovery timing, independent of whether old geometry is displayed. */
public final class NavigationRecoveryPolicy {
 private int failures; private long due; private boolean needed;
 public void reset(){failures=0;due=0;needed=false;}
 public void failed(long now){needed=true;failures=Math.min(6,failures+1);due=now+Math.min(30000,2000L*(1L<<(failures-1)));}
 public void require(long now){needed=true;if(due==0)due=now;}
 public void networkAvailable(long now){if(needed)due=now;}
 public boolean due(long now){return needed&&now>=due;}
 public boolean needed(){return needed;}
}
