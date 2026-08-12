package com.transiva.app;

import android.app.Activity;
import android.provider.Settings;
import android.os.Build;
import android.net.Uri;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Pengaturan lokal khusus sisi driver. */
public class DriverSettingsActivity extends Activity {

    private static final String DEVICE_URL = "https://transiva.my.id/server/driver_device_native.php";
    private TextView deviceNameView;
    private TextView deviceMetaView;
    private TextView resetDeviceButton;
    private volatile boolean resettingDevice = false;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(buildScreen());
        DriverAppSettings.apply(this);
        loadDeviceStatus();
    }

    @Override protected void onResume() {
        super.onResume();
        DriverAppSettings.apply(this);
        if (!resettingDevice && deviceNameView != null) loadDeviceStatus();
    }

    private LinearLayout buildScreen() {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(Color.parseColor("#F5F8FD"));

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text("‹", 34, "#0B7CFF", true);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("Pengaturan Driver", 23, "#0B3A78", true));
        titles.addView(text("Atur tampilan aplikasi khusus akun driver", 11, "#718096", false));
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(header);

        TextView section = text("Tampilan", 13, "#0B3A78", true);
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(-1, -2);
        sectionLp.setMargins(0, dp(18), 0, dp(8));
        root.addView(section, sectionLp);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(round("#FFFFFF", 20));
        card.setElevation(dp(2));

        card.addView(toggleRow(
                "Mode Malam",
                "Aktifkan tema gelap pada seluruh halaman driver",
                DriverAppSettings.isDarkMode(this),
                (button, checked) -> {
                    DriverAppSettings.setDarkMode(this, checked);
                    recreate();
                }
        ));
        root.addView(card);

        TextView callSection = text("Panggilan Masuk", 13, "#0B3A78", true);
        LinearLayout.LayoutParams callSectionLp = new LinearLayout.LayoutParams(-1, -2);
        callSectionLp.setMargins(0, dp(18), 0, dp(8));
        root.addView(callSection, callSectionLp);

        LinearLayout callCard = new LinearLayout(this);
        callCard.setOrientation(LinearLayout.VERTICAL);
        callCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        callCard.setBackground(round("#FFFFFF", 20));
        callCard.setElevation(dp(2));
        LinearLayout overlayRow = new LinearLayout(this);
        overlayRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout overlayLabels = new LinearLayout(this);
        overlayLabels.setOrientation(LinearLayout.VERTICAL);
        overlayLabels.addView(text("Tampil di Atas Aplikasi Lain", 15, "#0B3A78", true));
        overlayLabels.addView(text(overlayStatusText(), 11, "#64748B", false));
        overlayRow.addView(overlayLabels, new LinearLayout.LayoutParams(0, -2, 1));
        overlayRow.addView(text("›", 30, "#0B7CFF", true));
        overlayRow.setOnClickListener(v -> explainAndOpenOverlaySettings());
        callCard.addView(overlayRow);
        root.addView(callCard);

        TextView deviceSection = text("Keamanan Perangkat", 13, "#0B3A78", true);
        LinearLayout.LayoutParams deviceSectionLp = new LinearLayout.LayoutParams(-1, -2);
        deviceSectionLp.setMargins(0, dp(18), 0, dp(8));
        root.addView(deviceSection, deviceSectionLp);

        LinearLayout deviceCard = new LinearLayout(this);
        deviceCard.setOrientation(LinearLayout.VERTICAL);
        deviceCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        deviceCard.setBackground(round("#FFFFFF", 20));
        deviceCard.setElevation(dp(2));

        deviceNameView = text("Memuat perangkat saat ini…", 15, "#0B3A78", true);
        deviceMetaView = text("Satu akun Driver hanya dapat terhubung ke satu perangkat.", 11, "#64748B", false);
        deviceCard.addView(deviceNameView);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(-1, -2);
        metaLp.setMargins(0, dp(4), 0, dp(14));
        deviceCard.addView(deviceMetaView, metaLp);

        resetDeviceButton = text("Reset Perangkat", 14, "#D92D20", true);
        resetDeviceButton.setGravity(Gravity.CENTER);
        resetDeviceButton.setPadding(dp(12), dp(12), dp(12), dp(12));
        resetDeviceButton.setBackground(round("#FFF1F0", 14));
        resetDeviceButton.setOnClickListener(v -> confirmResetDevice());
        deviceCard.addView(resetDeviceButton, new LinearLayout.LayoutParams(-1, -2));
        root.addView(deviceCard);

        TextView accountSection = text("Keamanan Akun", 13, "#0B3A78", true);
        LinearLayout.LayoutParams accountSectionLp = new LinearLayout.LayoutParams(-1, -2);
        accountSectionLp.setMargins(0, dp(18), 0, dp(8));
        root.addView(accountSection, accountSectionLp);

        LinearLayout accountCard = new LinearLayout(this);
        accountCard.setOrientation(LinearLayout.VERTICAL);
        accountCard.setPadding(dp(16), dp(8), dp(16), dp(8));
        accountCard.setBackground(round("#FFFFFF", 20));
        accountCard.setElevation(dp(2));

        accountCard.addView(actionRow("Ubah Username", "Ganti username akun Driver", () -> {
            Intent i = new Intent(this, DriverAccountSecurityActivity.class);
            i.putExtra(DriverAccountSecurityActivity.EXTRA_MODE, DriverAccountSecurityActivity.MODE_USERNAME);
            startActivity(i);
        }));
        accountCard.addView(actionRow("Ubah Password", "Ganti password login dan cabut sesi lain", () -> {
            Intent i = new Intent(this, DriverAccountSecurityActivity.class);
            i.putExtra(DriverAccountSecurityActivity.EXTRA_MODE, DriverAccountSecurityActivity.MODE_PASSWORD);
            startActivity(i);
        }));
        accountCard.addView(actionRow("Ubah PIN", "Ganti PIN keamanan 6 digit", () ->
                startActivity(new Intent(this, ChangePinActivity.class))));
        root.addView(accountCard);

        TextView updateSection = text("Pembaruan", 13, "#0B3A78", true);
        LinearLayout.LayoutParams updateSectionLp = new LinearLayout.LayoutParams(-1, -2);
        updateSectionLp.setMargins(0, dp(18), 0, dp(8));
        root.addView(updateSection, updateSectionLp);

        LinearLayout updateCard = new LinearLayout(this);
        updateCard.setOrientation(LinearLayout.VERTICAL);
        updateCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        updateCard.setBackground(round("#FFFFFF", 20));
        updateCard.setElevation(dp(2));
        LinearLayout updateRow = new LinearLayout(this);
        updateRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout updateLabels = new LinearLayout(this);
        updateLabels.setOrientation(LinearLayout.VERTICAL);
        updateLabels.addView(text("Cek Pembaruan Aplikasi", 15, "#0B3A78", true));
        updateLabels.addView(text("Versi terpasang " + AppUpdateClient.installedVersionName(this), 11, "#64748B", false));
        updateRow.addView(updateLabels, new LinearLayout.LayoutParams(0, -2, 1));
        TextView updateArrow = text("›", 30, "#0B7CFF", true);
        updateRow.addView(updateArrow);
        updateRow.setOnClickListener(v -> {
            Intent intent = new Intent(this, UpdateDownloadActivity.class);
            intent.putExtra(UpdateDownloadActivity.EXTRA_ROLE, "driver");
            startActivity(intent);
        });
        updateCard.addView(updateRow);
        root.addView(updateCard);

        TextView note = text(
                "Mode Normal menggunakan tampilan terang. Mode Malam menggunakan latar gelap dan tetap tersimpan saat aplikasi dibuka kembali.",
                11, "#64748B", false);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(-1, -2);
        noteLp.setMargins(dp(4), dp(12), dp(4), 0);
        root.addView(note, noteLp);

        return shell;
    }

    private String overlayStatusText() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            return "Aktif • layar panggilan dapat tampil saat aplikasi di latar belakang";
        }
        return "Opsional • aktifkan hanya untuk membantu layar panggilan masuk";
    }

    private void explainAndOpenOverlaySettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        if (Settings.canDrawOverlays(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("Izin sudah aktif")
                    .setMessage("Transiva Driver sudah diizinkan tampil di atas aplikasi lain. Izin ini membantu layar panggilan masuk tampil ketika aplikasi berada di latar belakang.")
                    .setPositiveButton("Tutup", null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Aktifkan secara opsional")
                .setMessage("Izin ini tidak wajib untuk memakai aplikasi. Aktifkan hanya bila Anda ingin layar panggilan Transiva tampil di atas aplikasi lain saat sedang online atau menjalankan perjalanan.")
                .setNegativeButton("Nanti", null)
                .setPositiveButton("Buka Pengaturan", (dialog, which) -> {
                    try {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    } catch (Exception ignored) {
                    }
                })
                .show();
    }

    private void loadDeviceStatus() {
        final String token = new SessionManager(this).getToken();
        if (token == null || token.trim().isEmpty()) {
            setDeviceUi("Sesi Driver tidak aktif", "Silakan login kembali untuk melihat perangkat.", false);
            return;
        }
        setDeviceUi("Memuat perangkat saat ini…", "Memeriksa binding perangkat Driver.", false);
        DriverNetworkExecutor.execute(() -> {
            try {
                JSONObject response = requestDevice("GET", null);
                boolean success = response.optBoolean("success", false);
                if (!success) throw new Exception(response.optString("message", "Gagal membaca perangkat."));
                JSONObject device = response.optJSONObject("device");
                if (device == null) {
                    runOnUiThread(() -> setDeviceUi(
                            "Belum ada perangkat terhubung",
                            "Login ulang akan mengikat akun ini ke perangkat Driver yang digunakan.",
                            false));
                    return;
                }
                String serverName = device.optString("device_name", "").trim();
                if (serverName.isEmpty()) serverName = Build.MANUFACTURER + " " + Build.MODEL;
                boolean current = device.optBoolean("is_current", false);
                String lastSeen = device.optString("last_seen_at", "").trim();
                String localShort = shortUuid(DeviceIdentityManager.getInstallationUuid(this));
                String meta = (current ? "Perangkat saat ini" : "Perangkat terikat")
                        + " • ID " + localShort
                        + (lastSeen.isEmpty() || "null".equalsIgnoreCase(lastSeen) ? "" : " • aktif " + lastSeen);
                final String finalName = serverName;
                runOnUiThread(() -> setDeviceUi(finalName, meta, true));
            } catch (Exception e) {
                final String msg = e.getMessage() == null ? "Gagal memuat status perangkat." : e.getMessage();
                runOnUiThread(() -> setDeviceUi(
                        Build.MANUFACTURER + " " + Build.MODEL,
                        "Status server belum dapat dimuat • " + msg,
                        true));
            }
        });
    }

    private void setDeviceUi(String name, String meta, boolean enableReset) {
        if (deviceNameView != null) deviceNameView.setText(name);
        if (deviceMetaView != null) deviceMetaView.setText(meta);
        if (resetDeviceButton != null) {
            resetDeviceButton.setEnabled(enableReset && !resettingDevice);
            resetDeviceButton.setAlpha((enableReset && !resettingDevice) ? 1f : 0.5f);
        }
    }

    private void confirmResetDevice() {
        if (resettingDevice) return;
        new AlertDialog.Builder(this)
                .setTitle("Reset perangkat Driver?")
                .setMessage("Akun ini akan dilepas dari HP sekarang, semua sesi Driver dicabut, dan notifikasi ke perangkat ini dihentikan. Setelah itu akun dapat login di HP lain.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Reset Perangkat", (dialog, which) -> resetDevice())
                .show();
    }

    private void resetDevice() {
        resettingDevice = true;
        setDeviceUi("Mereset perangkat…", "Mohon tunggu, sesi dan FCM perangkat sedang dilepas.", false);
        DriverNetworkExecutor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("action", "disconnect_device");
                JSONObject response = requestDevice("POST", body);
                if (!response.optBoolean("success", false)) {
                    throw new Exception(response.optString("message", "Reset perangkat gagal."));
                }
                runOnUiThread(() -> {
                    new SessionManager(this).forceLogout("driver_device_reset");
                    Intent intent = new Intent(this, LoginActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(intent);
                    finish();
                });
            } catch (Exception e) {
                resettingDevice = false;
                final String msg = e.getMessage() == null ? "Reset perangkat gagal." : e.getMessage();
                runOnUiThread(() -> {
                    setDeviceUi(Build.MANUFACTURER + " " + Build.MODEL,
                            "Reset gagal • " + msg, true);
                    new AlertDialog.Builder(this)
                            .setTitle("Reset perangkat gagal")
                            .setMessage(msg)
                            .setPositiveButton("OK", null)
                            .show();
                });
            }
        });
    }

    private JSONObject requestDevice(String method, JSONObject body) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(DEVICE_URL).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setUseCaches(false);
            conn.setDoInput(true);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Cache-Control", "no-store");
            String token = new SessionManager(this).getToken();
            if (token != null && !token.trim().isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token.trim());
            }
            conn.setRequestProperty("X-Device-UUID", DeviceIdentityManager.getInstallationUuid(this));
            conn.setRequestProperty("X-App-Scope", "driver");
            if ("POST".equals(method)) {
                conn.setDoOutput(true);
                try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8))) {
                    writer.write(body == null ? "{}" : body.toString());
                }
            }
            int status = conn.getResponseCode();
            InputStream stream = status >= 200 && status < 400 ? conn.getInputStream() : conn.getErrorStream();
            String raw = readStream(stream);
            if (raw.trim().isEmpty()) throw new Exception("Server tidak mengirim respons.");
            JSONObject json = new JSONObject(raw);
            if (status < 200 || status >= 400) {
                throw new Exception(json.optString("message", "HTTP " + status));
            }
            return json;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String readStream(InputStream stream) throws Exception {
        if (stream == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) out.append(line);
            return out.toString();
        }
    }

    private String shortUuid(String uuid) {
        if (uuid == null) return "-";
        String clean = uuid.trim();
        if (clean.length() <= 8) return clean;
        return clean.substring(0, 4).toUpperCase() + "…" + clean.substring(clean.length() - 4).toUpperCase();
    }

    private LinearLayout actionRow(String title, String subtitle, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 15, "#0B3A78", true));
        labels.addView(text(subtitle, 11, "#64748B", false));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(text("›", 28, "#0B7CFF", true));
        row.setOnClickListener(v -> { if (action != null) action.run(); });
        return row;
    }

    private LinearLayout toggleRow(String title, String subtitle, boolean checked,
                                   CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 15, "#0B3A78", true));
        labels.addView(text(subtitle, 11, "#64748B", false));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        Switch toggle = new Switch(this);
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener(listener);
        row.addView(toggle);
        return row;
    }

    private TextView text(String value, int sp, String color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(Color.parseColor(color));
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable round(String color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.parseColor(color));
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
