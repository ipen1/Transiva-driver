package com.transiva.app;

import android.app.Activity;
import android.content.Intent;

import java.util.concurrent.atomic.AtomicBoolean;

/** Pemeriksaan update ringan ketika aplikasi kembali ke foreground. */
public final class AppUpdateRuntimeGate {
    private static final long CHECK_INTERVAL_MS = 5L * 60L * 1000L;
    private static final AtomicBoolean checking = new AtomicBoolean(false);
    private static volatile boolean launching;

    private AppUpdateRuntimeGate() {}

    public static void onActivityResumed(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        if (activity instanceof SplashActivity || activity instanceof UpdateDownloadActivity) return;

        int installed = installed(activity);
        AppUpdateInfo cached = AppUpdateStore.cachedInfo(activity);
        if (cached != null && cached.isForceRequired(installed)) {
            openForced(activity);
            return;
        }

        long age = System.currentTimeMillis() - AppUpdateStore.lastCheck(activity);
        if (age >= 0 && age < CHECK_INTERVAL_MS) {
            if (cached != null && cached.isUpdateAvailable(installed)) {
                try { AppUpdateDownloadManager.ensureDownload(activity, cached); } catch (Exception ignored) {}
            }
            return;
        }
        if (!checking.compareAndSet(false, true)) return;

        AppUpdateClient.check(activity.getApplicationContext(), new AppUpdateClient.Callback() {
            @Override public void onResult(AppUpdateInfo info, boolean available) {
                checking.set(false);
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    int current = installed(activity);
                    if (info.isForceRequired(current)) {
                        openForced(activity);
                    } else if (available) {
                        try { AppUpdateDownloadManager.ensureDownload(activity, info); } catch (Exception ignored) {}
                    }
                });
            }

            @Override public void onError(String message) {
                checking.set(false);
            }
        });
    }

    public static void clearLaunchingFlag() { launching = false; }

    private static synchronized void openForced(Activity activity) {
        if (launching || activity instanceof UpdateDownloadActivity) return;
        launching = true;
        Intent i = new Intent(activity, UpdateDownloadActivity.class);
        i.putExtra(UpdateDownloadActivity.EXTRA_ROLE, "driver");
        i.putExtra(UpdateDownloadActivity.EXTRA_FORCE, true);
        i.putExtra(UpdateDownloadActivity.EXTRA_AUTO_START, true);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        activity.startActivity(i);
    }

    private static int installed(Activity a) {
        try { return AppUpdateClient.installedVersionCode(a); }
        catch (Exception ignored) { return 0; }
    }
}
