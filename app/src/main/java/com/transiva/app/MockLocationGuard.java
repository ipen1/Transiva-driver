package com.transiva.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Guard untuk menolak penggunaan aplikasi driver ketika Android mendeteksi
 * aplikasi mock-location yang dipilih atau lokasi terakhir berasal dari mock provider.
 */
public final class MockLocationGuard {
    public interface Callback {
        void onSafe();
        void onBlocked(String reason);
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean CHECK_RUNNING = new AtomicBoolean(false);
    private static final AtomicBoolean DIALOG_VISIBLE = new AtomicBoolean(false);
    private static volatile AlertDialog ACTIVE_DIALOG;
    private static final long MAX_STALE_MOCK_MS = 15_000L;
    private static final String[] KNOWN_MOCK_PACKAGES = new String[]{
            "com.lexa.fakegps",
            "com.blogspot.newapphorizons.fakegps",
            "com.incorporateapps.fakegps.fre"
    };

    private MockLocationGuard() {}

    public static void checkAsync(Context context, Callback callback) {
        checkAsync(context, callback, false);
    }

    /**
     * forcePolicyRefresh=true memaksa aplikasi membaca setting terbaru dari server,
     * sehingga perubahan ON/OFF di Web Admin tidak tertahan cache 5 menit.
     */
    public static void checkAsync(Context context, Callback callback, boolean forcePolicyRefresh) {
        final Context app = context.getApplicationContext();
        if (!CHECK_RUNNING.compareAndSet(false, true)) {
            MAIN.postDelayed(() -> checkAsync(app, callback, forcePolicyRefresh), 300L);
            return;
        }

        EXECUTOR.execute(() -> {
            DetectionResult result;
            try {
                DriverSecurityPolicy.Policy policy = forcePolicyRefresh
                        ? DriverSecurityPolicy.resolveFresh(app)
                        : DriverSecurityPolicy.resolve(app);

                if (!policy.fakeGpsEnabled) {
                    result = DetectionResult.safe();
                } else {
                    result = detect(app);

                    // Bila device akan diblokir, cek sekali lagi langsung ke server.
                    // Ini menangani kasus admin baru saja mematikan Fake GPS sementara
                    // aplikasi masih menyimpan policy ON di cache.
                    if (result.blocked && !forcePolicyRefresh) {
                        DriverSecurityPolicy.Policy fresh = DriverSecurityPolicy.resolveFresh(app);
                        if (!fresh.fakeGpsEnabled) {
                            result = DetectionResult.safe();
                        }
                    }
                }
            } catch (Throwable ignored) {
                result = DriverSecurityPolicy.fakeGpsEnabledCached(app)
                        ? detectSafely(app)
                        : DetectionResult.safe();
            } finally {
                CHECK_RUNNING.set(false);
            }

            DetectionResult finalResult = result;
            MAIN.post(() -> {
                if (callback == null) return;
                if (finalResult.blocked) callback.onBlocked(finalResult.reason);
                else callback.onSafe();
            });
        });
    }

    public static void enforce(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        checkAsync(activity, new Callback() {
            @Override public void onSafe() { dismissBlockingDialog(); }
            @Override public void onBlocked(String reason) {
                showBlockingDialog(activity, reason);
            }
        });
    }

    /** Dipanggil saat FCM memberi tahu bahwa admin baru mengubah policy. */
    public static void enforceFresh(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        checkAsync(activity, new Callback() {
            @Override public void onSafe() {
                dismissBlockingDialog();
            }

            @Override public void onBlocked(String reason) {
                showBlockingDialog(activity, reason);
            }
        }, true);
    }

    public static void dismissBlockingDialog() {
        MAIN.post(() -> {
            try {
                AlertDialog dialog = ACTIVE_DIALOG;
                ACTIVE_DIALOG = null;
                DIALOG_VISIBLE.set(false);
                if (dialog != null && dialog.isShowing()) dialog.dismiss();
            } catch (Throwable ignored) {
                DIALOG_VISIBLE.set(false);
            }
        });
    }

    private static DetectionResult detectSafely(Context context) {
        try { return detect(context); }
        catch (Throwable ignored) { return DetectionResult.safe(); }
    }

    private static DetectionResult detect(Context context) {
        String selectedPackage = findSelectedMockLocationPackage(context);
        if (!TextUtils.isEmpty(selectedPackage)) {
            String label = getAppLabel(context, selectedPackage);
            return DetectionResult.blocked("Aplikasi lokasi palsu aktif: " + label);
        }

        String known = findEnabledKnownMockApp(context);
        if (!TextUtils.isEmpty(known)) {
            return DetectionResult.blocked("Aplikasi lokasi palsu terpasang dan aktif: " + getAppLabel(context, known));
        }
        if (hasFreshMockLocation(context)) {
            return DetectionResult.blocked("Perangkat masih mengirim koordinat dari lokasi palsu.");
        }
        return DetectionResult.safe();
    }

    private static String findSelectedMockLocationPackage(Context context) {
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        PackageManager pm = context.getPackageManager();
        if (appOps == null || pm == null) return null;

        List<ApplicationInfo> apps;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                apps = pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0));
            } else {
                //noinspection deprecation
                apps = pm.getInstalledApplications(0);
            }
        } catch (Throwable ignored) {
            return null;
        }

        for (ApplicationInfo info : apps) {
            if (info == null || context.getPackageName().equals(info.packageName)) continue;
            try {
                int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION,
                        info.uid, info.packageName);
                if (mode == AppOpsManager.MODE_ALLOWED) return info.packageName;
            } catch (Throwable ignored) { }
        }
        return null;
    }


    /** Called from every live location callback. */
    public static boolean isMock(Location location) {
        if (location == null) return false;
        try {
            return Build.VERSION.SDK_INT >= 31 ? location.isMock() : location.isFromMockProvider();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String findEnabledKnownMockApp(Context context) {
        PackageManager pm = context.getPackageManager();
        for (String packageName : KNOWN_MOCK_PACKAGES) {
            try {
                ApplicationInfo info;
                if (Build.VERSION.SDK_INT >= 33) {
                    info = pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0));
                } else {
                    //noinspection deprecation
                    info = pm.getApplicationInfo(packageName, 0);
                }
                if (info.enabled) return packageName;
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static boolean hasFreshMockLocation(Context context) {
        if (Build.VERSION.SDK_INT >= 23
                && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) return false;
        long now = System.currentTimeMillis();
        try {
            List<String> providers = manager.getAllProviders();
            if (providers == null) return false;
            for (String provider : providers) {
                Location location;
                try {
                    location = manager.getLastKnownLocation(provider);
                } catch (SecurityException ignored) {
                    continue;
                } catch (Throwable ignored) {
                    continue;
                }
                if (location == null) continue;
                long age = Math.abs(now - location.getTime());
                if (age > MAX_STALE_MOCK_MS) continue;
                boolean mock = Build.VERSION.SDK_INT >= 31
                        ? location.isMock()
                        : location.isFromMockProvider();
                if (mock) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    public static void showBlockingDialog(Activity activity, String reason) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (!DIALOG_VISIBLE.compareAndSet(false, true)) return;

        Runnable show = () -> {
            if (activity.isFinishing() || activity.isDestroyed()) {
                DIALOG_VISIBLE.set(false);
                return;
            }
            try {
                AlertDialog dialog = PremiumDialogs.builder(activity)
                        .setTitle("Lokasi palsu terdeteksi")
                        .setMessage((TextUtils.isEmpty(reason) ? "Aplikasi lokasi palsu sedang aktif." : reason)
                                + "\n\nUntuk keamanan order dan customer, aplikasi Driver tidak dapat digunakan. "
                                + "Nonaktifkan pilihan aplikasi lokasi palsu di Opsi Developer, lalu tekan Periksa Lagi.")
                        .setCancelable(false)
                        .setPositiveButton("Buka Opsi Developer", null)
                        .setNeutralButton("Periksa Lagi", null)
                        .setNegativeButton("Tutup aplikasi", null)
                        .create();
                dialog.setOnShowListener(ignored -> {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                        try {
                            activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
                        } catch (Throwable e) {
                            activity.startActivity(new Intent(Settings.ACTION_SETTINGS));
                        }
                    });
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
                        // Periksa Lagi juga membaca policy terbaru dari server.
                        // Jadi bila admin baru mematikan Fake GPS, dialog langsung hilang
                        // tanpa menunggu cache policy berakhir.
                        checkAsync(activity, new Callback() {
                            @Override public void onSafe() {
                                ACTIVE_DIALOG = null;
                                DIALOG_VISIBLE.set(false);
                                dialog.dismiss();
                                Toast.makeText(activity,
                                        "Pemeriksaan keamanan diperbarui.",
                                        Toast.LENGTH_SHORT).show();
                            }
                            @Override public void onBlocked(String latestReason) {
                                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(true);
                                dialog.setMessage(latestReason
                                        + "\n\nDeteksi Fake GPS masih aktif untuk akun ini. "
                                        + "Nonaktifkan aplikasi lokasi palsu atau ubah policy dari Web Admin, lalu tekan Periksa Lagi.");
                            }
                        }, true);
                    });
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                        ACTIVE_DIALOG = null;
                        DIALOG_VISIBLE.set(false);
                        activity.finishAffinity();
                    });
                });
                dialog.setOnDismissListener(ignored -> {
                    if (ACTIVE_DIALOG == dialog) ACTIVE_DIALOG = null;
                    DIALOG_VISIBLE.set(false);
                });
                ACTIVE_DIALOG = dialog;
                dialog.show();
            } catch (Throwable ignored) {
                DIALOG_VISIBLE.set(false);
                Toast.makeText(activity, "Lokasi palsu terdeteksi. Aplikasi ditutup.", Toast.LENGTH_LONG).show();
                MAIN.postDelayed(activity::finishAffinity, 800L);
            }
        };

        if (Looper.myLooper() == Looper.getMainLooper()) MAIN.postDelayed(show, 120L);
        else MAIN.post(show);
    }

    private static String getAppLabel(Context context, String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo info;
            if (Build.VERSION.SDK_INT >= 33) {
                info = pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0));
            } else {
                //noinspection deprecation
                info = pm.getApplicationInfo(packageName, 0);
            }
            CharSequence label = pm.getApplicationLabel(info);
            return TextUtils.isEmpty(label) ? packageName : label.toString();
        } catch (Throwable ignored) {
            return packageName;
        }
    }

    private static final class DetectionResult {
        final boolean blocked;
        final String reason;
        private DetectionResult(boolean blocked, String reason) {
            this.blocked = blocked;
            this.reason = reason;
        }
        static DetectionResult safe() { return new DetectionResult(false, ""); }
        static DetectionResult blocked(String reason) { return new DetectionResult(true, reason); }
    }
}
