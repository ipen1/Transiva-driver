package com.transiva.app;

import androidx.core.content.ContextCompat;
import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.view.animation.OvershootInterpolator;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.transiva.app.driver.data.DriverDashboardRepositoryImpl;
import com.transiva.app.driver.data.DriverApiClient;
import com.transiva.app.driver.domain.DriverDashboardState;
import com.transiva.app.driver.domain.DriverClusterStatus;
import com.transiva.app.driver.domain.DriverOrder;
import com.transiva.app.driver.presentation.DriverDashboardContract;
import com.transiva.app.driver.presentation.DriverDashboardPresenter;
import com.transiva.app.driver.ui.DriverBottomNavigation;
import com.transiva.app.driver.ui.DriverPageTransition;

import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
public class DriverDashboardActivity extends DriverDashboardActivityLayer2 {

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TransivaNotificationPermission.ask(this);

        session = new SessionManager(this);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        requestGpsAfterLogin = getIntent() != null
                && getIntent().getBooleanExtra("request_gps_after_login", false);
        if (!validSession()) return;
        offerCountdownController = new DashboardOfferCountdownController(this, () -> {
            if (presenter != null) handler.postDelayed(() -> presenter.load(false), 350L);
        });
        cancellationController = new DashboardCancellationController(this, new DashboardCancellationController.Listener() {
            @Override public void onCancelConfirmed(DriverOrder order, String reason) {
                // Prevent a cancelled order from leaving a ghost Pesan badge. If
                // cancellation fails, a later FCM message can mark it unread again.
                if (order != null) DriverMessageUnreadRepository.clearOrder(DriverDashboardActivity.this, order.id);
                DriverBottomNavigation.refreshUnread(DriverDashboardActivity.this, bottomNavigationView);
                if (presenter != null) presenter.cancelOrder(order.id, clean(order.source), clean(order.status), reason);
            }
            @Override public void onMessage(String message) { showMessage(message); }
        });

        refreshController = new DashboardRefreshController(new DashboardRefreshController.Host() {
            @Override public Context context() { return DriverDashboardActivity.this; }
            @Override public DriverDashboardState state() { return currentState; }
            @Override public void refreshDashboard() { if (presenter != null) presenter.load(false); }
            @Override public void tickOfferCountdown() {
                if (offerCountdownController != null) offerCountdownController.tick();
            }
        });

        presenter = new DriverDashboardPresenter(
                new DriverDashboardRepositoryImpl(session),
                this
        );

        setContentView(buildScreen());
        DriverAppSettings.apply(this);
        presenter.load(true);
        showOpportunityPromptIfNeeded(getIntent());
    }


    protected void showOpportunityPromptIfNeeded(Intent intent) {
        if (intent == null || isFinishing()) return;
        String type = clean(intent.getStringExtra("notif_type")).toLowerCase(Locale.US);
        String suggestOnline = clean(intent.getStringExtra("suggest_online"));
        if (!"driver_opportunity".equals(type) || !"1".equals(suggestOnline)) return;

        String reason = clean(intent.getStringExtra("reason"));
        String distance = clean(intent.getStringExtra("pickup_distance_km"));
        String service = clean(intent.getStringExtra("service"));
        if (service.isEmpty()) service = "Transiva";
        String message = "all_online_busy".equals(reason)
                ? "Semua driver online di sekitar sedang sibuk. Ada permintaan " + service
                    + (distance.isEmpty() ? " di dekat lokasi terakhirmu." : " sekitar " + distance + " km dari lokasi terakhirmu.")
                : "Belum ada driver online yang siap di sekitar pickup. Ada permintaan " + service
                    + (distance.isEmpty() ? " di dekat lokasi terakhirmu." : " sekitar " + distance + " km dari lokasi terakhirmu.");
        message += "\n\nAktifkan status ONLINE jika kamu siap menerima order.";

        PremiumDialogs.builder(this)
                .setTitle("Peluang order di sekitar")
                .setMessage(message)
                .setPositiveButton("Online sekarang", (dialog, which) -> {
                    if (!ensureLocationReady()) return;
                    setSwitch(true);
                    if (presenter != null) {
                        presenter.setOnline(true, normalizeDriverType(session.getDriverType()));
                    }
                    showMessage("Status ONLINE sedang diaktifkan. Siap terima cuan!");
                })
                .setNegativeButton("Nanti", null)
                .show();

        // Mencegah dialog yang sama muncul lagi saat Activity memakai intent lama.
        intent.removeExtra("suggest_online");
    }

    @Override protected void onStart() {
        super.onStart();
        if (!realtimeReceiverRegistered) {
            try {
                IntentFilter filter = new IntentFilter(TransivaFirebaseService.ACTION_DRIVER_DATA_CHANGED);
                ContextCompat.registerReceiver(this, realtimeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
                realtimeReceiverRegistered = true;
            } catch (Throwable error) {
                TransivaDriverCrashReporter.nonFatal("dashboard_realtime_receiver", error);
            }
        }
    }

    @Override protected void onStop() {
        if (realtimeReceiverRegistered) {
            try { unregisterReceiver(realtimeReceiver); }
            catch (Throwable error) { TransivaDriverCrashReporter.nonFatal("dashboard_receiver_stop", error); }
            realtimeReceiverRegistered = false;
        }
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        if (pendingBackgroundLocationSettings) {
            if (hasBackgroundLocationPermission()) {
                continueOnlineAfterPermissions();
            } else {
                pendingBackgroundLocationSettings = false;
                pendingOnlineAfterGps = false;
                setSwitch(false);
                showMessage("Izin lokasi latar belakang belum aktif. Driver tetap OFFLINE.");
            }
        }
        DriverAppSettings.apply(this);
        if (!validSession()) return;
        if (refreshController != null) refreshController.start();
        if (presenter != null) presenter.load(false);

        // Kembali dari halaman pengaturan GPS tanpa perlu menutup/membuka ulang APK.
        if (pendingOnlineAfterGps && hasLocationPermission() && hasBackgroundLocationPermission() && isLocationProviderEnabled()) {
            pendingOnlineAfterGps = false;
            setSwitch(true);
            if (presenter != null) {
                presenter.setOnline(true, normalizeDriverType(session.getDriverType()));
            }
            showMessage("GPS aktif. Driver sedang diaktifkan ONLINE.");
        } else if (requestGpsAfterLogin && !gpsPromptShown) {
            gpsPromptShown = true;
            requestGpsAfterLogin = false;
            if (!isLocationProviderEnabled()) {
                showGpsEnableDialog(false);
            }
        }
    }

    @Override protected void onPause() {
        if (refreshController != null) refreshController.stop();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (refreshController != null) refreshController.destroy();
        handler.removeCallbacksAndMessages(null);
        if (presenter != null) presenter.destroy();
        super.onDestroy();
    }

    protected boolean validSession() {
        boolean valid = session != null
                && session.isLoggedIn()
                && "driver".equals(session.normalizeRole(session.getRole()))
                && !clean(session.getToken()).isEmpty();

        if (!valid) {
            if (session != null) session.forceLogout("invalid_driver_session");
            DriverServiceController.stop(this);
            Intent intent = new Intent(this, LoginActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            finish();
            return false;
        }
        return true;
    }

    protected View buildScreen() {
        page = new FrameLayout(this);
        page.setBackgroundColor(Color.parseColor("#F7FAFF"));

        shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        page.addView(shell, new FrameLayout.LayoutParams(-1, -1));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(14), dp(14), dp(20));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        buildHeader();

        homeSections = new LinearLayout(this);
        homeSections.setOrientation(LinearLayout.VERTICAL);
        content.addView(homeSections);

        buildStatusAndEmergency();
        buildDriverLocationMenu();

        // Order menjadi prioritas visual. Panel hanya muncul bila ada
        // order aktif/tawaran dan posisinya selalu tepat di bawah status.
        orderSections = new LinearLayout(this);
        orderSections.setOrientation(LinearLayout.VERTICAL);
        orderSections.setVisibility(View.GONE);
        homeSections.addView(orderSections);
        buildOrderSections();

        buildWalletAndPerformance();
        buildDriverGrowth();
        buildSmartAssistant();

        bottomNavigationView = DriverBottomNavigation.build(
                this, DriverBottomNavigation.ActiveItem.HOME);
        shell.addView(bottomNavigationView, new LinearLayout.LayoutParams(-1, dp(66)));

        loading = new ProgressBar(this);
        loading.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp =
                new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER);
        page.addView(loading, lp);

        return page;
    }

    protected void buildHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1));

        left.addView(text("Selamat bekerja 👋", 12, "#64748B", false));

        nameText = text(
                first(session.getName(), session.getUsername(), "Driver"),
                23,
                "#0B3A78",
                true
        );
        add(left, nameText, 0, dp(1), 0, 0);

        verificationText = text("Memeriksa akun…", 10, "#D97706", true);
        verificationText.setPadding(dp(8), dp(4), dp(8), dp(4));
        add(left, verificationText, 0, dp(5), 0, 0);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        lastUpdateText = text("Belum diperbarui", 10, "#64748B", false);
        lastUpdateText.setGravity(Gravity.END);
        actions.addView(lastUpdateText, new LinearLayout.LayoutParams(-2, -2));

        TextView settingsButton = text("⚙", 25, "#0B7CFF", true);
        settingsButton.setGravity(Gravity.CENTER);
        settingsButton.setContentDescription("Pengaturan Driver");
        settingsButton.setPadding(dp(5), 0, 0, 0);
        settingsButton.setOnClickListener(v ->
                startActivity(new Intent(this, DriverSettingsActivity.class)));
        actions.addView(settingsButton, new LinearLayout.LayoutParams(dp(42), dp(42)));

        row.addView(actions, new LinearLayout.LayoutParams(-2, -2));

        content.addView(row);
    }

    protected void buildStatusAndEmergency() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout status = card();
        status.setPadding(dp(13), dp(10), dp(13), dp(10));
        LinearLayout statusLine = new LinearLayout(this);
        statusLine.setGravity(Gravity.CENTER_VERTICAL);
        onlineLabel = text("OFFLINE", 13, "#EF4444", true);
        statusLine.addView(onlineLabel, new LinearLayout.LayoutParams(0, -2, 1));
        onlineSwitch = new Switch(this);
        onlineSwitch.setScaleX(.82f);
        onlineSwitch.setScaleY(.82f);
        onlineSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (suppressSwitch) return;
            if (checked && !ensureLocationReady()) { setSwitch(false); return; }
            presenter.setOnline(checked, normalizeDriverType(session.getDriverType()));
        });
        statusLine.addView(onlineSwitch, new LinearLayout.LayoutParams(dp(50), dp(38)));
        status.addView(statusLine);
        readinessText = text("Siap menerima order", 9, "#64748B", false);
        status.addView(readinessText);

        sosButton = dangerOutlineButton("🆘  SOS");
        sosButton.setTextSize(13);
        sosButton.setContentDescription("Kirim sinyal darurat ke seluruh driver dan admin");
        sosButton.setOnClickListener(v -> PremiumDialogs.builder(this)
                .setTitle("Kirim SOS Darurat?")
                .setMessage("Semua driver dan admin akan menerima nama, lokasi terakhir, serta order aktif Anda.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("KIRIM SOS", (d, w) -> sendEmergency())
                .show());

        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, dp(76), 1);
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, dp(76), 1);
        right.setMargins(dp(9), 0, 0, 0);
        row.addView(status, left);
        row.addView(sosButton, right);
        add(homeSections, row, 0, dp(16), 0, 0);
    }


    protected void buildDriverLocationMenu() {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(15), dp(13), dp(15), dp(13));

        TextView icon = text("📍", 24, "#0B7CFF", true);
        icon.setGravity(Gravity.CENTER);
        card.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.addView(text("Lokasi Driver", 16, "#0B3A78", true));
        info.addView(text("Lihat driver online & idle dalam radius 20 km", 10, "#64748B", false));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, -2, 1);
        ip.setMargins(dp(7), 0, dp(6), 0);
        card.addView(info, ip);

        TextView arrow = text("›", 28, "#0B7CFF", true);
        arrow.setGravity(Gravity.CENTER);
        card.addView(arrow, new LinearLayout.LayoutParams(dp(28), dp(42)));

        card.setContentDescription("Buka Lokasi Driver");
        card.setOnClickListener(v -> startActivity(new Intent(this, DriverLocationActivity.class)));
        add(homeSections, card, 0, dp(10), 0, 0);
    }

    protected void buildWalletAndPerformance() {
        LinearLayout wallet = new LinearLayout(this);
        wallet.setOrientation(LinearLayout.VERTICAL);
        wallet.setPadding(dp(17), dp(15), dp(17), dp(15));
        wallet.setBackground(gradient("#086BFF", "#2EA2FF", dp(22)));

        wallet.addView(text("Saldo Driver", 13, "#EAF4FF", true));
        balanceText = text("Rp 0", 27, "#FFFFFF", true);
        add(wallet, balanceText, 0, dp(3), 0, 0);

        TextView walletHint = text(
                "Ketuk untuk melihat pendapatan, deposit, withdraw, dan mutasi.",
                10,
                "#EAF5FF",
                false
        );

        add(wallet, walletHint, 0, dp(8), 0, 0);

        wallet.setOnClickListener(
                view -> DriverPageTransition.open(
                        this,
                        DriverEarningsActivity.class,
                        DriverPageTransition.HOME,
                        DriverPageTransition.EARNINGS
                )
        );

        add(homeSections, wallet, 0, dp(12), 0, 0);

        // Statistik dipindahkan ke menu Aktivitas agar dashboard lebih fokus.
        earningText = new TextView(this);
        tripText = new TextView(this);
        ratingText = new TextView(this);
        onlineMinutesText = new TextView(this);
        distanceText = new TextView(this);
    }

    protected TextView stat(LinearLayout parent, String value, String label) {
        LinearLayout box = card();
        box.setGravity(Gravity.CENTER);
        TextView number = text(value, 16, "#0B3A78", true);
        box.addView(number);
        box.addView(text(label, 10, "#64748B", false));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        if (parent.getChildCount() > 0) lp.setMargins(dp(7), 0, 0, 0);
        parent.addView(box, lp);
        return number;
    }


    protected void buildDriverGrowth() {
        LinearLayout card = card();
        card.addView(text("🏆 Driver Growth", 17, "#0B3A78", true));
        growthScoreText = text("Driver Score 70/100 • Good", 15, "#0B7CFF", true);
        add(card, growthScoreText, 0, dp(10), 0, 0);
        growthGoalText = text("Target hari ini Rp 0 / Rp 200.000", 13, "#334155", true);
        add(card, growthGoalText, 0, dp(7), 0, 0);
        growthRateText = text("Pendapatan/jam Rp 0", 12, "#64748B", false);
        add(card, growthRateText, 0, dp(5), 0, 0);
        destinationModeText = text("🏠 Mode tujuan: Nonaktif", 12, "#475569", true);
        add(card, destinationModeText, 0, dp(8), 0, 0);
        TextView hint = text("Ketuk untuk mengatur target pendapatan dan Mode Pulang/Area. Score tidak mengubah urutan status order.", 10, "#64748B", false);
        add(card, hint, 0, dp(7), 0, 0);
        card.setOnClickListener(v -> showGrowthSettings());
        add(homeSections, card, 0, dp(12), 0, 0);
    }

    protected void showGrowthSettings() {
        final DriverDashboardState state = currentState;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(6), dp(22), 0);
        EditText goal = new EditText(this);
        goal.setHint("Target harian, contoh 200000");
        goal.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        goal.setText(String.valueOf(state == null ? 200000 : Math.max(50000, state.dailyGoal)));
        box.addView(goal);
        EditText destination = new EditText(this);
        destination.setHint("Tujuan pulang / area, kosong = nonaktif");
        destination.setText(state == null ? "" : clean(state.destinationLabel));
        box.addView(destination);
        PremiumDialogs.builder(this)
                .setTitle("Driver Growth & Mode Pulang")
                .setMessage("Isi area tujuan agar Transiva dapat menyimpan preferensi arah driver. Kosongkan tujuan untuk menonaktifkan mode tujuan.")
                .setView(box)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Simpan", (d,w) -> {
                    long target;
                    try { target = Long.parseLong(clean(goal.getText().toString())); } catch (Exception e) { target = 200000L; }
                    target = Math.max(50000L, Math.min(5000000L, target));
                    String label = clean(destination.getText().toString());
                    saveGrowthSettings(target, label.isEmpty() ? "off" : "home", label);
                }).show();
    }

    protected void saveGrowthSettings(long goal, String mode, String label) {
        showLoading(true);
        DriverApiClient api = new DriverApiClient(session);
        api.executor().execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("daily_goal", goal);
                body.put("destination_mode", mode);
                body.put("destination_label", label);
                api.postIdempotent("driver_growth_native.php", body);
                runOnUiThread(() -> {
                    showLoading(false);
                    Toast.makeText(this, "Driver Growth disimpan", Toast.LENGTH_SHORT).show();
                    if (presenter != null) presenter.load(false);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    showLoading(false);
                    Toast.makeText(this, "Gagal menyimpan Driver Growth: " + error.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    protected void buildSmartAssistant() {
        LinearLayout card = card();
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(text("🔥 Area Ramai & AI Assistant", 17, "#0B3A78", true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView badge = text("LIVE", 10, "#FFFFFF", true);
        badge.setPadding(dp(9), dp(4), dp(9), dp(4));
        badge.setBackground(round("#16A34A", dp(12)));
        titleRow.addView(badge);
        card.addView(titleRow);

        hotspotText = text("Area sekitar Anda • NORMAL", 14, "#D97706", true);
        add(card, hotspotText, 0, dp(12), 0, 0);

        clusterCurrentText = text("📍 Cluster: mendeteksi lokasi...", 14, "#0B3A78", true);
        add(card, clusterCurrentText, 0, dp(10), 0, 0);

        android.widget.HorizontalScrollView clusterScroll = new android.widget.HorizontalScrollView(this);
        clusterScroll.setHorizontalScrollBarEnabled(false);
        clusterScroll.setFillViewport(true);
        clusterGrid = new LinearLayout(this);
        clusterGrid.setOrientation(LinearLayout.HORIZONTAL);
        clusterGrid.setGravity(Gravity.CENTER_VERTICAL);
        clusterScroll.addView(clusterGrid, new android.widget.HorizontalScrollView.LayoutParams(-2, -2));
        add(card, clusterScroll, 0, dp(8), 0, 0);
        renderClusterGrid(null);

        clusterListText = text("", 1, "#FFFFFF", false);
        clusterListText.setVisibility(View.GONE);

        assistantTitleText = text("Asisten Transiva", 14, "#0B3A78", true);
        add(card, assistantTitleText, 0, dp(12), 0, 0);
        assistantMessageText = text("Memuat rekomendasi…", 12, "#475569", false);
        add(card, assistantMessageText, 0, dp(5), 0, 0);

        LinearLayout queueBox = new LinearLayout(this);
        queueBox.setOrientation(LinearLayout.VERTICAL);
        queueBox.setPadding(dp(12), dp(11), dp(12), dp(11));
        queueBox.setBackground(round("#EEF6FF", dp(14)));
        queueText = text("Smart Queue: -", 14, "#086BFF", true);
        queueDetailText = text("Antrean dihitung otomatis dan adil.", 11, "#64748B", false);
        queueBox.addView(queueText);
        queueBox.addView(queueDetailText);
        add(card, queueBox, 0, dp(12), 0, 0);
        add(homeSections, card, 0, dp(12), 0, 0);
    }

    protected void renderClusterGrid(DriverDashboardState state) {
        if (clusterGrid == null) return;
        clusterGrid.removeAllViews();

        java.util.List<DriverClusterStatus> rows = new java.util.ArrayList<>();
        if (state != null && state.clusters != null) {
            for (DriverClusterStatus row : state.clusters) {
                if (row == null || row.id <= 0) continue;
                if (state.currentRegionId > 0 && row.regionId > 0 && row.regionId != state.currentRegionId) continue;
                rows.add(row);
            }
        }

        if (rows.isEmpty()) {
            TextView empty = text("Cluster akan mengikuti database setelah lokasi tersinkron.", 11, "#64748B", false);
            empty.setPadding(dp(8), dp(12), dp(8), dp(12));
            clusterGrid.addView(empty, new LinearLayout.LayoutParams(-2, -2));
            return;
        }

        for (int i = 0; i < rows.size(); i++) {
            DriverClusterStatus row = rows.get(i);
            int drivers = row.activeDrivers;
            boolean current = state != null && state.currentClusterId == row.id;
            String name = clean(row.name).isEmpty() ? "Cluster " + row.id : row.name.replace("/", "/\n");

            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            // Compact cluster chip: tetap terbaca di layar kecil tanpa membuat
            // panel Area Ramai terlalu tinggi/lebar.
            box.setPadding(dp(6), dp(5), dp(6), dp(5));

            int accent = clusterAccent(drivers);
            int fill = mixWithWhite(accent, current ? 0.87f : 0.94f);
            box.setBackground(roundStrokeColor(fill, accent, dp(14), current ? 2 : 1));

            TextView number = text(String.valueOf(row.id), 9, "#FFFFFF", true);
            number.setGravity(Gravity.CENTER);
            number.setBackground(roundStrokeColor(accent, accent, dp(999), 1));
            box.addView(number, new LinearLayout.LayoutParams(dp(22), dp(22)));

            TextView label = text(name, 8, "#0B3A78", true);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(2);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(dp(72), dp(27));
            labelLp.topMargin = dp(3);
            box.addView(label, labelLp);

            TextView count = text(drivers + " driver", 7, "#475569", false);
            count.setGravity(Gravity.CENTER);
            box.addView(count);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(84), dp(72));
            if (i > 0) lp.setMargins(dp(5), 0, 0, 0);
            clusterGrid.addView(box, lp);
        }
    }
}
