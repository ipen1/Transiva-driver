package com.transiva.app;
import android.content.Context;
import android.os.*;
import org.json.*;
import java.util.concurrent.*;
import com.transiva.app.driver.data.DriverApiClient;

/** One cancellable read-only poll, generation/session pinned; foreground/background share this owner. */
final class NavigationOrderSync implements AutoCloseable {
    interface Callback { JSONObject currentOrder(); void updated(JSONObject order); }
    private final Context context;private final Callback callback;private final Handler main=new Handler(Looper.getMainLooper());
    private final DriverSessionOwner owner;
    private final ThreadPoolExecutor io=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),r->{Thread t=new Thread(r,"navigation-order-state");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private DriverApiClient client;private Future<?> future;private boolean active,busy,closed;private long generation;private int failures;
    NavigationOrderSync(Context c,Callback cb){context=c.getApplicationContext();callback=cb;owner=DriverSessionOwner.capture(context);}
    void active(boolean value){
        if(closed||active==value)return;active=value;generation++;main.removeCallbacksAndMessages(null);
        if(client!=null)client.shutdown();if(future!=null)future.cancel(true);io.purge();busy=false;
        if(value){failures=0;main.post(tick);}
    }
    void refresh(){if(closed||!active)return;active(false);active(true);}
    private final Runnable tick=()->poll();
    private void poll(){
        if(closed||!active||busy||!owner.current())return;
        JSONObject order=callback.currentOrder();if(NavigationTargetPolicy.id(order).isEmpty())return;
        final String identity=NavigationTargetPolicy.identity(order),revision=NavigationTargetPolicy.revision(order);final long gen=++generation;
        final DriverApiClient call=new DriverApiClient(new SessionManager(context));client=call;busy=true;
        try{future=io.submit(()->{
            JSONObject result=null;Exception error=null;
            try{JSONObject q=new JSONObject().put("order_id",NavigationTargetPolicy.id(order)).put("source",NavigationTargetPolicy.source(order));result=call.post("driver_navigation_state.php",q).body.optJSONObject("order");if(result==null)throw new java.io.IOException("Missing navigation state");}
            catch(Exception e){error=e;}
            final JSONObject fresh=result;final Exception failed=error;
            main.post(()->{
                if(closed||!active||gen!=generation||!owner.current())return;busy=false;
                if(!revision.equals(NavigationTargetPolicy.revision(callback.currentOrder()))){main.postDelayed(tick,250);return;}
                if(failed==null&&identity.equals(NavigationTargetPolicy.identity(callback.currentOrder()))&&identity.equals(NavigationTargetPolicy.identity(fresh))){failures=0;try{owner.runIfCurrent(()->callback.updated(fresh));}catch(RuntimeException e){failures=1;TransivaDiagnostics.error(context,"navigation","ORDER_SYNC_APPLY_FAILED",e);}}
                else failures=Math.min(4,failures+1);
                if(active&&!closed&&gen==generation)main.postDelayed(tick,Math.min(30000L,5000L*(1L<<failures)));
            });call.shutdown();
        });}catch(RejectedExecutionException e){busy=false;call.shutdown();main.postDelayed(tick,5000);}
    }
    @Override public void close(){closed=true;active=false;generation++;main.removeCallbacksAndMessages(null);if(client!=null)client.shutdown();if(future!=null)future.cancel(true);io.shutdownNow();owner.close();}
}
