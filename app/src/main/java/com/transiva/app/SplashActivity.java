package com.transiva.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/** Splash + security check + update gate. */
public class SplashActivity extends Activity {
    private boolean routed;
    private boolean checking;
    private boolean updateChecking;
    private static final int SPLASH_DELAY = 700;
    private TextView statusText;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout layout = new FrameLayout(this);
        layout.setBackgroundColor(Color.parseColor("#020617"));

        ImageView splash = new ImageView(this);
        int res = getDrawableId("splash_screen");
        if (res == 0) res = getDrawableId("transiva_logo");
        if (res == 0) res = getApplicationInfo().icon;
        splash.setImageResource(res);
        splash.setScaleType(ImageView.ScaleType.CENTER_CROP);
        layout.addView(splash, new FrameLayout.LayoutParams(-1, -1));

        statusText = new TextView(this);
        statusText.setText("Memeriksa keamanan aplikasi...");
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(14f);
        statusText.setGravity(android.view.Gravity.CENTER);
        statusText.setPadding(24, 14, 24, 14);
        statusText.setBackgroundColor(0x99000000);
        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(-1, -2);
        statusLp.gravity = android.view.Gravity.BOTTOM;
        statusLp.setMargins(28, 0, 28, 36);
        layout.addView(statusText, statusLp);
        setContentView(layout);

        new Handler(Looper.getMainLooper()).postDelayed(this::runSecurityCheck, SPLASH_DELAY);
    }

    @Override protected void onResume() {
        super.onResume();
        if (!routed && !checking && !updateChecking && statusText != null) {
            new Handler(Looper.getMainLooper()).postDelayed(this::runSecurityCheck, 250L);
        }
    }

    private void runSecurityCheck() {
        if (routed || checking || updateChecking || isFinishing() || isDestroyed()) return;
        checking = true;
        statusText.setText("Memeriksa keamanan lokasi...");
        RootSecurityGuard.checkAsync(this, new RootSecurityGuard.Callback() {
            @Override public void onSafe() { checkMockLocation(); }
            @Override public void onBlocked(String reason) {
                checking = false;
                statusText.setText("Perangkat tidak aman");
                finishAffinity();
            }
        });
    }

    private void checkMockLocation() {
        MockLocationGuard.checkAsync(this, new MockLocationGuard.Callback() {
            @Override public void onSafe() {
                checking = false;
                checkAppUpdate();
            }

            @Override public void onBlocked(String reason) {
                checking = false;
                statusText.setText("Lokasi palsu terdeteksi");
                MockLocationGuard.showBlockingDialog(SplashActivity.this, reason);
            }
        });
    }

    private void checkAppUpdate() {
        if (routed || updateChecking || isFinishing() || isDestroyed()) return;
        updateChecking = true;
        statusText.setText("Memeriksa versi Transiva Driver...");

        // Cache force sebelumnya tetap dihormati bahkan sebelum request baru selesai.
        AppUpdateInfo cached = AppUpdateStore.cachedInfo(this);
        int current = currentVersion();
        if (cached != null && cached.isForceRequired(current)) {
            updateChecking = false;
            openForcedUpdate();
            return;
        }

        AppUpdateClient.check(this, new AppUpdateClient.Callback() {
            @Override public void onResult(AppUpdateInfo info, boolean available) {
                runOnUiThread(() -> {
                    updateChecking = false;
                    if (isFinishing() || isDestroyed() || routed) return;
                    int installed = currentVersion();
                    if (info.isForceRequired(installed)) {
                        openForcedUpdate();
                        return;
                    }
                    if (available) {
                        // Update biasa langsung mulai di background tanpa menghalangi user.
                        try { AppUpdateDownloadManager.ensureDownload(SplashActivity.this, info); }
                        catch (Exception ignored) {}
                    }
                    statusText.setText("Aplikasi siap digunakan");
                    routeNext();
                });
            }

            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    updateChecking = false;
                    if (isFinishing() || isDestroyed() || routed) return;
                    // Fail-open saat endpoint update gangguan, kecuali cache sudah memaksa.
                    AppUpdateInfo old = AppUpdateStore.cachedInfo(SplashActivity.this);
                    if (old != null && old.isForceRequired(currentVersion())) {
                        openForcedUpdate();
                    } else {
                        statusText.setText("Membuka aplikasi...");
                        routeNext();
                    }
                });
            }
        });
    }

    private void openForcedUpdate() {
        if (routed) return;
        routed = true;
        Intent i = new Intent(this, UpdateDownloadActivity.class);
        i.putExtra(UpdateDownloadActivity.EXTRA_ROLE, "driver");
        i.putExtra(UpdateDownloadActivity.EXTRA_FORCE, true);
        i.putExtra(UpdateDownloadActivity.EXTRA_AUTO_START, true);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    private int currentVersion() {
        try { return AppUpdateClient.installedVersionCode(this); }
        catch (Exception ignored) { return 0; }
    }

    private void routeNext() {
        if (routed) return;
        routed = true;
        SessionManager session = new SessionManager(this);
        Intent intent;
        if (!session.isLoggedIn() || !"driver".equalsIgnoreCase(session.getRole())) {
            if (session.isLoggedIn()) session.forceLogout("driver_app_role_rejected");
            intent = new Intent(this, LoginActivity.class);
        } else {
            intent = new Intent(this, PinActivity.class);
            intent.putExtra("native_role", "driver");
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private int getDrawableId(String name) {
        try { return getResources().getIdentifier(name, "drawable", getPackageName()); }
        catch (Exception ignored) { return 0; }
    }
}
