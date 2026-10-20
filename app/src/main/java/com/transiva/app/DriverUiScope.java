package com.transiva.app;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

/** Activity-owned work and callbacks, pinned to the identity that opened the page. */
public final class DriverUiScope {
    private final Activity activity;
    public final DriverSessionOwner owner;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final List<Future<?>> work=new ArrayList<>();
    private volatile boolean closed;
    public DriverUiScope(Activity a){activity=a;owner=DriverSessionOwner.capture(a);}
    public boolean alive(){return !closed&&!activity.isFinishing()&&!activity.isDestroyed()&&owner.current();}
    public void post(Runnable r){if(alive())main.post(()->owner.runIfCurrent(()->{if(!closed&&!activity.isFinishing()&&!activity.isDestroyed())r.run();}));}
    public synchronized boolean execute(Runnable r){
        if(closed)return false;
        work.removeIf(Future::isDone);
        try{work.add(DriverNetworkExecutor.executor().submit(()->{if(alive())r.run();}));return true;}
        catch(java.util.concurrent.RejectedExecutionException e){return false;}
    }
    public synchronized void close(){closed=true;main.removeCallbacksAndMessages(null);owner.close();for(Future<?> f:work)f.cancel(true);work.clear();}
}
