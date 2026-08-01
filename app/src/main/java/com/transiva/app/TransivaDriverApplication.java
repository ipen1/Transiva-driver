package com.transiva.app;
import android.app.Activity; import android.app.Application; import android.os.Bundle;
public class TransivaDriverApplication extends Application implements Application.ActivityLifecycleCallbacks {
 @Override public void onCreate(){super.onCreate();TransivaDriverCrashReporter.initialize(this);registerActivityLifecycleCallbacks(this);}
 @Override public void onActivityResumed(Activity a){TransivaDriverCrashReporter.screen(a.getClass().getSimpleName());if(!(a instanceof SplashActivity)){MockLocationGuard.enforce(a);RootSecurityGuard.enforce(a);}}
 public void onActivityCreated(Activity a,Bundle b){} public void onActivityStarted(Activity a){} public void onActivityPaused(Activity a){} public void onActivityStopped(Activity a){} public void onActivitySaveInstanceState(Activity a,Bundle b){} public void onActivityDestroyed(Activity a){}
}
