package com.transiva.app;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.google.firebase.messaging.FirebaseMessaging;

import java.lang.ref.WeakReference;

public class TransivaDriverApplication extends Application implements Application.ActivityLifecycleCallbacks {
    private static volatile Context appContext;
    private static volatile WeakReference<Activity> currentActivity = new WeakReference<>(null);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static Context getAppContext() {
        return appContext;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
        TransivaDriverCrashReporter.initialize(this);
        registerActivityLifecycleCallbacks(this);
        // Resource update startup dikendalikan SplashActivity agar progress selalu terlihat.

        // Global security changes use a topic so admin can refresh every Driver
        // without looping over thousands of FCM tokens.
        try {
            FirebaseMessaging.getInstance().subscribeToTopic("transiva_driver_security");
        } catch (Throwable ignored) { }
    }

    @Override
    public void onActivityResumed(Activity activity) {
        currentActivity = new WeakReference<>(activity);
        TransivaDriverCrashReporter.screen(activity.getClass().getSimpleName());
        if (!(activity instanceof SplashActivity)) {
            MockLocationGuard.enforce(activity);
            RootSecurityGuard.enforce(activity);
        }
        DriverBubbleController.onActivityResumed(activity);
        // Tombol Driver Lounge (chat global) adalah kontrol UI terpisah dari Messenger-style overlay.
        // Jangan sembunyikan tombol kiri hanya karena izin overlay aktif. DriverGlobalChatBubble.attach()
        // sendiri akan mengecualikan Splash/Login/PIN/halaman chat global.
        DriverGlobalChatBubble.attach(activity);
        // Re-apply after resume because OEMs can change navigation-bar insets
        // when switching gesture/3-button mode, PiP, keyboard, or immersive screens.
        try { DriverResponsiveUi.apply(activity); } catch (Throwable ignored) { }
    }

    /**
     * Dipanggil langsung oleh FCM saat admin mengubah Root/Fake GPS.
     * Bila aplikasi foreground, efek ON/OFF diterapkan saat itu juga.
     * Bila background, cache dihapus dan policy baru diterapkan saat Activity berikutnya dibuka.
     */
    public static void onSecurityPolicyChanged() {
        Context context = appContext;
        if (context == null) return;

        DriverSecurityPolicy.invalidate(context);

        MAIN.post(() -> {
            Activity activity = currentActivity.get();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            if (activity instanceof SplashActivity) return;

            // Keduanya fetch fresh; salah satu request dapat memakai cache hasil request pertama.
            MockLocationGuard.enforceFresh(activity);
            RootSecurityGuard.enforceFresh(activity);
        });
    }

    public void onActivityCreated(Activity a, Bundle b) {
        // Android 15/16 can enforce edge-to-edge for targetSdk 35+.
        // Apply the same safe-inset strategy used by the customer app so
        // bottom navigation/action controls never sit behind 3-button or gesture navigation.
        try { DriverResponsiveUi.apply(a); } catch (Throwable ignored) { }
    }
    public void onActivityStarted(Activity a) {}
    public void onActivityPaused(Activity a) {
        Activity current = currentActivity.get();
        if (current == a) currentActivity = new WeakReference<>(null);
    }
    public void onActivityStopped(Activity a) {}
    public void onActivitySaveInstanceState(Activity a, Bundle b) {}
    public void onActivityDestroyed(Activity a) {}
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW) RemoteImageLoader.clearMemory();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        RemoteImageLoader.clearMemory();
    }

}
