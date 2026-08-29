package com.transiva.app;

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

public class DriverDashboardActivity extends Activity
        implements DriverDashboardContract.View {

    private static final int REQ_LOCATION = 8702;
    private static final long IDLE_REFRESH_MS = 60000L;
    private static final long ACTIVE_REFRESH_MS = 15000L;
    private static final long OFFER_REFRESH_MS = 8000L;
    private static final long COUNTDOWN_TICK_MS = 1000L;
    private static final long SERVER_DRIFT_TOLERANCE_MS = 2500L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private SessionManager session;
    private DriverDashboardPresenter presenter;
    private DriverDashboardState currentState;

    private FrameLayout page;
    private LinearLayout shell;
    private LinearLayout content;
    private LinearLayout activeBox;
    private LinearLayout offerBox;
    private LinearLayout homeSections;
    private LinearLayout orderSections;

    private TextView nameText;
    private TextView verificationText;
    private TextView balanceText;
    private TextView earningText;
    private TextView tripText;
    private TextView ratingText;
    private TextView onlineLabel;
    private TextView readinessText;
    private TextView lastUpdateText;
    private TextView onlineMinutesText;
    private TextView distanceText;
    private TextView queueText;
    private TextView queueDetailText;
    private TextView assistantTitleText;
    private TextView assistantMessageText;
    private TextView hotspotText;
    private TextView growthScoreText;
    private TextView growthGoalText;
    private TextView growthRateText;
    private TextView destinationModeText;
    private TextView clusterCurrentText;
    private TextView clusterListText;
    private LinearLayout clusterGrid;
    private TextView priorityOrderTitle;
    private Button sosButton;
    private final Set<String> seenOfferKeys = new HashSet<>();
    private boolean firstOfferSnapshot = true;

    private Switch onlineSwitch;
    private boolean pendingOnlineAfterGps = false;
    private boolean requestGpsAfterLogin = false;
    private boolean gpsPromptShown = false;
    private ProgressBar loading;
    private boolean suppressSwitch;

    private final Map<String, Long> offerDeadlines = new HashMap<>();
    private final Map<String, TextView> countdownViews = new HashMap<>();
    private final Map<String, Button> offerButtons = new HashMap<>();
    private final Map<String, Integer> lastVibratedSecond = new HashMap<>();
    private final Set<String> expiredRefreshRequested = new HashSet<>();
    private Vibrator vibrator;

    private boolean realtimeReceiverRegistered = false;
    private final BroadcastReceiver realtimeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (presenter == null) return;
            handler.removeCallbacks(refreshRunnable);
            presenter.load(false);
            handler.postDelayed(refreshRunnable, WaveLoadGuard.jitter(adaptiveRefreshMs()));
        }
    };

    private final Runnable refreshRunnable = new Runnable() {
        @Override public void run() {
            if (presenter != null) presenter.load(false);
            handler.postDelayed(this, WaveLoadGuard.jitter(adaptiveRefreshMs()));
        }
    };

    private long adaptiveRefreshMs() {
        DriverDashboardState state = currentState;
        if (state != null) {
            if (state.offers != null && !state.offers.isEmpty()) return OFFER_REFRESH_MS;
            if (state.activeOrders != null && !state.activeOrders.isEmpty()) return ACTIVE_REFRESH_MS;
        }
        return IDLE_REFRESH_MS;
    }

    private final Runnable countdownRunnable = new Runnable() {
        @Override public void run() {
            updateAllCountdowns();
            handler.postDelayed(this, COUNTDOWN_TICK_MS);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TransivaNotificationPermission.ask(this);

        session = new SessionManager(this);
        requestGpsAfterLogin = getIntent() != null
                && getIntent().getBooleanExtra("request_gps_after_login", false);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (!validSession()) return;

        presenter = new DriverDashboardPresenter(
                new DriverDashboardRepositoryImpl(session),
                this
        );

        setContentView(buildScreen());
        DriverAppSettings.apply(this);
        presenter.load(true);
        showOpportunityPromptIfNeeded(getIntent());
    }


    private void showOpportunityPromptIfNeeded(Intent intent) {
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

        new AlertDialog.Builder(this)
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
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(realtimeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    registerReceiver(realtimeReceiver, filter);
                }
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
        DriverAppSettings.apply(this);
        if (!validSession()) return;
        handler.removeCallbacks(refreshRunnable);
        handler.removeCallbacks(countdownRunnable);
        handler.postDelayed(refreshRunnable, WaveLoadGuard.jitter(adaptiveRefreshMs()));
        handler.post(countdownRunnable);
        if (presenter != null) presenter.load(false);

        // Kembali dari halaman pengaturan GPS tanpa perlu menutup/membuka ulang APK.
        if (pendingOnlineAfterGps && hasLocationPermission() && isLocationProviderEnabled()) {
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
        handler.removeCallbacks(refreshRunnable);
        handler.removeCallbacks(countdownRunnable);
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (presenter != null) presenter.destroy();
        super.onDestroy();
    }

    private boolean validSession() {
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

    private View buildScreen() {
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

        shell.addView(
                DriverBottomNavigation.build(
                        this,
                        DriverBottomNavigation.ActiveItem.HOME
                ),
                new LinearLayout.LayoutParams(-1, dp(66))
        );

        loading = new ProgressBar(this);
        loading.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp =
                new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER);
        page.addView(loading, lp);

        return page;
    }

    private void buildHeader() {
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

    private void buildStatusAndEmergency() {
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
        sosButton.setOnClickListener(v -> new AlertDialog.Builder(this)
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


    private void buildDriverLocationMenu() {
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

    private void buildWalletAndPerformance() {
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

    private TextView stat(LinearLayout parent, String value, String label) {
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


    private void buildDriverGrowth() {
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

    private void showGrowthSettings() {
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
        new AlertDialog.Builder(this)
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

    private void saveGrowthSettings(long goal, String mode, String label) {
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

    private void buildSmartAssistant() {
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

        clusterGrid = new LinearLayout(this);
        clusterGrid.setOrientation(LinearLayout.HORIZONTAL);
        clusterGrid.setGravity(Gravity.CENTER);
        add(card, clusterGrid, 0, dp(8), 0, 0);
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

    private void renderClusterGrid(DriverDashboardState state) {
        if (clusterGrid == null) return;
        clusterGrid.removeAllViews();

        String[] fallbackNames = {"Sumbersari", "Dolago /\nRibamba", "Parigi", "Pangi", "Toboli"};
        for (int i = 0; i < 5; i++) {
            int id = i + 1;
            String name = fallbackNames[i];
            int drivers = 0;
            boolean current = state != null && state.currentClusterId == id;

            if (state != null && state.clusters != null) {
                for (DriverClusterStatus row : state.clusters) {
                    if (row != null && row.id == id) {
                        name = clean(row.name).isEmpty() ? fallbackNames[i] : row.name.replace("/", "/\n");
                        drivers = row.activeDrivers;
                        break;
                    }
                }
            }

            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setPadding(dp(2), dp(6), dp(2), dp(6));

            int accent = clusterAccent(drivers);
            int fill = mixWithWhite(accent, current ? 0.87f : 0.94f);
            box.setBackground(roundStrokeColor(fill, accent, dp(14), current ? 2 : 1));

            TextView number = text(String.valueOf(id), 10, "#FFFFFF", true);
            number.setGravity(Gravity.CENTER);
            number.setBackground(roundStrokeColor(accent, accent, dp(999), 1));
            box.addView(number, new LinearLayout.LayoutParams(dp(24), dp(24)));

            TextView label = text(name, 8, "#0B3A78", true);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(2);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(-1, dp(31));
            labelLp.topMargin = dp(3);
            box.addView(label, labelLp);

            TextView count = text(drivers + " driver", 8, "#475569", false);
            count.setGravity(Gravity.CENTER);
            box.addView(count);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(84), 1);
            if (i > 0) lp.setMargins(dp(3), 0, 0, 0);
            clusterGrid.addView(box, lp);
        }
    }

    private int clusterAccent(int drivers) {
        if (drivers <= 2) return Color.parseColor("#16A34A");
        if (drivers <= 6) return Color.parseColor("#0B7CFF");
        if (drivers <= 15) return Color.parseColor("#F59E0B");
        return Color.parseColor("#EF4444");
    }

    private void sendEmergency() {
        showLoading(true);
        DriverApiClient api = new DriverApiClient(session);
        api.executor().execute(() -> {
            try {
                JSONObject body = session.getLastLocationJson();
                body.put("message", "Membutuhkan bantuan darurat");
                body.put("current_order_id", clean(session.get("current_order_id")));
                api.post("driver_sos_native.php", body);
                runOnUiThread(() -> { showLoading(false); showMessage("SOS terkirim ke seluruh driver dan admin."); });
            } catch (Exception error) {
                runOnUiThread(() -> { showLoading(false); showMessage("SOS gagal dikirim. Hubungi admin melalui telepon bila kondisi darurat."); });
            }
        });
    }

    private void buildOrderSections() {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        priorityOrderTitle = text("⚡ Order Prioritas", 16, "#0B3A78", true);
        header.addView(priorityOrderTitle, new LinearLayout.LayoutParams(0, -2, 1));
        TextView live = text("LANGSUNG", 9, "#FFFFFF", true);
        live.setGravity(Gravity.CENTER);
        live.setPadding(dp(9), dp(4), dp(9), dp(4));
        live.setBackground(round("#EF4444", dp(999)));
        header.addView(live);
        add(orderSections, header, 0, dp(11), 0, dp(3));

        activeBox = new LinearLayout(this);
        activeBox.setOrientation(LinearLayout.VERTICAL);
        orderSections.addView(activeBox);

        offerBox = new LinearLayout(this);
        offerBox.setOrientation(LinearLayout.VERTICAL);
        orderSections.addView(offerBox);
    }

    @Override public void showLoading(boolean visible) {
        loading.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    @Override public void showDashboard(DriverDashboardState state) {
        currentState = state;

        nameText.setText(first(state.displayName, state.username, "Driver"));
        verificationText.setText(
                state.verified ? "✓ Terverifikasi" : "• Belum Terverifikasi");
        verificationText.setTextColor(Color.parseColor(
                state.verified ? "#0E9F4B" : "#D97706"));
        verificationText.setBackground(round(
                state.verified ? "#EAFBF1" : "#FFF7E6", dp(12)));

        balanceText.setText(rupiah(state.balance));
        earningText.setText(rupiah(state.todayEarning));
        if (growthScoreText != null) growthScoreText.setText("Driver Score " + state.driverScore + "/100 • " + state.driverScoreLabel);
        if (growthGoalText != null) growthGoalText.setText("Target hari ini " + rupiah(state.todayEarning) + " / " + rupiah(state.dailyGoal) + " • " + state.goalProgress + "%");
        if (growthRateText != null) growthRateText.setText("Pendapatan/jam " + rupiah(state.earningPerHour));
        if (destinationModeText != null) {
            String dm = clean(state.destinationMode);
            String dl = clean(state.destinationLabel);
            destinationModeText.setText("🏠 Mode tujuan: " + ("off".equalsIgnoreCase(dm) ? "Nonaktif" : (dl.isEmpty() ? ("home".equalsIgnoreCase(dm) ? "Pulang" : "Area pilihan") : dl)));
        }
        tripText.setText(String.valueOf(state.todayTrips));
        ratingText.setText(String.format(Locale.US, "%.1f", state.rating));
        onlineMinutesText.setText(formatMinutes(state.onlineMinutes));
        distanceText.setText(String.format(Locale.US, "%.1f km", state.todayDistanceKm));
        queueText.setText(state.queueRank > 0
                ? "Smart Queue: posisi " + state.queueRank + " dari " + Math.max(state.queueTotal, state.queueRank)
                : "Smart Queue: belum aktif");
        queueDetailText.setText(first(state.queueLabel, "Antrean dihitung otomatis dan adil."));
        assistantTitleText.setText(first(state.assistantTitle, "Asisten Transiva"));
        assistantMessageText.setText(buildAssistantMessage(state));
        int localHotspotScore = effectiveHotspotScore(state);
        String localHotspotLevel = localHotspotScore > 0
                ? effectiveHotspotLevel(state, localHotspotScore)
                : first(state.hotspotLevel, "NORMAL");
        hotspotText.setText(first(state.hotspotName, "Area sekitar Anda") + " • "
                + localHotspotLevel + " (" + localHotspotScore + "% )");
        if (clusterCurrentText != null) {
            clusterCurrentText.setText(state.currentClusterId > 0
                    ? "📍 Anda di Cluster " + state.currentClusterId + " • " + state.currentClusterName
                    : "📍 Cluster belum terdeteksi • aktifkan GPS");
        }
        renderClusterGrid(state);

        onlineLabel.setText(state.online ? "ONLINE" : "OFFLINE");
        onlineLabel.setTextColor(Color.parseColor(
                state.online ? "#16A34A" : "#EF4444"));
        setSwitch(state.online);

        session.put("driver_server_online", state.online ? "1" : "0");

        // Status ONLINE dari server tidak boleh membuat aplikasi crash ketika
        // driver login kembali saat GPS/lokasi sedang mati. Driver tetap boleh
        // masuk ke dashboard; tracking baru dijalankan setelah izin + provider
        // lokasi benar-benar tersedia. Status ONLINE server tetap dipertahankan.
        boolean locationReady = hasLocationPermission() && isLocationProviderEnabled();
        if (state.online && locationReady) {
            DriverServiceController.start(this);
        } else {
            DriverServiceController.stop(this);
        }

        readinessText.setText(
                state.online
                        ? (locationReady
                            ? "Online • Siap Terima Orderan."
                            : "Online • GPS/lokasi mati.")
                        : "Offline • Order tidak ditawarkan."
        );

        lastUpdateText.setText("Baru diperbarui");

        activeBox.removeAllViews();
        if (state.activeOrders == null || state.activeOrders.isEmpty()) {
            session.remove("current_order_id");
            // Tidak tampilkan placeholder di panel prioritas. Bila ada tawaran,
            // tawaran langsung menjadi kartu pertama yang terlihat.
        } else {
            session.put("current_order_id", state.activeOrders.get(0).id);
            int slot = 0;
            for (DriverOrder activeOrder : state.activeOrders) {
                slot++;
                if (activeOrder.raw != null) {
                    try {
                        activeOrder.raw.put("concurrent_slot", slot);
                        activeOrder.raw.put("active_count", state.activeOrders.size());
                        activeOrder.raw.put("max_active_orders", 2);
                    } catch (Exception ignored) { }
                }
                activeBox.addView(orderCard(activeOrder, true));
            }
        }

        offerBox.removeAllViews();
        countdownViews.clear();
        offerButtons.clear();
        if (!state.online) {
            // Panel prioritas tetap ringkas; status offline sudah terlihat di atas.
        } else if (state.offers.isEmpty()) {
            // Tidak tampilkan placeholder agar order aktif tetap menjadi fokus.
        } else {
            Set<String> activeOfferKeys = new HashSet<>();
            boolean hasFreshOffer = false;
            for (DriverOrder offer : state.offers) {
                String freshKey = offerKey(offer);
                if (!seenOfferKeys.contains(freshKey)) hasFreshOffer = true;
            }
            if (!firstOfferSnapshot && hasFreshOffer) playIncomingOrderEffect();
            for (DriverOrder offer : state.offers) {
                String key = offerKey(offer);
                activeOfferKeys.add(key);
                syncOfferDeadline(offer);
                offerBox.addView(orderCard(offer, false));
            }
            offerDeadlines.keySet().retainAll(activeOfferKeys);
            lastVibratedSecond.keySet().retainAll(activeOfferKeys);
            expiredRefreshRequested.retainAll(activeOfferKeys);
            updateAllCountdowns();
            seenOfferKeys.clear();
            seenOfferKeys.addAll(activeOfferKeys);
            firstOfferSnapshot = false;
        }
        boolean hasPriorityOrder = (state.activeOrders != null && !state.activeOrders.isEmpty())
                || (state.offers != null && !state.offers.isEmpty());
        if (orderSections != null) {
            orderSections.setVisibility(hasPriorityOrder ? View.VISIBLE : View.GONE);
        }
        if (priorityOrderTitle != null) {
            int activeCount = state.activeOrders == null ? 0 : state.activeOrders.size();
            int offerCount = state.offers == null ? 0 : state.offers.size();
            if (activeCount > 0) {
                priorityOrderTitle.setText("🚗 Order Aktif" + (activeCount > 1 ? " • " + activeCount + " perjalanan" : ""));
            } else if (offerCount > 0) {
                priorityOrderTitle.setText("⚡ Tawaran Masuk" + (offerCount > 1 ? " • " + offerCount + " order" : ""));
            } else {
                priorityOrderTitle.setText("⚡ Order Prioritas");
            }
        }
        if (state.offers == null || state.offers.isEmpty()) {
            seenOfferKeys.clear();
            firstOfferSnapshot = false;
        }
    }

    private void playIncomingOrderEffect() {
        if (page == null || offerBox == null) return;
        try {
            ObjectAnimator sx1 = ObjectAnimator.ofFloat(offerBox, View.SCALE_X, 0.92f, 1.04f, 1f);
            ObjectAnimator sy1 = ObjectAnimator.ofFloat(offerBox, View.SCALE_Y, 0.92f, 1.04f, 1f);
            ObjectAnimator alpha = ObjectAnimator.ofFloat(offerBox, View.ALPHA, 0.35f, 1f);
            AnimatorSet set = new AnimatorSet();
            set.playTogether(sx1, sy1, alpha);
            set.setDuration(650);
            set.setInterpolator(new OvershootInterpolator());
            set.start();
            if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0,180,90,180,90,320}, -1));
            else vibrator.vibrate(new long[]{0,180,90,180,90,320}, -1);
            Toast.makeText(this, "🔥 ORDER BARU MASUK! Tetap semangat dan utamakan keselamatan.", Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) { }
    }

    @Override public void showActionLoading(String action, boolean visible) {
        showLoading(visible);
        onlineSwitch.setEnabled(!visible);
    }

    @Override public void showMessage(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override public void showSessionExpired() {
        session.forceLogout("session_expired");
        DriverServiceController.stop(this);
        Intent intent = new Intent(this, LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }

    @Override public void openTrip(DriverOrder order) {
        try {
            session.put("current_order_id", order.id);
            Intent intent = new Intent(this, DriverTripActivity.class);
            intent.putExtra("order_json", order.raw.toString());
            intent.putExtra("order_table", order.source);
            intent.putExtra("driver", session.getUsername());
            intent.putExtra("driver_type",
                    normalizeDriverType(session.getDriverType()));
            startActivity(intent);
        } catch (Exception error) {
            showMessage("Tidak dapat membuka halaman trip.");
        }
    }

    private JSONObject foodPayload(JSONObject raw) {
        if (raw == null) return new JSONObject();
        JSONObject direct = raw.optJSONObject("food");
        if (direct != null) return direct;
        try {
            String note = raw.optString("note", "");
            if (!note.trim().isEmpty()) {
                JSONObject parsed = new JSONObject(note);
                if ("food".equalsIgnoreCase(parsed.optString("type", "")) || parsed.has("items")) return parsed;
            }
        } catch (Exception ignored) { }
        return new JSONObject();
    }

    private boolean isFoodOrder(DriverOrder order) {
        if (order == null) return false;
        String s = (clean(order.serviceName) + " " + (order.raw == null ? "" : order.raw.optString("order_type", ""))).toLowerCase(Locale.US);
        return s.contains("food") || s.contains("makanan") || s.contains("restaurant");
    }

    private String merchantStatusLabel(String raw) {
        String s = clean(raw).toLowerCase(Locale.US);
        if (s.equals("merchant_accepted") || s.equals("accepted")) return "Diterima merchant";
        if (s.equals("preparing") || s.equals("processing")) return "Sedang disiapkan";
        if (s.equals("ready")) return "Siap diambil";
        if (s.equals("merchant_rejected") || s.equals("rejected")) return "Ditolak merchant";
        return raw;
    }

    private String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) {
            String c = clean(value);
            if (!c.isEmpty() && !"null".equalsIgnoreCase(c)) return c;
        }
        return "";
    }

    private String customerNote(JSONObject raw) {
        if (raw == null) return "";

        String direct = firstNonEmpty(
                raw.optString("customer_note", ""),
                raw.optString("note_customer", ""),
                raw.optString("order_note", ""),
                raw.optString("special_instructions", ""),
                raw.optString("instructions", "")
        );
        if (!direct.isEmpty()) return direct;

        String note = clean(raw.optString("note", ""));
        if (note.isEmpty() || "-".equals(note)) return "";

        if (note.startsWith("{")) {
            try {
                JSONObject parsed = new JSONObject(note);
                return firstNonEmpty(
                        parsed.optString("customer_note", ""),
                        parsed.optString("note_customer", ""),
                        parsed.optString("text", ""),
                        parsed.optString("note", ""),
                        parsed.optString("special_instructions", ""),
                        parsed.optString("instructions", ""),
                        parsed.optString("message", ""),
                        parsed.optString("remark", "")
                );
            } catch (Exception ignored) {
                return "";
            }
        }

        // Jangan menampilkan JSON/array mentah di kartu order.
        if (note.startsWith("[")) return "";
        return note;
    }

    private String buildAssistantMessage(DriverDashboardState state) {
        if (state == null) return "Belum ada rekomendasi.";
        java.util.LinkedHashSet<String> services = new java.util.LinkedHashSet<>();
        int activeCount = 0;
        int offerCount = 0;

        if (state.activeOrders != null) {
            for (DriverOrder order : state.activeOrders) {
                if (order == null) continue;
                activeCount++;
                services.add(aiServiceLabel(order));
            }
        }
        if (state.offers != null) {
            for (DriverOrder order : state.offers) {
                if (order == null) continue;
                offerCount++;
                services.add(aiServiceLabel(order));
            }
        }

        if (activeCount > 0) {
            String serviceText = joinServices(services);
            return "Terdeteksi " + activeCount + " order aktif"
                    + (serviceText.isEmpty() ? "" : " (" + serviceText + ")")
                    + ". Fokus selesaikan perjalanan dengan aman. "
                    + (offerCount > 0 ? offerCount + " tawaran lain juga terdeteksi." : "Smart Queue aktif kembali setelah order selesai.");
        }
        if (offerCount > 0) {
            String serviceText = joinServices(services);
            return "AI mendeteksi " + offerCount + " tawaran order"
                    + (serviceText.isEmpty() ? "" : " (" + serviceText + ")")
                    + ". Pilih order sesuai kendaraan dan jarak Anda.";
        }
        return first(state.assistantMessage, "Belum ada rekomendasi.");
    }

    private int effectiveHotspotScore(DriverDashboardState state) {
        if (state == null) return 0;
        int serverScore = Math.max(0, state.hotspotScore);
        int activeCount = state.activeOrders == null ? 0 : state.activeOrders.size();
        int offerCount = state.offers == null ? 0 : state.offers.size();

        // Fallback lokal agar semua jenis order, termasuk TransSend/pickup,
        // ikut terbaca walaupun backend hotspot lama hanya menghitung tabel orders.
        int localScore = Math.min(100, (activeCount * 25) + (offerCount * 18));
        return Math.max(serverScore, localScore);
    }

    private String effectiveHotspotLevel(DriverDashboardState state, int score) {
        if (score >= 70) return "RAMAI";
        if (score >= 35) return "SEDANG";
        if (score > 0) return "ADA ORDER";
        return state == null ? "NORMAL" : first(state.hotspotLevel, "NORMAL");
    }

    private String aiServiceLabel(DriverOrder order) {
        if (order == null) return "";
        String raw = first(order.serviceName,
                order.raw == null ? "" : order.raw.optString("order_type"),
                order.raw == null ? "" : order.raw.optString("service_type"),
                order.source).toLowerCase(Locale.US);
        if (raw.contains("pickup") || raw.contains("send")) return "TransSend";
        if (raw.contains("shop") || raw.contains("mart")) return "TransShop";
        if (raw.contains("food")) return "TransFood";
        if (raw.contains("car") || raw.contains("mobil")) return "TransCar";
        if (raw.contains("ride") || raw.contains("bike") || raw.contains("motor")) return "TransRide";
        return clean(order.serviceName);
    }

    private String joinServices(java.util.LinkedHashSet<String> services) {
        if (services == null || services.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (String service : services) {
            if (clean(service).isEmpty()) continue;
            if (out.length() > 0) out.append(", ");
            out.append(service);
        }
        return out.toString();
    }

    private String serviceIcon(String service) {
        String s = clean(service).toLowerCase(Locale.US);
        if (s.contains("food")) return "🍜";
        if (s.contains("send")) return "📦";
        if (s.contains("car")) return "🚗";
        if (s.contains("shop") || s.contains("mart")) return "🛍";
        return "🏍";
    }

    private String serviceSubtitle(String service) {
        String s = clean(service).toLowerCase(Locale.US);
        if (s.contains("food")) return "Ambil pesanan di merchant lalu antar ke customer";
        if (s.contains("send")) return "Ambil paket dari pengirim lalu antar ke penerima";
        if (s.contains("car")) return "Jemput penumpang • layanan mobil Transiva";
        if (s.contains("shop") || s.contains("mart")) return "Belanja titipan customer lalu antar ke tujuan";
        return "Jemput penumpang • layanan motor Transiva";
    }

    private View orderCard(DriverOrder order, boolean active) {
        LinearLayout card = card();
        boolean queued = !active && order.raw != null && order.raw.optBoolean("queued", false);
        int queuePosition = order.raw == null ? 0 : order.raw.optInt("queue_position", 0);
        int concurrentSlot = order.raw == null ? 0 : order.raw.optInt("concurrent_slot", 0);
        String cardTitle = active ? (concurrentSlot > 0 ? "Order Aktif " + concurrentSlot + "/2" : "Order Aktif") : (queued ? "Order Antrean" : "Tawaran");
        card.addView(text(
                cardTitle + " #" + order.id,
                17, "#0B3A78", true));
        if (queued) {
            add(card, text("Sudah diterima • antrean " + Math.max(1, queuePosition) + ". Selesaikan order aktif terlebih dahulu.",
                    13, "#B45309", true), 0, dp(6), 0, 0);
        }
        String serviceLabel = aiServiceLabel(order);
        String serviceIcon = serviceIcon(serviceLabel);
        TextView serviceBadge = text(serviceIcon + "  " + serviceLabel, 15, "#0B7CFF", true);
        serviceBadge.setPadding(dp(12), dp(8), dp(12), dp(8));
        serviceBadge.setBackground(roundStroke("#EFF6FF", "#BFDBFE", dp(16), 1));
        add(card, serviceBadge, 0, dp(7), 0, 0);

        JSONObject identityRaw = order.raw == null ? new JSONObject() : order.raw;
        boolean familyOrder = "family".equalsIgnoreCase(identityRaw.optString("account_type", "personal"));
        String customerName = firstNonEmpty(identityRaw.optString("customer_name", ""), "Customer");
        String familyName = firstNonEmpty(identityRaw.optString("family_name", ""), customerName);
        String familyRelation = firstNonEmpty(identityRaw.optString("family_relationship", ""), "Keluarga");
        String identityTitle = familyOrder ? "👨‍👩‍👧 Transiva Family" : "👤 Akun Pribadi";
        String identityName = familyOrder ? (familyName + " • " + familyRelation) : customerName;
        LinearLayout identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.VERTICAL);
        identity.setPadding(dp(12), dp(10), dp(12), dp(10));
        identity.setBackground(roundStroke(familyOrder ? "#F5F3FF" : "#F8FAFC", familyOrder ? "#DDD6FE" : "#E2E8F0", dp(14), 1));
        identity.addView(text(identityTitle, 12, familyOrder ? "#6D28D9" : "#475569", true));
        TextView identityPerson = text(identityName, 16, "#0F172A", true);
        identityPerson.setPadding(0, dp(3), 0, 0);
        identity.addView(identityPerson);
        add(card, identity, 0, dp(8), 0, 0);

        TextView serviceExplain = text(serviceSubtitle(serviceLabel), 12, "#64748B", false);
        add(card, serviceExplain, 0, dp(6), 0, 0);
        add(card, text("Penjemputan:\n" + order.pickupAddress,
                13, "#334155", false), 0, dp(8), 0, 0);
        add(card, text("Pengantaran:\n" + order.destinationAddress,
                13, "#334155", false), 0, dp(6), 0, 0);

        String meta = "Pendapatan " + rupiah(order.driverEarning) + " • " + ("balance".equalsIgnoreCase(order.paymentMethod) ? "💳 TransPay" : "💵 Tunai");
        if (!clean(order.pickupDistanceText).isEmpty()) {
            meta += " • " + order.pickupDistanceText;
        }
        add(card, text(meta, 13, "#0F172A", true), 0, dp(8), 0, 0);

        if (!active) {
            double tripKm = order.raw == null ? 0d : Math.max(order.raw.optDouble("distance_km", 0d), order.raw.optDouble("trip_distance_km", 0d));
            double pickupKm = order.raw == null ? 0d : Math.max(order.raw.optDouble("pickup_distance_km", 0d), order.raw.optDouble("driver_distance_km", 0d));
            double basisKm = tripKm > 0d ? tripKm : pickupKm;
            String radar = "📡 Smart Radar";
            if (pickupKm > 0d) radar += " • " + String.format(Locale.US, "%.1f km ke pickup", pickupKm);
            if (tripKm > 0d) radar += " • trip " + String.format(Locale.US, "%.1f km", tripKm);
            if (basisKm > 0d && order.driverEarning > 0) radar += " • ±" + rupiah(Math.round(order.driverEarning / basisKm)) + "/km";
            add(card, text(radar, 12, "#0B7CFF", true), 0, dp(7), 0, 0);
        }

        String customerNote = customerNote(order.raw);
        if (!customerNote.isEmpty()) {
            add(card, text("📝 Catatan customer: " + customerNote,
                    13, "#7C2D12", true), 0, dp(7), 0, 0);
        }

        if (isFoodOrder(order)) {
            JSONObject raw = order.raw == null ? new JSONObject() : order.raw;
            JSONObject food = foodPayload(raw);
            String merchantStatus = firstNonEmpty(raw.optString("merchant_status", ""), food.optString("merchant_status", ""));
            int cookMinutes = raw.optInt("cook_minutes", food.optInt("cook_minutes", 0));
            String restaurant = firstNonEmpty(raw.optString("restaurant_name", ""), raw.optString("merchant_name", ""),
                    food.optString("restaurant_name", ""), food.optString("merchant_name", ""));
            if (!restaurant.isEmpty()) {
                add(card, text("Merchant: " + restaurant, 13, "#334155", true), 0, dp(6), 0, 0);
            }
            if (!merchantStatus.isEmpty()) {
                String kitchen = "Dapur: " + merchantStatusLabel(merchantStatus);
                if (cookMinutes > 0) kitchen += " • ±" + cookMinutes + " menit";
                add(card, text(kitchen, 13, "#B45309", true), 0, dp(5), 0, 0);
            }
        }

        if (!active && order.remainingSeconds >= 0) {
            String key = offerKey(order);
            TextView countdown = text("Menghitung…", 14, "#16A34A", true);
            countdown.setGravity(Gravity.CENTER);
            countdown.setMinWidth(dp(112));
            countdown.setPadding(dp(13), dp(7), dp(13), dp(7));

            LinearLayout countdownRow = new LinearLayout(this);
            countdownRow.setGravity(Gravity.END);
            countdownRow.addView(countdown, new LinearLayout.LayoutParams(-2, -2));
            add(card, countdownRow, 0, dp(10), 0, 0);
            countdownViews.put(key, countdown);
        }

        boolean capacityReached = !active
                && !queued
                && currentState != null
                && currentState.activeOrders != null
                && currentState.activeOrders.size() >= 2;

        Button action = primaryButton(active ? "Lanjutkan Trip" : (queued ? "Menunggu Order Aktif Selesai" : "Ambil Order"));

        if (active) {
            action.setOnClickListener(v -> openTrip(order));
            add(card, action, 0, dp(12), 0, 0);

            if (canDriverCancel(order.status)) {
                Button cancel = dangerOutlineButton("Batalkan Order");
                cancel.setOnClickListener(v -> showCancelOrderDialog(order));
                add(card, cancel, 0, dp(9), 0, 0);
            }
        } else if (queued) {
            action.setEnabled(false);
            add(card, action, 0, dp(12), 0, 0);
        } else if (capacityReached) {
            TextView capacityNotice = text(
                    "Maksimal 2 orderan yang berjalan. Selesaikan salah satu order aktif untuk menerima order ini.",
                    14, "#B45309", true);
            capacityNotice.setPadding(dp(14), dp(12), dp(14), dp(12));
            capacityNotice.setBackground(roundStroke(
                    "#FFF7E6", "#F59E0B", dp(14), 1));
            add(card, capacityNotice, 0, dp(12), 0, 0);
        } else {
            String key = offerKey(order);
            offerButtons.put(key, action);
            action.setOnClickListener(v -> {
                if (remainingMillis(key) <= 0L) {
                    action.setEnabled(false);
                    action.setText("Tawaran berakhir");
                    showMessage("Tawaran order sudah berakhir.");
                    return;
                }
                presenter.acceptOrder(order.id, clean(order.source));
            });
            add(card, action, 0, dp(12), 0, 0);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(7), 0, dp(12));
        card.setLayoutParams(lp);
        return card;
    }

    private String normalizeOrderStatus(String status) {
        if (status == null) {
            return "";
        }

        return status.trim()
                .toLowerCase(Locale.US)
                .replace('-', '_')
                .replace(' ', '_');
    }

    private boolean canDriverCancel(String status) {
        return DriverOrderCancellationPolicy.canCancel(status);
    }

    private void showCancelOrderDialog(DriverOrder order) {
        if (order == null || !canDriverCancel(order.status)) {
            showMessage(
                    "Order tidak dapat dibatalkan pada status "
                            + normalizeOrderStatus(order == null ? "" : order.status)
            );
            return;
        }

        final String[] reasons = DriverOrderCancellationPolicy.reasons();

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Batalkan order #" + order.id)
                .setSingleChoiceItems(reasons, -1, null)
                .setNegativeButton("Kembali", null)
                .setPositiveButton("Lanjutkan", null)
                .create();

        dialog.setOnShowListener(ignored -> {
            Button continueButton = dialog.getButton(
                    AlertDialog.BUTTON_POSITIVE
            );

            continueButton.setOnClickListener(view -> {
                int selectedPosition = dialog.getListView()
                        .getCheckedItemPosition();

                if (selectedPosition < 0) {
                    Toast.makeText(
                            this,
                            "Silakan pilih alasan pembatalan.",
                            Toast.LENGTH_SHORT
                    ).show();
                    return;
                }

                dialog.dismiss();

                if (selectedPosition == reasons.length - 1) {
                    showCustomCancelReasonDialog(order);
                    return;
                }

                confirmCancelOrder(order, reasons[selectedPosition]);
            });
        });

        dialog.show();
    }

    private void showCustomCancelReasonDialog(DriverOrder order) {
        final EditText reasonInput = new EditText(this);
        reasonInput.setHint("Tuliskan alasan pembatalan");
        reasonInput.setSingleLine(false);
        reasonInput.setMinLines(3);
        reasonInput.setMaxLines(5);
        reasonInput.setPadding(
                dp(16),
                dp(12),
                dp(16),
                dp(12)
        );

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Alasan lainnya")
                .setMessage("Jelaskan alasan pembatalan order.")
                .setView(reasonInput)
                .setNegativeButton("Kembali", null)
                .setPositiveButton("Lanjutkan", null)
                .create();

        dialog.setOnShowListener(ignored -> {
            Button continueButton = dialog.getButton(
                    AlertDialog.BUTTON_POSITIVE
            );

            continueButton.setOnClickListener(view -> {
                String reason = reasonInput.getText()
                        .toString()
                        .trim();

                if (reason.length() < 5) {
                    reasonInput.setError("Alasan minimal 5 karakter");
                    reasonInput.requestFocus();
                    return;
                }

                dialog.dismiss();
                confirmCancelOrder(order, reason);
            });
        });

        dialog.show();
    }

    private void confirmCancelOrder(DriverOrder order, String reason) {
        new AlertDialog.Builder(this)
                .setTitle("Konfirmasi pembatalan")
                .setMessage("Order akan dilepas dan ditawarkan kepada driver lain.\n\nAlasan: " + reason)
                .setNegativeButton("Tidak", null)
                .setPositiveButton("Ya, Batalkan", (dialog, which) ->
                        presenter.cancelOrder(
                                order.id,
                                clean(order.source),
                                clean(order.status),
                                reason
                        ))
                .show();
    }

    private Button dangerOutlineButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(Color.parseColor("#DC2626"));
        button.setBackground(roundStroke(
                "#FFF7F7", "#EF4444", dp(15), 1));
        return button;
    }

    private String offerKey(DriverOrder order) {
        String cycle = "";
        if (order != null && order.raw != null) {
            cycle = firstNonEmpty(
                    order.raw.optString("offer_cycle_key", ""),
                    order.raw.optString("offer_action_token", ""),
                    order.raw.optString("offer_expired_at", "")
            );
        }
        return clean(order == null ? "" : order.source) + ":"
                + clean(order == null ? "" : order.id) + ":" + clean(cycle);
    }

    private void syncOfferDeadline(DriverOrder order) {
        if (order == null || order.remainingSeconds < 0) return;

        String key = offerKey(order);
        long now = SystemClock.elapsedRealtime();
        long candidate = now + order.remainingSeconds * 1000L;
        Long current = offerDeadlines.get(key);

        if (current == null) {
            offerDeadlines.put(key, candidate);
            expiredRefreshRequested.remove(key);
            return;
        }

        long currentRemaining = current - now;
        long candidateRemaining = candidate - now;

        /*
         * Backend adalah sumber waktu tawaran. Ketika order dengan ID yang sama
         * di-redispatch, offer_expired_at dan token siklus dibuat ulang +15 detik. Versi lama
         * hanya mengizinkan deadline memendek sehingga order yang sudah pernah
         * habis tetap terkunci pada "Tawaran berakhir".
         *
         * Reset deadline bila:
         * 1. deadline lokal sudah habis tetapi server memberi waktu baru; atau
         * 2. deadline server lebih panjang secara nyata (siklus redispatch baru).
         * Selisih kecil tetap diabaikan agar polling biasa tidak menambah waktu.
         */
        boolean revivedByServer = currentRemaining <= 0L
                && candidateRemaining > 0L;
        boolean newerOfferWindow = candidate > current
                + Math.max(SERVER_DRIFT_TOLERANCE_MS, 3000L);

        if (revivedByServer || newerOfferWindow) {
            offerDeadlines.put(key, candidate);
            expiredRefreshRequested.remove(key);
            return;
        }

        // Jika server memperpendek waktu, ikuti deadline server.
        if (candidate < current - SERVER_DRIFT_TOLERANCE_MS) {
            offerDeadlines.put(key, candidate);
        }
    }

    private long remainingMillis(String key) {
        Long deadline = offerDeadlines.get(key);
        return deadline == null
                ? 0L
                : Math.max(0L, deadline - SystemClock.elapsedRealtime());
    }

    private void updateAllCountdowns() {
        if (countdownViews.isEmpty()) return;
        Set<String> keys = new HashSet<>(countdownViews.keySet());

        for (String key : keys) {
            TextView view = countdownViews.get(key);
            if (view == null) continue;

            long remainingMs = remainingMillis(key);
            int seconds = remainingMs <= 0L
                    ? 0
                    : (int) Math.ceil(remainingMs / 1000.0);

            renderCountdown(view, seconds);
            Button button = offerButtons.get(key);

            if (seconds <= 0) {
                if (button != null) {
                    button.setEnabled(false);
                    button.setText("Tawaran berakhir");
                }
                if (expiredRefreshRequested.add(key) && presenter != null) {
                    handler.postDelayed(() -> presenter.load(false), 350L);
                }
            } else {
                if (button != null) {
                    button.setEnabled(true);
                    button.setText("Ambil Order");
                }
                maybeVibrateCountdown(key, seconds);
            }
        }
    }

    private void renderCountdown(TextView view, int seconds) {
        int borderColor = countdownColor(seconds);
        int fillColor = mixWithWhite(borderColor, 0.90f);
        view.setText(seconds > 0 ? "⏱ " + seconds + " detik" : "Waktu habis");
        view.setTextColor(borderColor);
        view.setBackground(roundStrokeColor(
                fillColor, borderColor, dp(999), seconds <= 6 ? 2 : 1));

        if (seconds > 0 && seconds <= 6) {
            view.animate().cancel();
            view.setScaleX(1.08f);
            view.setScaleY(1.08f);
            view.animate().scaleX(1f).scaleY(1f).setDuration(180L).start();
        } else {
            view.setScaleX(1f);
            view.setScaleY(1f);
        }
    }

    private int countdownColor(int seconds) {
        if (seconds <= 0) return Color.parseColor("#991B1B");
        float normalized = Math.max(0f, Math.min(1f, (seconds - 1f) / 14f));
        float hue = 120f * normalized;
        return Color.HSVToColor(new float[]{hue, 0.88f, 0.82f});
    }

    private int mixWithWhite(int color, float whiteRatio) {
        float ratio = Math.max(0f, Math.min(1f, whiteRatio));
        int red = Math.round(Color.red(color) * (1f - ratio) + 255f * ratio);
        int green = Math.round(Color.green(color) * (1f - ratio) + 255f * ratio);
        int blue = Math.round(Color.blue(color) * (1f - ratio) + 255f * ratio);
        return Color.rgb(red, green, blue);
    }

    private GradientDrawable roundStrokeColor(
            int fillColor, int strokeColor, int radius, int width) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(width), strokeColor);
        return drawable;
    }

    private void maybeVibrateCountdown(String key, int seconds) {
        if (seconds < 1 || seconds > 9) return;
        Integer last = lastVibratedSecond.get(key);
        if (last != null && last == seconds) return;
        lastVibratedSecond.put(key, seconds);

        try {
            if (vibrator == null || !vibrator.hasVibrator()) return;
            long duration = seconds <= 2 ? 120L : 70L;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(
                        duration, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(duration);
            }
        } catch (SecurityException ignored) {
        } catch (Exception ignored) {}
    }

    private boolean ensureLocationReady() {
        if (!hasLocationPermission()) {
            pendingOnlineAfterGps = true;
            if (Build.VERSION.SDK_INT >= 23) {
                requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                }, REQ_LOCATION);
            }
            showMessage("Izinkan lokasi agar driver dapat online.");
            return false;
        }

        if (!isLocationProviderEnabled()) {
            pendingOnlineAfterGps = true;
            showGpsEnableDialog(true);
            return false;
        }
        pendingOnlineAfterGps = false;
        return true;
    }

    private boolean isLocationProviderEnabled() {
        try {
            LocationManager manager =
                    (LocationManager) getSystemService(LOCATION_SERVICE);
            return manager != null
                    && (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
        } catch (Exception ignored) {
            return false;
        }
    }

    private void showGpsEnableDialog(boolean continueOnlineAfterReturn) {
        if (isFinishing()) return;
        pendingOnlineAfterGps = continueOnlineAfterReturn;
        new AlertDialog.Builder(this)
                .setTitle("Aktifkan lokasi")
                .setMessage(continueOnlineAfterReturn
                        ? "GPS/lokasi wajib aktif sebelum driver online. Aktifkan GPS, lalu kembali ke Transiva. Driver akan melanjutkan ONLINE otomatis."
                        : "Aktifkan GPS/lokasi agar Transiva dapat menerima posisi driver. Setelah aktif, tekan Kembali untuk kembali ke aplikasi.")
                .setPositiveButton("Aktifkan GPS", (dialog, which) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                    } catch (Exception error) {
                        startActivity(new Intent(Settings.ACTION_SETTINGS));
                    }
                })
                .setNegativeButton("Nanti", (dialog, which) -> {
                    if (continueOnlineAfterReturn) pendingOnlineAfterGps = false;
                })
                .show();
    }

    @Override public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION) {
            if (hasLocationPermission()) {
                if (!isLocationProviderEnabled()) {
                    showGpsEnableDialog(pendingOnlineAfterGps);
                } else if (pendingOnlineAfterGps) {
                    pendingOnlineAfterGps = false;
                    setSwitch(true);
                    if (presenter != null) {
                        presenter.setOnline(true, normalizeDriverType(session.getDriverType()));
                    }
                    showMessage("Izin lokasi diberikan. Driver sedang diaktifkan ONLINE.");
                } else {
                    showMessage("Izin lokasi diberikan.");
                }
            } else {
                setSwitch(false);
                showMessage("Driver tidak dapat online tanpa izin lokasi.");
            }
        }
    }

    private boolean hasLocationPermission() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void setSwitch(boolean checked) {
        suppressSwitch = true;
        onlineSwitch.setChecked(checked);
        suppressSwitch = false;
    }

    private String normalizeDriverType(String value) {
        String clean = clean(value).toLowerCase(Locale.US);
        return clean.equals("car") || clean.equals("mobil") ? "car" : "bike";
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(15), dp(14), dp(15), dp(14));
        card.setBackground(roundStroke(
                "#FFFFFF", "#D7E6F8", dp(21), 1));
        return card;
    }

    private TextView section(String value) {
        TextView text = text(value, 20, "#0B3A78", true);
        text.setPadding(0, dp(17), 0, dp(5));
        return text;
    }

    private TextView emptyCard(String value) {
        TextView text = text(value, 14, "#334155", false);
        text.setPadding(dp(15), dp(18), dp(15), dp(18));
        text.setBackground(roundStroke(
                "#FFFFFF", "#D7E6F8", dp(21), 1));
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(7), 0, dp(12));
        text.setLayoutParams(lp);
        return text;
    }

    private TextView text(
            String value, int sp, String color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sp);
        text.setTextColor(Color.parseColor(color));
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    private Button primaryButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(round("#0B7CFF", dp(14)));
        return button;
    }

    private Button whiteButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextColor(Color.parseColor("#0B7CFF"));
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(round("#FFFFFF", dp(13)));
        return button;
    }

    private GradientDrawable round(String fill, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.parseColor(fill));
        shape.setCornerRadius(radius);
        return shape;
    }

    private GradientDrawable roundStroke(
            String fill, String stroke, int radius, int width) {
        GradientDrawable shape = round(fill, radius);
        shape.setStroke(dp(width), Color.parseColor(stroke));
        return shape;
    }

    private GradientDrawable gradient(
            String start, String end, int radius) {
        GradientDrawable shape = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{
                        Color.parseColor(start),
                        Color.parseColor(end)
                }
        );
        shape.setCornerRadius(radius);
        return shape;
    }

    private void add(
            LinearLayout parent,
            View child,
            int left,
            int top,
            int right,
            int bottom
    ) {
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(left, top, right, bottom);
        parent.addView(child, lp);
    }

    private int dp(int value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density);
    }

    private String formatMinutes(int minutes) {
        if (minutes < 60) return minutes + " mnt";
        return (minutes / 60) + "j " + (minutes % 60) + "m";
    }

    private String rupiah(long value) {
        return NumberFormat.getCurrencyInstance(
                new Locale("id", "ID"))
                .format(value)
                .replace(",00", "");
    }

    private String first(String... values) {
        if (values == null) return "";
        for (String value : values) {
            String clean = clean(value);
            if (!clean.isEmpty()
                    && !"null".equalsIgnoreCase(clean)
                    && !"undefined".equalsIgnoreCase(clean)) {
                return clean;
            }
        }
        return "";
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
