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
abstract class DriverDashboardActivityLayer1 extends Activity
        implements DriverDashboardContract.View {

    protected static final int REQ_LOCATION = 8702;
    protected static final int REQ_BACKGROUND_LOCATION = 8703;
    protected static final long SERVER_DRIFT_TOLERANCE_MS = 2500L;

    protected final Handler handler = new Handler(Looper.getMainLooper());
    protected Vibrator vibrator;

    protected SessionManager session;
    protected DriverDashboardPresenter presenter;
    protected DriverDashboardState currentState;

    protected FrameLayout page;
    protected View bottomNavigationView;
    protected LinearLayout shell;
    protected LinearLayout content;
    protected LinearLayout activeBox;
    protected LinearLayout offerBox;
    protected LinearLayout homeSections;
    protected LinearLayout orderSections;

    protected TextView nameText;
    protected TextView verificationText;
    protected TextView balanceText;
    protected TextView earningText;
    protected TextView tripText;
    protected TextView ratingText;
    protected TextView onlineLabel;
    protected TextView readinessText;
    protected TextView lastUpdateText;
    protected TextView onlineMinutesText;
    protected TextView distanceText;
    protected TextView queueText;
    protected TextView queueDetailText;
    protected TextView assistantTitleText;
    protected TextView assistantMessageText;
    protected TextView hotspotText;
    protected TextView growthScoreText;
    protected TextView growthGoalText;
    protected TextView growthRateText;
    protected TextView destinationModeText;
    protected TextView clusterCurrentText;
    protected TextView clusterListText;
    protected LinearLayout clusterGrid;
    protected TextView priorityOrderTitle;
    protected Button sosButton;
    protected final Set<String> seenOfferKeys = new HashSet<>();
    protected boolean firstOfferSnapshot = true;

    protected Switch onlineSwitch;
    protected boolean pendingOnlineAfterGps = false;
    protected boolean pendingBackgroundLocationSettings = false;
    protected boolean requestGpsAfterLogin = false;
    protected boolean gpsPromptShown = false;
    protected ProgressBar loading;
    protected boolean suppressSwitch;

    protected DashboardOfferCountdownController offerCountdownController;
    protected DashboardRefreshController refreshController;
    protected DashboardCancellationController cancellationController;

    protected boolean realtimeReceiverRegistered = false;
    protected final BroadcastReceiver realtimeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (presenter == null) return;
            presenter.load(false);
            if (refreshController != null) refreshController.scheduleSoon(350L);
        }
    };

    protected boolean canDriverCancel(String status) {
        return DriverOrderCancellationPolicy.canCancel(status);
    }

    protected Button dangerOutlineButton(String label) {
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

    protected boolean ensureLocationReady() {
        if (!hasLocationPermission()) {
            pendingOnlineAfterGps = true;
            showLocationDisclosureAndRequestForeground();
            return false;
        }

        if (!hasBackgroundLocationPermission()) {
            pendingOnlineAfterGps = true;
            showBackgroundLocationDisclosureAndRequest();
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

    /**
     * Google Play prominent disclosure. It is intentionally shown in the normal
     * ONLINE flow immediately before Android's location permission prompt.
     */
    protected void showLocationDisclosureAndRequestForeground() {
        PremiumDialogs.builder(this)
                .setTitle("Lokasi untuk Driver ONLINE")
                .setMessage("Transiva Driver mengumpulkan data lokasi presisi untuk menjalankan fitur Driver ONLINE dan perjalanan aktif. Lokasi digunakan untuk menentukan posisi driver, menghubungkan order di sekitar, menampilkan posisi perjalanan kepada customer, dan navigasi. Saat Anda memilih ONLINE atau memiliki perjalanan aktif, lokasi dapat tetap dikumpulkan di latar belakang, termasuk ketika aplikasi ditutup atau tidak sedang digunakan. Lokasi tidak digunakan untuk iklan dan layanan lokasi berhenti ketika Driver OFFLINE dan tidak memiliki perjalanan aktif.")
                .setNegativeButton("Tetap Offline", (d, w) -> {
                    pendingOnlineAfterGps = false;
                    setSwitch(false);
                })
                .setPositiveButton("Lanjut", (d, w) -> {
                    if (Build.VERSION.SDK_INT >= 23) {
                        requestPermissions(new String[]{
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                        }, REQ_LOCATION);
                    }
                })
                .show();
    }

    protected boolean hasBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true;
        return checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    protected void showBackgroundLocationDisclosureAndRequest() {
        PremiumDialogs.builder(this)
                .setTitle("Izinkan lokasi di latar belakang")
                .setMessage("Transiva Driver memerlukan lokasi di latar belakang agar fitur Driver ONLINE dan perjalanan aktif tetap berjalan ketika aplikasi tidak sedang tampil di layar. Lokasi digunakan untuk posisi driver, pencocokan order di sekitar, progres perjalanan kepada customer, dan navigasi. Akses ini hanya berjalan saat Anda ONLINE atau memiliki perjalanan aktif, berhenti saat OFFLINE tanpa perjalanan aktif, dan tidak digunakan untuk iklan.")
                .setNegativeButton("Tetap Offline", (d, w) -> {
                    pendingOnlineAfterGps = false;
                    pendingBackgroundLocationSettings = false;
                    setSwitch(false);
                })
                .setPositiveButton("Lanjut", (d, w) -> requestBackgroundLocationPermission())
                .show();
    }

    protected void requestBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            continueOnlineAfterPermissions();
            return;
        }
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQ_BACKGROUND_LOCATION);
            return;
        }

        // Android 11+: "Allow all the time" is granted from the app's Location
        // permission settings. We send the driver there only after the disclosure.
        pendingBackgroundLocationSettings = true;
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            showMessage("Buka Izin > Lokasi lalu pilih Izinkan sepanjang waktu, kemudian kembali ke Transiva.");
        } catch (Exception e) {
            pendingBackgroundLocationSettings = false;
            showMessage("Tidak dapat membuka pengaturan izin lokasi.");
        }
    }

    protected void continueOnlineAfterPermissions() {
        if (!hasLocationPermission() || !hasBackgroundLocationPermission()) {
            setSwitch(false);
            return;
        }
        if (!isLocationProviderEnabled()) {
            showGpsEnableDialog(true);
            return;
        }
        if (pendingOnlineAfterGps) {
            pendingOnlineAfterGps = false;
            pendingBackgroundLocationSettings = false;
            setSwitch(true);
            if (presenter != null) {
                presenter.setOnline(true, normalizeDriverType(session.getDriverType()));
            }
            showMessage("Lokasi siap. Driver sedang diaktifkan ONLINE.");
        }
    }

    protected boolean isLocationProviderEnabled() {
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

    protected void showGpsEnableDialog(boolean continueOnlineAfterReturn) {
        if (isFinishing()) return;
        pendingOnlineAfterGps = continueOnlineAfterReturn;
        PremiumDialogs.builder(this)
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
                if (!hasBackgroundLocationPermission()) {
                    showBackgroundLocationDisclosureAndRequest();
                } else {
                    continueOnlineAfterPermissions();
                }
            } else {
                pendingOnlineAfterGps = false;
                setSwitch(false);
                showMessage("Driver tidak dapat online tanpa izin lokasi.");
            }
            return;
        }
        if (requestCode == REQ_BACKGROUND_LOCATION) {
            if (hasBackgroundLocationPermission()) {
                continueOnlineAfterPermissions();
            } else {
                pendingOnlineAfterGps = false;
                setSwitch(false);
                showMessage("Lokasi latar belakang belum diberikan. Driver tetap OFFLINE.");
            }
        }
    }

    protected boolean hasLocationPermission() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    protected void setSwitch(boolean checked) {
        suppressSwitch = true;
        onlineSwitch.setChecked(checked);
        suppressSwitch = false;
    }

    protected String normalizeDriverType(String value) {
        String clean = clean(value).toLowerCase(Locale.US);
        return clean.equals("car") || clean.equals("mobil") ? "car" : "bike";
    }

    protected LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(15), dp(14), dp(15), dp(14));
        card.setBackground(roundStroke(
                "#FFFFFF", "#D7E6F8", dp(21), 1));
        return card;
    }

    protected TextView section(String value) {
        TextView text = text(value, 20, "#0B3A78", true);
        text.setPadding(0, dp(17), 0, dp(5));
        return text;
    }

    protected TextView emptyCard(String value) {
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

    protected TextView text(
            String value, int sp, String color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sp);
        text.setTextColor(Color.parseColor(color));
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    protected Button primaryButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(round("#0B7CFF", dp(14)));
        return button;
    }

    protected Button whiteButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextColor(Color.parseColor("#0B7CFF"));
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(round("#FFFFFF", dp(13)));
        return button;
    }

    protected GradientDrawable round(String fill, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.parseColor(fill));
        shape.setCornerRadius(radius);
        return shape;
    }

    protected GradientDrawable roundStroke(
            String fill, String stroke, int radius, int width) {
        GradientDrawable shape = round(fill, radius);
        shape.setStroke(dp(width), Color.parseColor(stroke));
        return shape;
    }

    protected int mixWithWhite(int color, float whiteRatio) {
        float ratio = Math.max(0f, Math.min(1f, whiteRatio));
        int red = Math.round(Color.red(color) * (1f - ratio) + 255f * ratio);
        int green = Math.round(Color.green(color) * (1f - ratio) + 255f * ratio);
        int blue = Math.round(Color.blue(color) * (1f - ratio) + 255f * ratio);
        return Color.rgb(red, green, blue);
    }

    protected GradientDrawable roundStrokeColor(
            int fillColor, int strokeColor, int radius, int width) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(width), strokeColor);
        return drawable;
    }

    protected GradientDrawable gradient(
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

    protected void add(
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

    protected int dp(int value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density);
    }

    protected String formatMinutes(int minutes) {
        if (minutes < 60) return minutes + " mnt";
        return (minutes / 60) + "j " + (minutes % 60) + "m";
    }

    protected String rupiah(long value) {
        return NumberFormat.getCurrencyInstance(
                new Locale("id", "ID"))
                .format(value)
                .replace(",00", "");
    }

    protected String first(String... values) {
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

    protected String clean(String value) {
        return value == null ? "" : value.trim();
    }
    // Cross-layer contracts keep the split type-safe without duplicating state.
    protected abstract void onCreate(Bundle savedInstanceState);
    protected abstract void showOpportunityPromptIfNeeded(Intent intent);
    protected abstract void onStart();
    protected abstract void onStop();
    protected abstract void onResume();
    protected abstract void onPause();
    protected abstract void onDestroy();
    protected abstract boolean validSession();
    protected abstract View buildScreen();
    protected abstract void buildHeader();
    protected abstract void buildStatusAndEmergency();
    protected abstract void buildDriverLocationMenu();
    protected abstract void buildWalletAndPerformance();
    protected abstract TextView stat(LinearLayout parent, String value, String label);
    protected abstract void buildDriverGrowth();
    protected abstract void showGrowthSettings();
    protected abstract void saveGrowthSettings(long goal, String mode, String label);
    protected abstract void buildSmartAssistant();
    protected abstract void renderClusterGrid(DriverDashboardState state);
    protected abstract int clusterAccent(int drivers);
    protected abstract void sendEmergency();
    protected abstract void buildOrderSections();
    protected abstract void showLoading(boolean visible);
    protected abstract void showDashboard(DriverDashboardState state);
    protected abstract void playIncomingOrderEffect();
    protected abstract void showActionLoading(String action, boolean visible);
    protected abstract void showMessage(String message);
    protected abstract void showSessionExpired();
    protected abstract void openTrip(DriverOrder order);
    protected abstract JSONObject foodPayload(JSONObject raw);
    protected abstract boolean isFoodOrder(DriverOrder order);
    protected abstract String merchantStatusLabel(String raw);
    protected abstract String firstNonEmpty(String... values);
    protected abstract String customerNote(JSONObject raw);
    protected abstract String buildAssistantMessage(DriverDashboardState state);
    protected abstract int effectiveHotspotScore(DriverDashboardState state);
    protected abstract String effectiveHotspotLevel(DriverDashboardState state, int score);
    protected abstract String aiServiceLabel(DriverOrder order);
    protected abstract String joinServices(java.util.LinkedHashSet<String> services);
    protected abstract String serviceIcon(String service);
    protected abstract String serviceSubtitle(String service);
    protected abstract View orderCard(DriverOrder order, boolean active);

}
