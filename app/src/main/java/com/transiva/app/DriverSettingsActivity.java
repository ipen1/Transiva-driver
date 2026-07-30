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

/** Pengaturan lokal khusus sisi driver. */
public class DriverSettingsActivity extends Activity {

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(buildScreen());
        DriverAppSettings.apply(this);
    }

    @Override protected void onResume() {
        super.onResume();
        DriverAppSettings.apply(this);
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
