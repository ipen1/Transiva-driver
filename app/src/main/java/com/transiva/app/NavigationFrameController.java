package com.transiva.app;
import android.os.Handler;
import android.view.Choreographer;
import org.maplibre.android.maps.MapView;

/** Display-synchronized cadence. Deadlines retain phase instead of adding render cost to every frame. */
public final class NavigationFrameController {
    public interface Callback {void onVisualFrame();}
    private final long intervalNs;private final Callback callback;private boolean running;private long next;private Choreographer clock;
    public NavigationFrameController(Handler ignored,long frameMs,Callback cb){intervalNs=frameMs<=17?1000000000L/60:frameMs>=32&&frameMs<=34?1000000000L/30:Math.max(16,frameMs)*1000000L;callback=cb;}
    public void attachMapView(MapView ignored){}
    public void start(){if(running)return;running=true;next=0;clock=Choreographer.getInstance();clock.postFrameCallback(tick);}
    public void stop(){running=false;next=0;if(clock!=null)clock.removeFrameCallback(tick);}
    private final Choreographer.FrameCallback tick=new Choreographer.FrameCallback(){public void doFrame(long frameTimeNanos){
        if(!running)return;
        if(next==0||frameTimeNanos+500000L>=next){
            if(next==0||frameTimeNanos-next>intervalNs*2)next=frameTimeNanos+intervalNs;else next+=intervalNs;
            if(callback!=null)callback.onVisualFrame();
        }
        if(running)clock.postFrameCallback(this);
    }};
}
