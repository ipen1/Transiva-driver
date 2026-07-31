package com.transiva.app;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/** Menjalankan pemeriksaan anti mock-location setiap aplikasi kembali ke foreground. */
public class TransivaDriverApplication extends Application implements Application.ActivityLifecycleCallbacks {
    @Override public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
    }

    @Override public void onActivityResumed(Activity activity) {
        if (!(activity instanceof SplashActivity)) {
            MockLocationGuard.enforce(activity);
        }
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityPaused(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
    @Override public void onActivityDestroyed(Activity activity) { }
}
