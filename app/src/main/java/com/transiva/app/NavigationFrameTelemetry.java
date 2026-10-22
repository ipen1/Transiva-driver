package com.transiva.app;

import android.app.Activity;
import android.content.Context;
import android.os.*;
import android.view.FrameMetrics;
import android.view.Window;

/** API 24+: platform frame metrics on a worker; never claims SurfaceView/map renderer FPS. */
final class NavigationFrameTelemetry implements AutoCloseable {
    private final Activity activity;private final String screen;private Session session;
    NavigationFrameTelemetry(Activity activity,String screen){this.activity=activity;this.screen=screen;}
    void start(){
        if(session!=null)return;
        try{session=new Session(activity,screen);session.window.addOnFrameMetricsAvailableListener(session,session.handler);}
        catch(RuntimeException e){if(session!=null){session.thread.quitSafely();session=null;}NavigationDiagnostics.error(activity,"FRAME_MONITOR_START",e);}
    }
    @Override public void close(){
        Session current=session;session=null;if(current==null)return;current.active=false;
        try{current.window.removeOnFrameMetricsAvailableListener(current);}catch(RuntimeException ignored){}
        current.handler.post(()->{try{current.publish();}finally{current.thread.quitSafely();}});
    }
    private static final class Session implements Window.OnFrameMetricsAvailableListener {
        final Window window;final Context context;final String screen;final HandlerThread thread;final Handler handler;
        final NavigationFrameStats stats=new NavigationFrameStats();final android.view.Display display;double hz;long fallbackBudget,rateAt;
        volatile boolean active=true;long published;
        Session(Activity activity,String screen){
            window=activity.getWindow();context=activity.getApplicationContext();this.screen=screen;
            display=activity.getWindowManager().getDefaultDisplay();double rate=display.getRefreshRate();hz=Double.isFinite(rate)&&rate>=30?rate:60;fallbackBudget=(long)(1_000_000_000d/hz);
            thread=new HandlerThread("Transiva-Frame-Metrics");thread.start();handler=new Handler(thread.getLooper());
        }
        @Override public void onFrameMetricsAvailable(Window ignored,FrameMetrics frame,int dropped){
            if(!active||frame.getMetric(FrameMetrics.FIRST_DRAW_FRAME)==1)return;
            long now=SystemClock.elapsedRealtime();
            if(now-rateAt>=1000){rateAt=now;try{double rate=display.getRefreshRate();if(Double.isFinite(rate)&&rate>=30){hz=rate;fallbackBudget=(long)(1_000_000_000d/hz);}}catch(RuntimeException ignoredRate){}}
            long budget=Build.VERSION.SDK_INT>=31?frame.getMetric(FrameMetrics.DEADLINE):fallbackBudget;
            stats.offer(frame.getMetric(FrameMetrics.TOTAL_DURATION),budget>0?budget:fallbackBudget,dropped);
            if(now-published>=15000){published=now;publish();}
        }
        void publish(){if(stats.frames()==0)return;try{String report=stats.summary(hz);
            context.getSharedPreferences("navigation_frame_metrics",0).edit().putString(screen,report).putLong(screen+"_at",System.currentTimeMillis()).apply();
            android.util.Log.i("TransivaNavFrames",screen+" "+report.replace('\n',' '));
            }catch(RuntimeException e){NavigationDiagnostics.error(context,"FRAME_MONITOR_SAVE",e);}
        }
    }
    static String report(Context context){
        android.content.SharedPreferences p=context.getSharedPreferences("navigation_frame_metrics",0);
        return section(p,"navigation","Navigasi utama")+"\n\n"+section(p,"trip","Driver Trip")+"\n\nIni waktu render jendela aplikasi; bukan FPS renderer peta. Uji perjalanan minimal 3–5 menit. Target anggaran: 60 Hz ≈16,7 ms; 90 Hz ≈11,1 ms; 120 Hz ≈8,3 ms.";
    }
    private static String section(android.content.SharedPreferences p,String key,String label){long at=p.getLong(key+"_at",0);return label+(at==0?"": " • "+java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(at)))+"\n"+p.getString(key,"Belum ada sampel. Buka halaman dan gunakan petanya terlebih dahulu.");}
}
