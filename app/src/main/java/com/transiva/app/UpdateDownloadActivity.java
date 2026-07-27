package com.transiva.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
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

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/** Layar pemeriksaan, download progres, verifikasi, dan instalasi APK Transiva. */
public class UpdateDownloadActivity extends Activity {
    public static final String EXTRA_ROLE = "role";
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView titleView, versionView, statusView, percentView, sizeView, notesView;
    private ProgressBar progressBar, checkingBar;
    private Button actionButton;
    private AppUpdateInfo updateInfo;
    private File downloadedApk;
    private boolean downloading;
    private boolean dark;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        dark = DriverAppSettings.isDarkMode(this);
        setContentView(buildScreen());
        checkUpdate();
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
        TextView back = text("‹", 34, "#0B7CFF", true);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> { if (!downloading) finish(); });
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        header.addView(text("Pembaruan Aplikasi", 22, primaryText, true));
        shell.addView(header);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(28));
        scroll.addView(root);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout updateCard = new LinearLayout(this);
        updateCard.setOrientation(LinearLayout.VERTICAL);
        updateCard.setPadding(dp(20), dp(22), dp(20), dp(22));
        updateCard.setGravity(Gravity.CENTER_HORIZONTAL);
        updateCard.setBackground(round(card, 24));
        updateCard.setElevation(dp(3));

        TextView icon = text("↻", 44, "#0B7CFF", true);
        icon.setGravity(Gravity.CENTER);
        updateCard.addView(icon, new LinearLayout.LayoutParams(dp(72), dp(72)));

        titleView = text("Memeriksa pembaruan...", 20, primaryText, true);
        titleView.setGravity(Gravity.CENTER);
        updateCard.addView(titleView);

        versionView = text("Versi terpasang " + AppUpdateClient.installedVersionName(this), 12, secondary, false);
        versionView.setGravity(Gravity.CENTER);
        versionView.setPadding(0, dp(5), 0, dp(15));
        updateCard.addView(versionView);

        checkingBar = new ProgressBar(this);
        updateCard.addView(checkingBar, new LinearLayout.LayoutParams(dp(44), dp(44)));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setVisibility(View.GONE);
        updateCard.addView(progressBar, new LinearLayout.LayoutParams(-1, dp(12)));

        LinearLayout progressInfo = new LinearLayout(this);
        progressInfo.setGravity(Gravity.CENTER_VERTICAL);
        percentView = text("0%", 24, "#0B7CFF", true);
        sizeView = text("Menunggu download", 11, secondary, false);
        sizeView.setGravity(Gravity.END);
        progressInfo.addView(percentView);
        progressInfo.addView(sizeView, new LinearLayout.LayoutParams(0, -2, 1));
        progressInfo.setVisibility(View.GONE);
        progressInfo.setTag("progress_info");
        updateCard.addView(progressInfo, marginTop(10));

        statusView = text("Menghubungi server Transiva", 13, secondary, false);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, dp(12), 0, dp(8));
        updateCard.addView(statusView);

        notesView = text("", 13, secondary, false);
        notesView.setPadding(dp(12), dp(12), dp(12), dp(12));
        notesView.setBackground(round(dark ? "#0B1727" : "#F0F6FF", 16));
        notesView.setVisibility(View.GONE);
        updateCard.addView(notesView, marginTop(10));

        actionButton = new Button(this);
        actionButton.setText("Periksa Lagi");
        actionButton.setTextColor(Color.WHITE);
        actionButton.setTextSize(14);
        actionButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        actionButton.setAllCaps(false);
        actionButton.setBackground(round("#0B7CFF", 16));
        actionButton.setVisibility(View.GONE);
        actionButton.setOnClickListener(v -> onAction());
        updateCard.addView(actionButton, new LinearLayout.LayoutParams(-1, dp(52)));

        root.addView(updateCard);
        TextView safety = text("APK diunduh langsung dari server resmi Transiva. Setelah selesai, Android akan meminta konfirmasi pemasangan.", 11, secondary, false);
        safety.setPadding(dp(4), dp(14), dp(4), 0);
        root.addView(safety);
        return shell;
    }

    private void checkUpdate() {
        checkingBar.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.GONE);
        actionButton.setVisibility(View.GONE);
        notesView.setVisibility(View.GONE);
        titleView.setText("Memeriksa pembaruan...");
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
        checkingBar.setVisibility(View.GONE);
        actionButton.setVisibility(View.VISIBLE);
        if (!available) {
            titleView.setText("Aplikasi sudah terbaru");
            versionView.setText("Versi " + AppUpdateClient.installedVersionName(this));
            statusView.setText("Tidak ada pembaruan yang perlu diunduh.");
            actionButton.setText("Periksa Lagi");
            return;
        }
        titleView.setText(info.title);
        versionView.setText("Versi " + info.versionName + " tersedia");
        statusView.setText("Pembaruan siap diunduh");
        notesView.setText(info.message);
        notesView.setVisibility(View.VISIBLE);
        actionButton.setText("Download & Perbarui");
    }

    private void showError(String message) {
        checkingBar.setVisibility(View.GONE);
        titleView.setText("Pemeriksaan gagal");
        statusView.setText(message);
        actionButton.setText("Coba Lagi");
        actionButton.setVisibility(View.VISIBLE);
    }

    private void onAction() {
        if (downloading) return;
        if (downloadedApk != null && downloadedApk.exists()) {
            installApk(downloadedApk);
        } else if (updateInfo != null && updateInfo.versionCode > currentVersion()) {
            startDownload();
        } else {
            checkUpdate();
        }
    }

    private int currentVersion() {
        try { return AppUpdateClient.installedVersionCode(this); }
        catch (Exception ignored) { return 0; }
    }

    private void startDownload() {
        downloading = true;
        checkingBar.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);
        View info = progressBar.getParent() instanceof LinearLayout
                ? ((LinearLayout) progressBar.getParent()).findViewWithTag("progress_info") : null;
        if (info != null) info.setVisibility(View.VISIBLE);
        actionButton.setVisibility(View.GONE);
        titleView.setText("Mengunduh pembaruan");
        statusView.setText("Jangan tutup aplikasi sampai download selesai.");

        new Thread(() -> {
            HttpURLConnection connection = null;
            File output = new File(new File(getCacheDir(), "updates"), "transiva-update.apk");
            try {
                File parent = output.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IllegalStateException("Folder update tidak dapat dibuat.");
                connection = (HttpURLConnection) new URL(updateInfo.apkUrl).openConnection();
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(60000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("Accept", "application/vnd.android.package-archive");
                connection.connect();
                int response = connection.getResponseCode();
                if (response < 200 || response >= 300) throw new IllegalStateException("Download gagal, server merespons " + response);
                long total = connection.getContentLengthLong();
                if (total <= 0) total = updateInfo.fileSize;
                long downloaded = 0;
                byte[] buffer = new byte[32 * 1024];
                try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
                     FileOutputStream file = new FileOutputStream(output, false)) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        file.write(buffer, 0, count);
                        downloaded += count;
                        final long done = downloaded;
                        final long expected = total;
                        main.post(() -> updateProgress(done, expected));
                    }
                    file.flush();
                }
                if (!updateInfo.sha256.trim().isEmpty()) {
                    main.post(() -> statusView.setText("Memverifikasi keamanan file..."));
                    String actual = sha256(output);
                    if (!actual.equalsIgnoreCase(updateInfo.sha256.trim())) {
                        //noinspection ResultOfMethodCallIgnored
                        output.delete();
                        throw new SecurityException("Verifikasi APK gagal. File tidak akan dipasang.");
                    }
                }
                downloadedApk = output;
                main.post(this::downloadFinished);
            } catch (Exception e) {
                main.post(() -> downloadFailed(e.getMessage()));
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "transiva-apk-download").start();
    }

    private void updateProgress(long done, long total) {
        int percent = total > 0 ? (int) Math.min(100, (done * 100L) / total) : 0;
        progressBar.setIndeterminate(total <= 0);
        if (total > 0) progressBar.setProgress(percent);
        percentView.setText(total > 0 ? percent + "%" : "...");
        sizeView.setText(formatBytes(done) + (total > 0 ? " dari " + formatBytes(total) : ""));
    }

    private void downloadFinished() {
        downloading = false;
        progressBar.setIndeterminate(false);
        progressBar.setProgress(100);
        percentView.setText("100%");
        titleView.setText("Download selesai");
        statusView.setText("APK siap dipasang di perangkat ini.");
        actionButton.setText("Pasang Sekarang");
        actionButton.setVisibility(View.VISIBLE);
        installApk(downloadedApk);
    }

    private void downloadFailed(String message) {
        downloading = false;
        titleView.setText("Download terhenti");
        statusView.setText(message == null ? "Koneksi terputus. Silakan coba lagi." : message);
        actionButton.setText("Ulangi Download");
        actionButton.setVisibility(View.VISIBLE);
    }

    private void installApk(File apk) {
        if (apk == null || !apk.exists()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this)
                    .setTitle("Izinkan pemasangan aplikasi")
                    .setMessage("Aktifkan izin 'Instal aplikasi tidak dikenal' untuk Transiva, lalu kembali ke halaman ini.")
                    .setNegativeButton("Batal", null)
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
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Installer tidak dapat dibuka: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onBackPressed() {
        if (downloading) {
            Toast.makeText(this, "Download pembaruan sedang berlangsung.", Toast.LENGTH_SHORT).show();
            return;
        }
        super.onBackPressed();
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
