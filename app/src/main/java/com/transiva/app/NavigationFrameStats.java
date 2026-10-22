package com.transiva.app;

import java.util.Locale;

/** Constant-space window render timing, separate from map renderer FPS. */
final class NavigationFrameStats {
    private final long[] histogram=new long[201];
    private long frames,slow,dropped,totalNs,maxNs;
    void offer(long ns,long deadlineNs,int droppedReports){
        if(ns<=0||deadlineNs<=0)return;
        frames++;if(ns>=deadlineNs)slow++;dropped+=Math.max(0,droppedReports);totalNs+=ns;maxNs=Math.max(maxNs,ns);
        histogram[(int)Math.min(200,ns/1_000_000L)]++;
    }
    long frames(){return frames;}
    double percentile95(){long wanted=(long)Math.ceil(frames*.95),n=0;if(frames==0)return 0;for(int i=0;i<histogram.length;i++){n+=histogram[i];if(n>=wanted)return i==200?maxNs/1_000_000d:i+1;}return 0;}
    String summary(double hz){return frames==0?"Belum ada sampel frame.":String.format(new Locale("id","ID"),"Layar %.0f Hz • %,d sampel\nRender rata-rata %.1f ms • P95 ≤ %.1f ms\nLewat anggaran %.1f%% • maksimum %.1f ms\nLaporan terlewat: %d",hz,frames,totalNs/1_000_000d/frames,percentile95(),100d*slow/frames,maxNs/1_000_000d,dropped);}
}
