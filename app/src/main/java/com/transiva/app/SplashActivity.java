package com.transiva.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Locale;

/** Splash + security/APK gate + MLBB-style resource update screen. */
public class SplashActivity extends Activity {
    private boolean routed;
    private boolean checking;
    private boolean updateChecking;
    private boolean resourceChecking;
    private static final int SPLASH_DELAY = 650;

    private TextView statusText, titleText, detailText, percentText, versionText, retryText, continueText;
    private ProgressBar progressBar;
    private LinearLayout updateCard;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        new Handler(Looper.getMainLooper()).postDelayed(this::runSecurityCheck, SPLASH_DELAY);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#020617"));

        ImageView splash = new ImageView(this);
        int res = getDrawableId("splash_screen");
        if (res == 0) res = getDrawableId("transiva_logo");
        if (res == 0) res = getApplicationInfo().icon;
        splash.setImageResource(res);
        splash.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(splash, new FrameLayout.LayoutParams(-1, -1));

        View shade = new View(this);
        shade.setBackgroundColor(0x32000000);
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        updateCard = new LinearLayout(this);
        updateCard.setOrientation(LinearLayout.VERTICAL);
        updateCard.setPadding(dp(22), dp(18), dp(22), dp(18));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE610172A);
        bg.setCornerRadius(dp(22));
        bg.setStroke(dp(1), 0x3348A7FF);
        updateCard.setBackground(bg);

        titleText = text("Menyiapkan Transiva Driver", 19, Color.WHITE, Typeface.BOLD);
        updateCard.addView(titleText);

        statusText = text("Memeriksa keamanan aplikasi...", 14, 0xFFF1F5F9, Typeface.NORMAL);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2); slp.topMargin = dp(7);
        updateCard.addView(statusText, slp);

        detailText = text("", 12, 0xFF94A3B8, Typeface.NORMAL);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, -2); dlp.topMargin = dp(4);
        updateCard.addView(detailText, dlp);

        LinearLayout progressRow = new LinearLayout(this);
        progressRow.setOrientation(LinearLayout.HORIZONTAL);
        progressRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams prlp = new LinearLayout.LayoutParams(-1, -2); prlp.topMargin = dp(14);
        updateCard.addView(progressRow, prlp);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100); progressBar.setProgress(0); progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, dp(8), 1f);
        progressRow.addView(progressBar, plp);

        percentText = text("0%", 12, Color.WHITE, Typeface.BOLD);
        percentText.setGravity(Gravity.END);
        percentText.setVisibility(View.GONE);
        LinearLayout.LayoutParams pplp = new LinearLayout.LayoutParams(dp(48), -2); pplp.leftMargin = dp(10);
        progressRow.addView(percentText, pplp);

        versionText = text("", 11, 0xFF64748B, Typeface.NORMAL);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-1, -2); vlp.topMargin = dp(9);
        updateCard.addView(versionText, vlp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2); alp.topMargin = dp(8);
        updateCard.addView(actions, alp);

        continueText = action("Lewati", false);
        continueText.setVisibility(View.GONE);
        continueText.setOnClickListener(v -> routeNext());
        actions.addView(continueText);

        retryText = action("Coba lagi", true);
        retryText.setVisibility(View.GONE);
        retryText.setOnClickListener(v -> checkResourceUpdate());
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2); rlp.leftMargin = dp(8);
        actions.addView(retryText, rlp);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(-1, -2);
        cardLp.gravity = Gravity.BOTTOM;
        cardLp.setMargins(dp(20), 0, dp(20), dp(26));
        root.addView(updateCard, cardLp);
        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        if (!routed && !checking && !updateChecking && !resourceChecking && statusText != null) {
            new Handler(Looper.getMainLooper()).postDelayed(this::runSecurityCheck, 250L);
        }
    }

    private void runSecurityCheck() {
        if (routed || checking || updateChecking || resourceChecking || isFinishing() || isDestroyed()) return;
        checking = true;
        setStage("Menyiapkan Transiva Driver", "Memeriksa keamanan aplikasi...", "", false, 0);

        SessionManager session = new SessionManager(this);
        if (!session.isLoggedIn() || !"driver".equalsIgnoreCase(session.getRole())) {
            checking = false;
            checkAppUpdate();
            return;
        }

        statusText.setText("Memeriksa keamanan lokasi...");
        RootSecurityGuard.checkAsync(this, new RootSecurityGuard.Callback() {
            @Override public void onSafe() { checkMockLocation(); }
            @Override public void onBlocked(String reason) {
                checking = false; statusText.setText("Perangkat tidak aman"); finishAffinity();
            }
        });
    }

    private void checkMockLocation() {
        MockLocationGuard.checkAsync(this, new MockLocationGuard.Callback() {
            @Override public void onSafe() { checking = false; checkAppUpdate(); }
            @Override public void onBlocked(String reason) {
                checking = false; statusText.setText("Lokasi palsu terdeteksi"); MockLocationGuard.showBlockingDialog(SplashActivity.this, reason);
            }
        });
    }

    private void checkAppUpdate() {
        if (routed || updateChecking || resourceChecking || isFinishing() || isDestroyed()) return;
        updateChecking = true;
        setStage("Menyiapkan Transiva Driver", "Memeriksa versi aplikasi...", "", false, 0);

        AppUpdateInfo cached = AppUpdateStore.cachedInfo(this);
        int current = currentVersion();
        if (cached != null && cached.isForceRequired(current)) { updateChecking = false; openForcedUpdate(); return; }

        AppUpdateClient.check(this, new AppUpdateClient.Callback() {
            @Override public void onResult(AppUpdateInfo info, boolean available) {
                runOnUiThread(() -> {
                    updateChecking = false;
                    if (isFinishing() || isDestroyed() || routed) return;
                    int installed = currentVersion();
                    if (info.isForceRequired(installed)) { openForcedUpdate(); return; }
                    if (available) {
                        try { AppUpdateDownloadManager.ensureDownload(SplashActivity.this, info); } catch (Exception ignored) {}
                    }
                    checkResourceUpdate();
                });
            }
            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    updateChecking = false;
                    if (isFinishing() || isDestroyed() || routed) return;
                    AppUpdateInfo old = AppUpdateStore.cachedInfo(SplashActivity.this);
                    if (old != null && old.isForceRequired(currentVersion())) openForcedUpdate();
                    else checkResourceUpdate();
                });
            }
        });
    }

    private void checkResourceUpdate() {
        if (routed || resourceChecking || isFinishing() || isDestroyed()) return;
        resourceChecking = true;
        retryText.setVisibility(View.GONE);
        continueText.setVisibility(View.GONE);
        setStage("Menyiapkan data Transiva", "Memeriksa data terbaru...", "Resource v" + ResourceUpdateManager.activeVersion(this), false, 0);

        ResourceUpdateManager.checkInteractive(this, new ResourceUpdateManager.Listener() {
            @Override public void onProgress(ResourceUpdateManager.Stage stage, int percent, long got, long total, int targetVersion, String message) {
                runOnUiThread(() -> {
                    if (routed || isFinishing() || isDestroyed()) return;
                    boolean showProgress = stage == ResourceUpdateManager.Stage.DOWNLOADING || stage == ResourceUpdateManager.Stage.VERIFYING || stage == ResourceUpdateManager.Stage.INSTALLING || stage == ResourceUpdateManager.Stage.COMPLETE;
                    String detail = "";
                    if (stage == ResourceUpdateManager.Stage.DOWNLOADING && total > 0) detail = human(got) + " / " + human(total);
                    else if (targetVersion > 0) detail = "Resource v" + targetVersion;
                    setStage(stage == ResourceUpdateManager.Stage.UPDATE_FOUND || showProgress ? "Pembaruan Transiva" : "Menyiapkan data Transiva", message, detail, showProgress, percent);
                });
            }

            @Override public void onFinished(boolean updated, int activeVersion) {
                runOnUiThread(() -> {
                    resourceChecking = false;
                    if (routed || isFinishing() || isDestroyed()) return;
                    if (updated) {
                        setStage("Transiva siap", "Pembaruan berhasil dipasang ✓", "Resource v" + activeVersion, true, 100);
                        new Handler(Looper.getMainLooper()).postDelayed(SplashActivity.this::routeNext, 650L);
                    } else {
                        setStage("Transiva siap", "Data sudah terbaru ✓", "Resource v" + activeVersion, false, 100);
                        new Handler(Looper.getMainLooper()).postDelayed(SplashActivity.this::routeNext, 250L);
                    }
                });
            }

            @Override public void onError(String message, boolean canContinue) {
                runOnUiThread(() -> {
                    resourceChecking = false;
                    if (routed || isFinishing() || isDestroyed()) return;
                    setStage("Pembaruan tertunda", "Data baru belum dapat dipasang", message, false, 0);
                    retryText.setVisibility(View.VISIBLE);
                    continueText.setVisibility(canContinue ? View.VISIBLE : View.GONE);
                });
            }
        });
    }

    private void setStage(String title, String status, String detail, boolean showProgress, int percent) {
        titleText.setText(title); statusText.setText(status); detailText.setText(detail == null ? "" : detail);
        progressBar.setVisibility(showProgress ? View.VISIBLE : View.GONE);
        percentText.setVisibility(showProgress ? View.VISIBLE : View.GONE);
        if (showProgress) { progressBar.setProgress(Math.max(0, Math.min(100, percent))); percentText.setText(Math.max(0, Math.min(100, percent)) + "%"); }
        versionText.setText("App " + versionName() + "  •  Resource " + ResourceUpdateManager.activeVersion(this));
    }

    private void openForcedUpdate() {
        if (routed) return;
        routed = true;
        Intent i = new Intent(this, UpdateDownloadActivity.class);
        i.putExtra(UpdateDownloadActivity.EXTRA_ROLE, "driver");
        i.putExtra(UpdateDownloadActivity.EXTRA_FORCE, true);
        i.putExtra(UpdateDownloadActivity.EXTRA_AUTO_START, true);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i); finish();
    }

    private int currentVersion() { try { return AppUpdateClient.installedVersionCode(this); } catch (Exception ignored) { return 0; } }
    private String versionName() { try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "-"; } }

    private void routeNext() {
        if (routed) return;
        routed = true;
        SessionManager session = new SessionManager(this);
        Intent intent;
        if (!session.isLoggedIn() || !"driver".equalsIgnoreCase(session.getRole())) {
            if (session.isLoggedIn()) session.forceLogout("driver_app_role_rejected");
            intent = new Intent(this, LoginActivity.class);
        } else {
            intent = new Intent(this, PinActivity.class); intent.putExtra("native_role", "driver");
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent); finish();
    }

    private TextView text(String s, int sp, int color, int style) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color); t.setTypeface(Typeface.create("sans", style)); return t;
    }
    private TextView action(String s, boolean primary) {
        TextView t = text(s, 13, Color.WHITE, Typeface.BOLD); t.setGravity(Gravity.CENTER); t.setPadding(dp(14), dp(9), dp(14), dp(9));
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(dp(12)); g.setColor(primary ? 0xFF0B7BFF : 0x332E8FFF); t.setBackground(g); return t;
    }
    private int getDrawableId(String name) { try { return getResources().getIdentifier(name, "drawable", getPackageName()); } catch (Exception ignored) { return 0; } }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private static String human(long b) { if (b < 1024) return b + " B"; if (b < 1024L*1024L) return String.format(Locale.US, "%.1f KB", b/1024f); return String.format(Locale.US, "%.1f MB", b/(1024f*1024f)); }
}
