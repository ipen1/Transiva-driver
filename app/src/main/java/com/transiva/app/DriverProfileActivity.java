package com.transiva.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.transiva.app.driver.data.DriverApiClient;
import com.transiva.app.driver.ui.DriverBottomNavigation;

import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.Locale;
public class DriverProfileActivity extends DriverProfileActivityLayer1 {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#0B7CFF"));
        getWindow().setNavigationBarColor(Color.parseColor("#071426"));

        session = new SessionManager(this);
        if (!validDriverSession()) {
            redirectLogin();
            return;
        }

        api = new DriverApiClient(session);
        setContentView(buildScreen());
        DriverAppSettings.apply(this);
        loadProfile();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The Profile/Akun page can remain in the back stack while the user changes
        // theme from DriverSettingsActivity. Re-apply here so DriverAppSettings can
        // detect the preference change and recreate this page immediately.
        DriverAppSettings.apply(this);
        if (api != null && !loadingData) loadProfile();
    }

    @Override
    protected void onDestroy() {
        if (api != null) api.shutdown();
        super.onDestroy();
    }

    protected boolean validDriverSession() {
        return session != null
                && session.isLoggedIn()
                && "driver".equals(session.normalizeRole(session.getRole()))
                && !clean(session.getToken()).isEmpty();
    }

    protected void redirectLogin() {
        Intent intent = new Intent(this, LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }

    protected View buildScreen() {
        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(Color.parseColor("#F6F9FE"));

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        page.addView(shell, new FrameLayout.LayoutParams(-1, -1));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(14), dp(14), dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        content.addView(buildHeader());
        content.addView(buildIdentityCard(), sectionLp());
        content.addView(buildPerformanceCard(), sectionLp());
        content.addView(buildRatingCard(), sectionLp());
        content.addView(buildDriverInfoCard(), sectionLp());
        content.addView(buildStatusCard(), sectionLp());
        content.addView(buildBpjsCard(), sectionLp());
        content.addView(buildDocumentCard(), sectionLp());
        content.addView(buildSecurityCard());

        shell.addView(
                DriverBottomNavigation.build(this, DriverBottomNavigation.ActiveItem.PROFILE),
                new LinearLayout.LayoutParams(-1, dp(66))
        );

        loading = new ProgressBar(this);
        loading.setVisibility(View.GONE);
        FrameLayout.LayoutParams loadingLp = new FrameLayout.LayoutParams(dp(46), dp(46));
        loadingLp.gravity = Gravity.CENTER;
        page.addView(loading, loadingLp);
        return page;
    }

    protected View buildHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.addView(text("Akun Driver", 24, "#0B3A78", true));
        titleBox.addView(text("Profil, kendaraan, dokumen, dan status kerja", 11, "#718096", false));
        row.addView(titleBox, new LinearLayout.LayoutParams(0, -2, 1));

        TextView refresh = text("↻", 25, "#0B7CFF", true);
        refresh.setGravity(Gravity.CENTER);
        refresh.setBackground(roundStroke("#FFFFFF", "#DCE8F6", 16, 1));
        refresh.setOnClickListener(view -> loadProfile());
        row.addView(refresh, new LinearLayout.LayoutParams(dp(44), dp(44)));
        return row;
    }

    protected View buildIdentityCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(18), dp(22), dp(18), dp(20));
        card.setBackground(gradient("#075EF4", "#22A4FF", 22));
        card.setElevation(dp(3));

        FrameLayout avatarFrame = new FrameLayout(this);
        GradientDrawable border = new GradientDrawable();
        border.setShape(GradientDrawable.OVAL);
        border.setColor(Color.WHITE);
        border.setStroke(dp(3), Color.WHITE);
        avatarFrame.setBackground(border);
        avatarFrame.setElevation(dp(5));

        avatarView = new ImageView(this);
        avatarView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatarView.setImageResource(drawableOrFallback("ic_nav_profile"));
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.parseColor("#EAF4FF"));
        avatarView.setBackground(mask);
        avatarView.setClipToOutline(true);
        avatarView.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        FrameLayout.LayoutParams avatarLp = new FrameLayout.LayoutParams(dp(94), dp(94));
        avatarLp.gravity = Gravity.CENTER;
        avatarFrame.addView(avatarView, avatarLp);
        card.addView(avatarFrame, new LinearLayout.LayoutParams(dp(102), dp(102)));

        nameView = text(first(session.getName(), session.getUsername(), "Driver"), 21, "#FFFFFF", true);
        nameView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(-1, -2);
        nameLp.setMargins(0, dp(12), 0, 0);
        card.addView(nameView, nameLp);

        usernameView = text("@" + first(session.getUsername(), "driver"), 11, "#EAF5FF", false);
        usernameView.setGravity(Gravity.CENTER);
        card.addView(usernameView);

        LinearLayout badges = new LinearLayout(this);
        badges.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams badgesLp = new LinearLayout.LayoutParams(-1, -2);
        badgesLp.setMargins(0, dp(12), 0, 0);
        card.addView(badges, badgesLp);

        verificationBadge = badge("Memuat status", "#FFFFFF", "#0B7CFF");
        badges.addView(verificationBadge);

        driverTypeBadge = badge("Driver", "#FFE08A", "#5C3A00");
        LinearLayout.LayoutParams typeLp = new LinearLayout.LayoutParams(-2, -2);
        typeLp.setMargins(dp(7), 0, 0, 0);
        badges.addView(driverTypeBadge, typeLp);
        return card;
    }

    protected View buildPerformanceCard() {
        LinearLayout card = whiteCard();
        card.addView(sectionTitle("Performa Aplikasi", "Auto detect menyesuaikan rendering, GPS, FPS, gambar, dan polling dengan kemampuan perangkat"));

        performanceModeValue = text("Mendeteksi perangkat…", 14, "#0B3A78", true);
        card.addView(performanceModeValue);
        performanceRecommendedValue = text("", 10, "#64748B", false);
        LinearLayout.LayoutParams recLp = new LinearLayout.LayoutParams(-1, -2);
        recLp.setMargins(0, dp(4), 0, dp(11));
        card.addView(performanceRecommendedValue, recLp);

        LinearLayout row1 = new LinearLayout(this);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        performanceAutoButton = performanceButton("Auto");
        performanceLowButton = performanceButton("Low · Hemat baterai");
        row1.addView(performanceAutoButton, new LinearLayout.LayoutParams(0, dp(46), 1));
        LinearLayout.LayoutParams lowLp = new LinearLayout.LayoutParams(0, dp(46), 1);
        lowLp.setMargins(dp(8), 0, 0, 0);
        row1.addView(performanceLowButton, lowLp);
        card.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        performanceNormalButton = performanceButton("Normal");
        performanceHighButton = performanceButton("High");
        row2.addView(performanceNormalButton, new LinearLayout.LayoutParams(0, dp(46), 1));
        LinearLayout.LayoutParams highLp = new LinearLayout.LayoutParams(0, dp(46), 1);
        highLp.setMargins(dp(8), 0, 0, 0);
        row2.addView(performanceHighButton, highLp);
        LinearLayout.LayoutParams row2Lp = new LinearLayout.LayoutParams(-1, -2);
        row2Lp.setMargins(0, dp(8), 0, 0);
        card.addView(row2, row2Lp);

        performanceAutoButton.setOnClickListener(v -> selectPerformanceMode(DevicePerformanceProfile.UserMode.AUTO));
        performanceLowButton.setOnClickListener(v -> selectPerformanceMode(DevicePerformanceProfile.UserMode.LOW));
        performanceNormalButton.setOnClickListener(v -> selectPerformanceMode(DevicePerformanceProfile.UserMode.NORMAL));
        performanceHighButton.setOnClickListener(v -> selectPerformanceMode(DevicePerformanceProfile.UserMode.HIGH));
        updatePerformanceCard();
        return card;
    }

    protected Button performanceButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(10);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setPadding(dp(5), 0, dp(5), 0);
        return b;
    }

    protected void selectPerformanceMode(DevicePerformanceProfile.UserMode mode) {
        if (mode != DevicePerformanceProfile.UserMode.AUTO
                && DevicePerformanceProfile.isAboveRecommended(this, mode)) {
            DevicePerformanceProfile.UserMode recommended = DevicePerformanceProfile.getRecommendedMode(this);
            PremiumDialogs.builder(this)
                    .setTitle("Mode di atas rekomendasi perangkat")
                    .setMessage("Perangkat ini direkomendasikan menggunakan "
                            + DevicePerformanceProfile.title(recommended)
                            + ". Memakai " + DevicePerformanceProfile.title(mode)
                            + " dapat membuat perangkat lebih panas, baterai lebih boros, dan aplikasi kurang stabil saat navigasi lama. Tetap gunakan mode ini?")
                    .setNegativeButton("Batal", null)
                    .setPositiveButton("Tetap gunakan", (d, w) -> applyPerformanceMode(mode))
                    .show();
            return;
        }
        applyPerformanceMode(mode);
    }

    protected void applyPerformanceMode(DevicePerformanceProfile.UserMode mode) {
        DevicePerformanceProfile.setSelectedMode(this, mode);
        RemoteImageLoader.onPerformanceModeChanged();
        updatePerformanceCard();
    }

    protected void updatePerformanceCard() {
        if (performanceModeValue == null) return;
        DevicePerformanceProfile.UserMode selected = DevicePerformanceProfile.getSelectedMode(this);
        DevicePerformanceProfile.UserMode recommended = DevicePerformanceProfile.getRecommendedMode(this);
        DevicePerformanceProfile effective = DevicePerformanceProfile.get(this);
        String activeLabel = selected == DevicePerformanceProfile.UserMode.AUTO
                ? "Auto → " + DevicePerformanceProfile.title(recommended)
                : DevicePerformanceProfile.title(selected);
        performanceModeValue.setText("Aktif: " + activeLabel);
        performanceRecommendedValue.setText("Rekomendasi perangkat: " + DevicePerformanceProfile.title(recommended)
                + " · target " + effective.targetFps + " FPS · GPS " + effective.navigationGpsMs + " ms");
        stylePerformanceButton(performanceAutoButton, selected == DevicePerformanceProfile.UserMode.AUTO);
        stylePerformanceButton(performanceLowButton, selected == DevicePerformanceProfile.UserMode.LOW);
        stylePerformanceButton(performanceNormalButton, selected == DevicePerformanceProfile.UserMode.NORMAL);
        stylePerformanceButton(performanceHighButton, selected == DevicePerformanceProfile.UserMode.HIGH);
    }

    protected void stylePerformanceButton(Button button, boolean active) {
        if (button == null) return;
        button.setTextColor(Color.parseColor(active ? "#FFFFFF" : "#0B3A78"));
        button.setBackground(active
                ? gradient("#0B7CFF", "#2EA2FF", 13)
                : roundStroke("#F8FBFF", "#D7E6F8", 13, 1));
    }

    protected View buildRatingCard() {
        LinearLayout card = whiteCard();
        card.addView(sectionTitle("Rating Driver", "Dihitung otomatis dari seluruh pesanan yang telah dinilai customer"));

        LinearLayout summary = new LinearLayout(this);
        summary.setGravity(Gravity.CENTER_VERTICAL);
        summary.setPadding(dp(4), dp(8), dp(4), dp(8));

        LinearLayout scoreBox = new LinearLayout(this);
        scoreBox.setOrientation(LinearLayout.VERTICAL);
        ratingValue = text("0.0", 34, "#0B3A78", true);
        ratingValue.setGravity(Gravity.CENTER);
        scoreBox.addView(ratingValue);
        ratingCountValue = text("Belum ada penilaian", 10, "#718096", false);
        ratingCountValue.setGravity(Gravity.CENTER);
        scoreBox.addView(ratingCountValue);
        summary.addView(scoreBox, new LinearLayout.LayoutParams(0, -2, 1));

        ratingStarsValue = text("☆☆☆☆☆", 26, "#FFB300", true);
        ratingStarsValue.setGravity(Gravity.CENTER);
        summary.addView(ratingStarsValue, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(summary);

        TextView info = text("Rating diperbarui dari review TransRide, TransCar, TransFood, dan TransSend.", 9, "#718096", false);
        info.setGravity(Gravity.CENTER);
        info.setPadding(0, dp(8), 0, 0);
        card.addView(info);
        return card;
    }

    protected View buildDriverInfoCard() {
        LinearLayout card = whiteCard();
        card.addView(sectionTitle("Informasi Driver", "Data akun dan kendaraan utama"));
        emailValue = addInfoRow(card, "Email", "-");
        phoneValue = addInfoRow(card, "Nomor HP", "-");
        plateValue = addInfoRow(card, "Nomor Polisi", "-");
        statusValue = addInfoRow(card, "Status Verifikasi", "-");
        verifiedAtValue = addInfoRow(card, "Terverifikasi Sejak", "-");
        balanceValue = addInfoRow(card, "Saldo Driver", "Rp0");
        noteValue = addInfoRow(card, "Catatan Verifikasi", "-");
        return card;
    }

    protected View buildStatusCard() {
        LinearLayout card = whiteCard();
        card.addView(sectionTitle("Status Kerja", "Informasi operasional driver saat ini"));
        onlineValue = addInfoRow(card, "Status", "Offline");
        busyValue = addInfoRow(card, "Ketersediaan", "Tersedia");
        onlineSinceValue = addInfoRow(card, "Online Sejak", "-");
        lastOrderValue = addInfoRow(card, "Order Terakhir", "-");
        locationValue = addInfoRow(card, "Lokasi Terakhir", "-");
        accuracyValue = addInfoRow(card, "Akurasi Lokasi", "-");
        speedValue = addInfoRow(card, "Kecepatan", "-");
        return card;
    }

    protected View buildBpjsCard() {
        LinearLayout card = whiteCard();
        card.setClickable(true);
        card.setFocusable(true);
        card.addView(sectionTitle("BPJS Ketenagakerjaan", "Perlindungan kepesertaan driver Transiva"));
        bpjsStatusValue = addInfoRow(card, "Status Kepesertaan", "Tidak Aktif");
        bpjsSummaryValue = addInfoRow(card, "Nomor BPJS", "Belum diisi");

        TextView open = text("Lihat kartu & detail BPJS  ›", 11, "#0B7CFF", true);
        open.setPadding(0, dp(13), 0, 0);
        card.addView(open);

        card.setOnClickListener(view -> {
            Intent intent = new Intent(this, DriverBpjsActivity.class);
            if (latestProfile != null) {
                intent.putExtra("bpjs_profile_json", latestProfile.toString());
            }
            startActivity(intent);
        });
        return card;
    }

    protected View buildDocumentCard() {
        LinearLayout card = whiteCard();
        card.addView(sectionTitle("Kendaraan & Dokumen", "Preview dokumen yang tersimpan di server"));

        LinearLayout images = new LinearLayout(this);
        images.setOrientation(LinearLayout.HORIZONTAL);
        ktpView = documentImage("Foto KTP");
        images.addView(documentBox(ktpView, "KTP"), documentLp(false));
        vehicleView = documentImage("Foto Kendaraan");
        images.addView(documentBox(vehicleView, "Kendaraan"), documentLp(true));
        card.addView(images);

        TextView hint = text("Ketuk foto untuk melihat ukuran penuh.", 9, "#718096", false);
        hint.setPadding(0, dp(10), 0, 0);
        card.addView(hint);
        return card;
    }

    protected View buildSecurityCard() {
        LinearLayout card = whiteCard();
        card.addView(sectionTitle("Keamanan", "Kelola sesi aplikasi driver"));
        Button logout = dangerButton("Keluar dari Akun");
        logout.setOnClickListener(view -> confirmLogout());
        card.addView(logout, new LinearLayout.LayoutParams(-1, dp(50)));
        return card;
    }

    protected void loadProfile() {
        if (loadingData || api == null) return;
        setLoading(true);

        api.executor().execute(() -> {
            try {
                DriverApiClient.Result result = api.get(PROFILE_ENDPOINT + System.currentTimeMillis());
                JSONObject profile = result.body.optJSONObject("profile");
                if (profile == null) throw new IllegalStateException("Data profil kosong.");
                session.updateDriverRuntime(profile);
                runOnUiThread(() -> {
                    bindProfile(profile);
                    setLoading(false);
                });
            } catch (DriverApiClient.ApiException error) {
                runOnUiThread(() -> {
                    setLoading(false);
                    showInfo(error.status == 401 ? "Sesi Berakhir" : "Profil Gagal Dimuat",
                            error.status == 401 ? "Silakan login kembali." : first(error.getMessage(), "Tidak dapat mengambil profil driver."));
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setLoading(false);
                    showInfo("Profil Gagal Dimuat", first(error.getMessage(), "Data profil tidak valid."));
                });
            }
        });
    }
}
