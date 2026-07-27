package com.transiva.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import com.transiva.app.driver.domain.DriverDashboardState;
import com.transiva.app.driver.domain.DriverOrder;
import com.transiva.app.driver.presentation.DriverDashboardContract;
import com.transiva.app.driver.presentation.DriverDashboardPresenter;
import com.transiva.app.driver.ui.DriverBottomNavigation;
import com.transiva.app.driver.ui.DriverPageTransition;

import java.text.NumberFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class DriverDashboardActivity extends Activity
        implements DriverDashboardContract.View {

    private static final int REQ_LOCATION = 8702;
    private static final long REFRESH_MS = 5000L;
    private static final long COUNTDOWN_TICK_MS = 250L;
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

    private final Runnable refreshRunnable = new Runnable() {
        @Override public void run() {
            if (presenter != null) presenter.load(false);
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    private final Runnable countdownRunnable = new Runnable() {
        @Override public void run() {
            updateAllCountdowns();
            handler.postDelayed(this, COUNTDOWN_TICK_MS);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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
    }

    @Override protected void onResume() {
        super.onResume();
        DriverAppSettings.apply(this);
        if (!validSession()) return;
        handler.removeCallbacks(refreshRunnable);
        handler.removeCallbacks(countdownRunnable);
        handler.postDelayed(refreshRunnable, REFRESH_MS);
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

        buildReadiness();
        buildWalletAndPerformance();

        orderSections = new LinearLayout(this);
        orderSections.setOrientation(LinearLayout.VERTICAL);
        content.addView(orderSections);

        buildOrderSections();

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

    private void buildReadiness() {
        LinearLayout card = card();
        card.addView(text("Status Driver", 18, "#0B3A78", true));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);

        onlineLabel = text("OFFLINE", 15, "#EF4444", true);
        row.addView(onlineLabel, new LinearLayout.LayoutParams(0, -2, 1));

        onlineSwitch = new Switch(this);
        onlineSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (suppressSwitch) return;
            if (checked && !ensureLocationReady()) {
                setSwitch(false);
                return;
            }
            presenter.setOnline(
                    checked,
                    normalizeDriverType(session.getDriverType())
            );
        });
        row.addView(onlineSwitch);
        add(card, row, 0, dp(10), 0, 0);

        readinessText = text(
                "Izin lokasi dan GPS akan diperiksa sebelum online.",
                12,
                "#64748B",
                false
        );
        add(card, readinessText, 0, dp(8), 0, 0);

        add(homeSections, card, 0, dp(16), 0, 0);
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

        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);

        earningText = stat(stats, "Rp 0", "Hari ini");
        tripText = stat(stats, "0", "Trip");
        ratingText = stat(stats, "0.0", "Rating");

        add(homeSections, stats, 0, dp(10), 0, 0);
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

    private void buildOrderSections() {
        orderSections.addView(section("Order Aktif"));
        activeBox = new LinearLayout(this);
        activeBox.setOrientation(LinearLayout.VERTICAL);
        orderSections.addView(activeBox);

        orderSections.addView(section("Tawaran Order"));
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
        tripText.setText(String.valueOf(state.todayTrips));
        ratingText.setText(String.format(Locale.US, "%.1f", state.rating));

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
                            ? "Online • lokasi driver dijaga oleh foreground service."
                            : "Online • GPS/lokasi mati. Login tetap aktif, tetapi pengiriman lokasi dijeda sampai GPS dinyalakan.")
                        : "Offline • lokasi tidak dikirim dan order tidak ditawarkan."
        );

        lastUpdateText.setText("Baru diperbarui");

        activeBox.removeAllViews();
        if (state.activeOrder == null) {
            session.remove("current_order_id");
            activeBox.addView(emptyCard("Belum ada order aktif."));
        } else {
            session.put("current_order_id", state.activeOrder.id);
            activeBox.addView(orderCard(state.activeOrder, true));
        }

        offerBox.removeAllViews();
        countdownViews.clear();
        offerButtons.clear();
        if (!state.online) {
            offerBox.addView(emptyCard(
                    "Driver OFFLINE.\nAktifkan ONLINE untuk menerima order."));
        } else if (state.offers.isEmpty()) {
            offerBox.addView(emptyCard("Belum ada tawaran order."));
        } else {
            Set<String> activeOfferKeys = new HashSet<>();
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
        }
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

    private View orderCard(DriverOrder order, boolean active) {
        LinearLayout card = card();
        card.addView(text(
                (active ? "Order Aktif" : "Tawaran") + " #" + order.id,
                17, "#0B3A78", true));
        add(card, text(order.serviceName, 14, "#0B7CFF", true),
                0, dp(5), 0, 0);
        add(card, text("Pickup:\n" + order.pickupAddress,
                13, "#334155", false), 0, dp(8), 0, 0);
        add(card, text("Tujuan:\n" + order.destinationAddress,
                13, "#334155", false), 0, dp(6), 0, 0);

        String meta = "Pendapatan " + rupiah(order.driverEarning);
        if (!clean(order.pickupDistanceText).isEmpty()) {
            meta += " • " + order.pickupDistanceText;
        }
        add(card, text(meta, 13, "#0F172A", true), 0, dp(8), 0, 0);

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

        Button action = primaryButton(active ? "Lanjutkan Trip" : "Ambil Order");

        if (active) {
            action.setOnClickListener(v -> openTrip(order));
            add(card, action, 0, dp(12), 0, 0);

            if (canDriverCancel(order.status)) {
                Button cancel = dangerOutlineButton("Batalkan Order");
                cancel.setOnClickListener(v -> showCancelOrderDialog(order));
                add(card, cancel, 0, dp(9), 0, 0);
            }
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
                presenter.acceptOrder(order.id);
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
        String value = normalizeOrderStatus(status);

        return value.equals("taken")
                || value.equals("driver_accepted")
                || value.equals("accepted")
                || value.equals("arrived_pickup");
    }

    private void showCancelOrderDialog(DriverOrder order) {
        if (order == null || !canDriverCancel(order.status)) {
            showMessage(
                    "Order tidak dapat dibatalkan pada status "
                            + normalizeOrderStatus(order == null ? "" : order.status)
            );
            return;
        }

        final String[] reasons = new String[]{
                "Kendaraan bermasalah",
                "Kondisi darurat",
                "Tidak dapat menemukan lokasi pickup",
                "Customer tidak dapat dihubungi",
                "Order tidak sesuai",
                "Alasan lainnya"
        };

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
        return clean(order.source) + ":" + clean(order.id);
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

        if (Math.abs(candidate - current) > SERVER_DRIFT_TOLERANCE_MS) {
            offerDeadlines.put(key, candidate);
            if (candidate > now) expiredRefreshRequested.remove(key);
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
        float normalized = Math.max(0f, Math.min(1f, (seconds - 1f) / 19f));
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
        if (seconds < 1 || seconds > 6) return;
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
