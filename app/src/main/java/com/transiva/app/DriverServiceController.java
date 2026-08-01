package com.transiva.app;
import android.content.Context; import android.content.Intent; import android.os.Build;
public final class DriverServiceController { private DriverServiceController(){}
 public static synchronized void start(Context c){if(c==null)return;SessionManager s=new SessionManager(c);if(!s.canRunDriverLocation())return;try{Intent i=new Intent(c,LocationService.class).setAction(LocationService.ACTION_START);if(Build.VERSION.SDK_INT>=26)c.startForegroundService(i);else c.startService(i);}catch(Throwable e){TransivaDriverCrashReporter.nonFatal("service_start",e);}}
 public static synchronized void stop(Context c){if(c==null)return;try{c.stopService(new Intent(c,LocationService.class));}catch(Throwable e){TransivaDriverCrashReporter.nonFatal("service_stop",e);}}
 public static synchronized void stopAll(Context c){if(c==null)return;stop(c);try{BackgroundSyncService.stop(c);}catch(Throwable ignored){}try{c.stopService(new Intent(c,TransivaDriverForegroundService.class));}catch(Throwable ignored){}}
}
