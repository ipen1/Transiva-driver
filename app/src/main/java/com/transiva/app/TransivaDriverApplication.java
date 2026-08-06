package com.transiva.app;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;

public class TransivaDriverApplication extends Application implements Application.ActivityLifecycleCallbacks {
    private static volatile Context appContext;

    public static Context getAppContext() {
        return appContext;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
        TransivaDriverCrashReporter.initialize(this);
        registerActivityLifecycleCallbacks(this);
    }

    @Override
    public void onActivityResumed(Activity activity) {
        TransivaDriverCrashReporter.screen(activity.getClass().getSimpleName());
        if (!(activity instanceof SplashActivity)) {
            MockLocationGuard.enforce(activity);
            RootSecurityGuard.enforce(activity);
        }
    }

    public void onActivityCreated(Activity a, Bundle b) {}
    public void onActivityStarted(Activity a) {}
    public void onActivityPaused(Activity a) {}
    public void onActivityStopped(Activity a) {}
    public void onActivitySaveInstanceState(Activity a, Bundle b) {}
    public void onActivityDestroyed(Activity a) {}
}
