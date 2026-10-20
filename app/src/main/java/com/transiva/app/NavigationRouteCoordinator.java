package com.transiva.app;

import android.app.Activity;
import android.os.Handler;
import java.util.concurrent.*;

/** One active socket plus one latest queued request; all deliveries occur on the UI thread. */
public final class NavigationRouteCoordinator implements AutoCloseable {
    public interface Callback {
        String currentTargetKey();
        void ready(long generation,StableRouteEngine.RouteSet routes);
        void failed(Exception error);
    }
    private final android.content.Context context;
    private final java.util.function.BooleanSupplier alive;
    private final Handler main;
    private final Callback callback;
    private final DriverSessionOwner initialOwner;
    private final NavigationRequestGate gate=new NavigationRequestGate();
    private final ThreadPoolExecutor io=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),r->{Thread t=new Thread(r,"navigation-route");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private Future<?> future;
    private DriverSessionOwner requestOwner;
    private volatile boolean busy,closed;
    private String activeKey="";
    public NavigationRouteCoordinator(Activity a,Handler main,Callback callback){this(a,main,()->!a.isFinishing()&&!a.isDestroyed(),callback);}
    public NavigationRouteCoordinator(android.content.Context c,Handler main,java.util.function.BooleanSupplier alive,Callback callback){context=c;this.alive=alive;this.main=main;this.callback=callback;initialOwner=DriverSessionOwner.capture(c);}
    public boolean valid(){return !closed&&alive.getAsBoolean()&&initialOwner.current();}
    public boolean busy(){return busy;}
    public void request(String key,double fromLat,double fromLng,double toLat,double toLng,String profile,double heading,boolean force){
        if(!valid()||(busy&&key.equals(activeKey)))return;
        activeKey=key;cancelCurrent();long generation=gate.begin(key);busy=true;
        final DriverSessionOwner[] slot=new DriverSessionOwner[1];
        if(!initialOwner.runIfCurrent(()->slot[0]=DriverSessionOwner.capture(context))){busy=false;return;}
        DriverSessionOwner captured=slot[0];requestOwner=captured;
        try{future=io.submit(()->{
            StableRouteEngine.RouteSet result=null;Exception failure=null;
            try{initialOwner.check();captured.check();result=StableRouteEngine.fetchNavigation(captured,fromLat,fromLng,toLat,toLng,profile,heading,force);}
            catch(Exception e){failure=e;}
            final StableRouteEngine.RouteSet response=result;final Exception error=failure;
            main.post(()->{
                if(!valid()||!gate.accepts(generation,key)||!key.equals(callback.currentTargetKey())){if(gate.accepts(generation,key))busy=false;captured.close();return;}
                captured.runIfCurrent(()->{busy=false;if(error==null)callback.ready(generation,response);else callback.failed(error);});
                captured.close();
            });
        });}catch(RejectedExecutionException e){busy=false;captured.close();if(valid()&&gate.accepts(generation,key))callback.failed(e);}
    }
    private void cancelCurrent(){if(requestOwner!=null)requestOwner.close();if(future!=null)future.cancel(true);io.purge();}
    public void invalidate(){gate.invalidate();cancelCurrent();busy=false;}
    @Override public void close(){closed=true;gate.close();cancelCurrent();initialOwner.close();busy=false;io.shutdownNow();}
}
