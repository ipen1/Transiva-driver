package com.transiva.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;

/** Layar update Driver: DownloadManager background + force gate + verifikasi APK. */
public class UpdateDownloadActivity extends Activity {
    public static final String EXTRA_ROLE = "role";
    public static final String EXTRA_FORCE = "force_update";
    public static final String EXTRA_AUTO_START = "auto_start";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable pollDownload = new Runnable() {
        @Override public void run() {
            if (isFinishing() || isDestroyed()) return;
            AppUpdateDownloadManager.State state = AppUpdateDownloadManager.current(UpdateDownloadActivity.this);
            if (state == null) {
                downloading = false;
                actionButton.setVisibility(View.VISIBLE);
                actionButton.setText("Ulangi Download");
                statusView.setText("Download tidak ditemukan. Silakan ulangi.");
                return;
            }
            updateProgress(state.downloaded, state.total);
            if (state.complete()) {
                downloading = false;
                downloadedApk = state.file;
                verifyAndFinishDownload();
                return;
            }
            if (state.failed()) {
                downloading = false;
                downloadFailed("Download gagal (kode " + state.reason + "). Silakan ulangi.");
                AppUpdateStore.clearDownload(UpdateDownloadActivity.this);
                return;
            }
            downloading = true;
            main.postDelayed(this, 800L);
        }
    };

    private TextView titleView, versionView, statusView, percentView, sizeView, notesView, backView;
    private ProgressBar progressBar, checkingBar;
    private LinearLayout progressInfo;
    private Button actionButton;
    private AppUpdateInfo updateInfo;
    private File downloadedApk;
    private boolean downloading;
    private boolean dark;
    private boolean forcedMode;
    private boolean autoStart;
    private boolean installerOpened;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        forcedMode = getIntent().getBooleanExtra(EXTRA_FORCE, false);
        autoStart = getIntent().getBooleanExtra(EXTRA_AUTO_START, false);
        dark = DriverAppSettings.isDarkMode(this);
        setContentView(buildScreen());
        AppUpdateRuntimeGate.clearLaunchingFlag();
        checkUpdate();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        forcedMode = forcedMode || intent.getBooleanExtra(EXTRA_FORCE, false);
        autoStart = autoStart || intent.getBooleanExtra(EXTRA_AUTO_START, false);
        applyForceUi();
    }

    @Override protected void onResume() {
        super.onResume();
        AppUpdateRuntimeGate.clearLaunchingFlag();
        if (installerOpened) {
            installerOpened = false;
            // Jika install berhasil, Activity lama akan diganti proses Android.
            // Jika user batal, force gate tetap ada dan tombol Pasang muncul kembali.
            int current = currentVersion();
            AppUpdateInfo cached = AppUpdateStore.cachedInfo(this);
            if (cached != null && !cached.isUpdateAvailable(current)) {
                AppUpdateStore.clearDownload(this);
                goToSplash();
                return;
            }
        }
        AppUpdateDownloadManager.State state = AppUpdateDownloadManager.current(this);
        if (state != null && (state.running() || state.complete())) {
            startPollingExisting(state);
        }
    }

    @Override protected void onDestroy() {
        main.removeCallbacks(pollDownload);
        super.onDestroy();
    }

    private View buildScreen() {
        String bg = dark ? "#07111F" : "#F4F8FD";
        String card = dark ? "#111E2F" : "#FFFFFF";
        String primaryText = dark ? "#F3F8FF" : "#0B3A78";
        String secondary = dark ? "#AFC0D6" : "#64748B";

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(Color.parseColor(bg));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(12), dp(14), dp(8));
        backView = text("‹", 34, "#0B7CFF", true);
        backView.setGravity(Gravity.CENTER);
        backView.setOnClickListener(v -> {
            if (forcedMode) {
                Toast.makeText(this, "Pembaruan wajib dipasang untuk melanjutkan.", Toast.LENGTH_SHORT).show();
            } else if (!downloading) finish();
        });
        header.addView(backView, new LinearLayout.LayoutParams(dp(44), dp(44)));
        header.addView(text("Pembaruan Aplikasi", 22, primaryText, true));
        shell.addView(header);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(28));
        scroll.addView(root);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout cardView = new LinearLayout(this);
        cardView.setOrientation(LinearLayout.VERTICAL);
        cardView.setPadding(dp(20), dp(22), dp(20), dp(22));
        cardView.setGravity(Gravity.CENTER_HORIZONTAL);
        cardView.setBackground(round(card, 24));
        cardView.setElevation(dp(3));

        TextView icon = text("↻", 44, "#0B7CFF", true);
        icon.setGravity(Gravity.CENTER);
        cardView.addView(icon, new LinearLayout.LayoutParams(dp(72), dp(72)));

        titleView = text("Memeriksa pembaruan...", 20, primaryText, true);
        titleView.setGravity(Gravity.CENTER);
        cardView.addView(titleView);

        versionView = text("Versi terpasang " + AppUpdateClient.installedVersionName(this), 12, secondary, false);
        versionView.setGravity(Gravity.CENTER);
        versionView.setPadding(0, dp(5), 0, dp(15));
        cardView.addView(versionView);

        checkingBar = new ProgressBar(this);
        cardView.addView(checkingBar, new LinearLayout.LayoutParams(dp(44), dp(44)));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setVisibility(View.GONE);
        cardView.addView(progressBar, new LinearLayout.LayoutParams(-1, dp(12)));

        progressInfo = new LinearLayout(this);
        progressInfo.setGravity(Gravity.CENTER_VERTICAL);
        percentView = text("0%", 24, "#0B7CFF", true);
        sizeView = text("Menunggu download", 11, secondary, false);
        sizeView.setGravity(Gravity.END);
        progressInfo.addView(percentView);
        progressInfo.addView(sizeView, new LinearLayout.LayoutParams(0, -2, 1));
        progressInfo.setVisibility(View.GONE);
        cardView.addView(progressInfo, marginTop(10));

        statusView = text("Menghubungi server Transiva", 13, secondary, false);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, dp(12), 0, dp(8));
        cardView.addView(statusView);

        notesView = text("", 13, secondary, false);
        notesView.setPadding(dp(12), dp(12), dp(12), dp(12));
        notesView.setBackground(round(dark ? "#0B1727" : "#F0F6FF", 16));
        notesView.setVisibility(View.GONE);
        cardView.addView(notesView, marginTop(10));

        actionButton = new Button(this);
        actionButton.setText("Periksa Lagi");
        actionButton.setTextColor(Color.WHITE);
        actionButton.setTextSize(14);
        actionButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        actionButton.setAllCaps(false);
        actionButton.setBackground(round("#0B7CFF", 16));
        actionButton.setVisibility(View.GONE);
        actionButton.setOnClickListener(v -> onAction());
        cardView.addView(actionButton, new LinearLayout.LayoutParams(-1, dp(52)));

        root.addView(cardView);
        TextView safety = text("Pembaruan diunduh oleh sistem Android dari server resmi Transiva. APK diverifikasi sebelum installer dibuka.", 11, secondary, false);
        safety.setPadding(dp(4), dp(14), dp(4), 0);
        root.addView(safety);
        applyForceUi();
        return shell;
    }

    private void applyForceUi() {
        if (backView != null) backView.setVisibility(forcedMode ? View.INVISIBLE : View.VISIBLE);
    }

    private void checkUpdate() {
        checkingBar.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.GONE);
        progressInfo.setVisibility(View.GONE);
        actionButton.setVisibility(View.GONE);
        notesView.setVisibility(View.GONE);
        titleView.setText(forcedMode ? "Pembaruan wajib" : "Memeriksa pembaruan...");
        statusView.setText("Menghubungi server Transiva");

        AppUpdateClient.check(this, new AppUpdateClient.Callback() {
            @Override public void onResult(AppUpdateInfo info, boolean available) {
                main.post(() -> showResult(info, available));
            }
            @Override public void onError(String message) {
                main.post(() -> showError(message));
            }
        });
    }

    private void showResult(AppUpdateInfo info, boolean available) {
        updateInfo = info;
        forcedMode = forcedMode || info.isForceRequired(currentVersion());
        applyForceUi();
        checkingBar.setVisibility(View.GONE);

        if (!available) {
            AppUpdateStore.clearDownload(this);
            titleView.setText("Aplikasi sudah terbaru");
            versionView.setText("Versi " + AppUpdateClient.installedVersionName(this));
            statusView.setText("Tidak ada pembaruan yang perlu diunduh.");
            actionButton.setText(forcedMode ? "Lanjutkan" : "Periksa Lagi");
            actionButton.setVisibility(View.VISIBLE);
            if (forcedMode) {
                forcedMode = false;
                applyForceUi();
                goToSplash();
            }
            return;
        }

        titleView.setText(forcedMode ? "Pembaruan wajib tersedia" : info.title);
        versionView.setText("Versi " + info.versionName + " • terpasang " + AppUpdateClient.installedVersionName(this));
        statusView.setText(forcedMode
                ? "Versi lama tidak dapat digunakan. Pembaruan akan diunduh otomatis."
                : "Pembaruan siap diunduh di background.");
        notesView.setText(info.message);
        notesView.setVisibility(View.VISIBLE);
        actionButton.setText("Download & Perbarui");
        actionButton.setVisibility(View.VISIBLE);

        AppUpdateDownloadManager.State existing = AppUpdateDownloadManager.current(this);
        if (existing != null && (existing.running() || existing.complete())) {
            startPollingExisting(existing);
        } else if (forcedMode || autoStart) {
            startDownload();
        }
    }

    private void showError(String message) {
        checkingBar.setVisibility(View.GONE);
        AppUpdateInfo cached = AppUpdateStore.cachedInfo(this);
        if (forcedMode && cached != null && cached.isForceRequired(currentVersion())) {
            updateInfo = cached;
            titleView.setText("Pembaruan wajib");
            statusView.setText("Server versi belum dapat dihubungi. Coba lagi untuk melanjutkan pembaruan.");
            actionButton.setText("Coba Lagi");
            actionButton.setVisibility(View.VISIBLE);
            return;
        }
        titleView.setText("Pemeriksaan gagal");
        statusView.setText(message == null ? "Tidak dapat memeriksa versi aplikasi." : message);
        actionButton.setText("Coba Lagi");
        actionButton.setVisibility(View.VISIBLE);
    }

    private void onAction() {
        if (downloadedApk != null && downloadedApk.exists()) {
            installApk(downloadedApk);
        } else if (updateInfo != null && updateInfo.isUpdateAvailable(currentVersion())) {
            startDownload();
        } else if (forcedMode && updateInfo != null && !updateInfo.isUpdateAvailable(currentVersion())) {
            goToSplash();
        } else {
            checkUpdate();
        }
    }

    private void startDownload() {
        if (updateInfo == null || !updateInfo.isUpdateAvailable(currentVersion())) return;
        try {
            AppUpdateDownloadManager.ensureDownload(this, updateInfo);
            downloading = true;
            checkingBar.setVisibility(View.GONE);
            progressBar.setVisibility(View.VISIBLE);
            progressInfo.setVisibility(View.VISIBLE);
            actionButton.setVisibility(View.GONE);
            titleView.setText(forcedMode ? "Mengunduh pembaruan wajib" : "Mengunduh di background");
            statusView.setText("Download dikelola Android. Anda boleh mematikan layar.");
            main.removeCallbacks(pollDownload);
            main.post(pollDownload);
        } catch (Exception e) {
            downloadFailed(e.getMessage());
        }
    }

    private void startPollingExisting(AppUpdateDownloadManager.State state) {
        checkingBar.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);
        progressInfo.setVisibility(View.VISIBLE);
        actionButton.setVisibility(View.GONE);
        titleView.setText(state.complete() ? "Download selesai" : "Mengunduh pembaruan");
        downloading = state.running();
        main.removeCallbacks(pollDownload);
        main.post(pollDownload);
    }

    private void updateProgress(long done, long total) {
        int percent = total > 0 ? (int) Math.min(100, (done * 100L) / total) : 0;
        progressBar.setIndeterminate(total <= 0);
        if (total > 0) progressBar.setProgress(percent);
        percentView.setText(total > 0 ? percent + "%" : "...");
        sizeView.setText(formatBytes(Math.max(0L, done)) + (total > 0 ? " dari " + formatBytes(total) : ""));
    }

    private void verifyAndFinishDownload() {
        main.removeCallbacks(pollDownload);
        if (downloadedApk == null || !downloadedApk.exists()) {
            downloadFailed("File APK hasil download tidak ditemukan.");
            return;
        }
        titleView.setText("Memverifikasi pembaruan");
        statusView.setText("Memeriksa paket, versi, tanda tangan, dan SHA-256...");
        actionButton.setVisibility(View.GONE);

        new Thread(() -> {
            try {
                verifyApk(downloadedApk);
                main.post(this::downloadFinished);
            } catch (Exception e) {
                try { downloadedApk.delete(); } catch (Exception ignored) {}
                AppUpdateStore.clearDownload(UpdateDownloadActivity.this);
                main.post(() -> downloadFailed(e.getMessage()));
            }
        }, "transiva-verify-update").start();
    }

    private void downloadFinished() {
        downloading = false;
        progressBar.setIndeterminate(false);
        progressBar.setProgress(100);
        percentView.setText("100%");
        titleView.setText("Pembaruan siap dipasang");
        statusView.setText("Verifikasi berhasil. Lanjutkan instalasi Android.");
        actionButton.setText("Pasang Sekarang");
        actionButton.setVisibility(View.VISIBLE);
        if (forcedMode || autoStart) installApk(downloadedApk);
    }

    private void downloadFailed(String message) {
        downloading = false;
        main.removeCallbacks(pollDownload);
        titleView.setText("Download terhenti");
        statusView.setText(message == null ? "Koneksi terputus. Silakan coba lagi." : message);
        actionButton.setText("Ulangi Download");
        actionButton.setVisibility(View.VISIBLE);
    }

    private void verifyApk(File apk) throws Exception {
        if (updateInfo == null) updateInfo = AppUpdateStore.cachedInfo(this);
        if (updateInfo == null) throw new SecurityException("Informasi versi update tidak tersedia.");

        PackageManager pm = getPackageManager();
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (archive == null) throw new SecurityException("File download bukan APK Android yang valid.");
        if (!AppUpdateClient.DRIVER_PACKAGE.equals(archive.packageName)) {
            throw new SecurityException("APK bukan paket Transiva Driver.");
        }
        long archiveVersion = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? archive.getLongVersionCode() : archive.versionCode;
        if (archiveVersion != updateInfo.versionCode) {
            throw new SecurityException("VersionCode APK tidak sesuai dengan server.");
        }

        PackageInfo installed = pm.getPackageInfo(getPackageName(), flags);
        byte[][] a = certDigests(installed);
        byte[][] b = certDigests(archive);
        if (a.length == 0 || b.length == 0 || !sameCertSet(a, b)) {
            throw new SecurityException("Tanda tangan APK berbeda. Update dibatalkan.");
        }

        if (updateInfo.sha256 != null && !updateInfo.sha256.trim().isEmpty()) {
            String actual = sha256(apk);
            if (!actual.equalsIgnoreCase(updateInfo.sha256.trim())) {
                throw new SecurityException("SHA-256 APK tidak cocok. Update dibatalkan.");
            }
        }
    }

    private byte[][] certDigests(PackageInfo info) throws Exception {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (info.signingInfo == null) return new byte[0][];
            signatures = info.signingInfo.hasMultipleSigners()
                    ? info.signingInfo.getApkContentsSigners()
                    : info.signingInfo.getSigningCertificateHistory();
        } else {
            signatures = info.signatures;
        }
        if (signatures == null) return new byte[0][];
        byte[][] result = new byte[signatures.length][];
        for (int i = 0; i < signatures.length; i++) {
            result[i] = MessageDigest.getInstance("SHA-256").digest(signatures[i].toByteArray());
        }
        return result;
    }

    private boolean sameCertSet(byte[][] a, byte[][] b) {
        if (a.length != b.length) return false;
        boolean[] used = new boolean[b.length];
        outer: for (byte[] x : a) {
            for (int i = 0; i < b.length; i++) {
                if (!used[i] && Arrays.equals(x, b[i])) {
                    used[i] = true;
                    continue outer;
                }
            }
            return false;
        }
        return true;
    }

    private void installApk(File apk) {
        if (apk == null || !apk.exists()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this)
                    .setTitle("Izinkan pemasangan aplikasi")
                    .setMessage("Aktifkan izin 'Instal aplikasi tidak dikenal' untuk Transiva Driver, lalu kembali. Pembaruan wajib tidak dapat dilewati.")
                    .setCancelable(!forcedMode)
                    .setNegativeButton(forcedMode ? null : "Batal", null)
                    .setPositiveButton("Buka Pengaturan", (d, w) -> {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    }).show();
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            installerOpened = true;
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Installer tidak dapat dibuka: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void goToSplash() {
        Intent i = new Intent(this, SplashActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    @Override public void onBackPressed() {
        if (forcedMode) {
            Toast.makeText(this, "Pembaruan wajib dipasang untuk menggunakan Transiva Driver.", Toast.LENGTH_SHORT).show();
            return;
        }
        // DownloadManager tetap melanjutkan download walau halaman ditutup.
        super.onBackPressed();
    }

    private int currentVersion() {
        try { return AppUpdateClient.installedVersionCode(this); }
        catch (Exception ignored) { return 0; }
    }

    private String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[32 * 1024];
        try (FileInputStream input = new FileInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) > 0) digest.update(buffer, 0, read);
        }
        StringBuilder value = new StringBuilder();
        for (byte b : digest.digest()) value.append(String.format(Locale.US, "%02x", b));
        return value.toString();
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024d;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        return String.format(Locale.US, "%.1f MB", kb / 1024d);
    }

    private TextView text(String value, int sp, String color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(Color.parseColor(color));
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable round(String color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.parseColor(color));
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private LinearLayout.LayoutParams marginTop(int value) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(value), 0, 0);
        return lp;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
