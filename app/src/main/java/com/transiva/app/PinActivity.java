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
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import java.util.concurrent.Executor;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class PinActivity extends FragmentActivity {

    private static final String TAG = "TRANSIVA_PIN";
    private static final String BASE_URL = "https://transiva.my.id/server/";
    private static final String STATUS_URL = BASE_URL + "pin_status.php";
    private static final String SET_URL = BASE_URL + "pin_set.php";
    private static final String VERIFY_URL = BASE_URL + "pin_verify.php";
    private static final int TIMEOUT_MS = 25000;
    private static final int PIN_LENGTH = 6;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private SessionManager session;
    private LinearLayout dotsContainer;
    private TextView titleText;
    private TextView subtitleText;
    private TextView messageText;
    private TextView stepText;
    private TextView actionHintText;
    private ProgressBar progressBar;
    private LinearLayout keypadContainer;
    private LinearLayout pinContentRoot;
    private TextView biometricButton;
    private boolean biometricPromptShown = false;

    private boolean loading;
    private boolean setupMode;
    private boolean confirmingPin;
    private String firstPin = "";
    private String currentPin = "";
    private String role = "driver";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            getWindow().setStatusBarColor(Color.parseColor("#081423"));
            getWindow().setNavigationBarColor(Color.parseColor("#081423"));
        } catch (Exception ignored) {}

        session = new SessionManager(this);
        if (!session.isLoggedIn() || safe(session.getToken()).trim().isEmpty()) {
            openLogin();
            return;
        }

        role = normalizeRole(session.getRole());
        if (!"driver".equals(role)) {
            session.forceLogout("driver_app_role_rejected");
            openLogin();
            return;
        }
        role = "driver";

        setContentView(buildScreen());
        checkPinStatus();
    }

    @Override
    public void onBackPressed() {
        // PIN gate tidak boleh dilewati dengan tombol Back.
        // Pengguna tetap bisa keluar akun melalui tombol "Keluar akun".
    }

    private View buildScreen() {
        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(Color.parseColor("#F7FBFF"));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        page.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout root = new LinearLayout(this);
        pinContentRoot = root;
        root.setVisibility(View.INVISIBLE);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(30));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        ImageView logo = new ImageView(this);
        int logoRes = findDrawable("transiva_logo");
        if (logoRes == 0) logoRes = getApplicationInfo().icon;
        logo.setImageResource(logoRes);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(dp(170), dp(66));
        logoLp.setMargins(0, dp(2), 0, dp(10));
        root.addView(logo, logoLp);

        LinearLayout securityBadge = new LinearLayout(this);
        securityBadge.setOrientation(LinearLayout.HORIZONTAL);
        securityBadge.setGravity(Gravity.CENTER);
        securityBadge.setPadding(dp(12), dp(7), dp(12), dp(7));
        securityBadge.setBackground(round("#EAF4FF", dp(99)));

        TextView shield = text("●", 10, "#1677FF", true);
        TextView secureText = text("  Keamanan akun Transiva", 12, "#1677FF", true);
        securityBadge.addView(shield);
        securityBadge.addView(secureText);

        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(-2, -2);
        badgeLp.setMargins(0, 0, 0, dp(16));
        root.addView(securityBadge, badgeLp);

        titleText = text("Memeriksa PIN", 26, "#0B3675", true);
        titleText.setGravity(Gravity.CENTER);
        root.addView(titleText, new LinearLayout.LayoutParams(-1, -2));

        subtitleText = text("Menyiapkan keamanan akun Anda...", 14, "#68758A", false);
        subtitleText.setGravity(Gravity.CENTER);
        subtitleText.setPadding(dp(12), dp(8), dp(12), dp(20));
        root.addView(subtitleText, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(20), dp(22), dp(20), dp(20));
        card.setBackground(roundStroke("#FFFFFF", "#D9E2EE", dp(26), 1));
        card.setElevation(dp(5));

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.setMargins(0, 0, 0, dp(14));
        root.addView(card, cardLp);

        stepText = text("PIN 6 digit", 12, "#64748B", true);
        stepText.setGravity(Gravity.CENTER);
        card.addView(stepText, new LinearLayout.LayoutParams(-1, -2));

        dotsContainer = new LinearLayout(this);
        dotsContainer.setOrientation(LinearLayout.HORIZONTAL);
        dotsContainer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dotsLp = new LinearLayout.LayoutParams(-1, dp(58));
        dotsLp.setMargins(0, dp(7), 0, dp(7));
        card.addView(dotsContainer, dotsLp);
        renderDots();

        messageText = text("", 12, "#B91C1C", true);
        messageText.setGravity(Gravity.CENTER);
        messageText.setPadding(dp(12), dp(9), dp(12), dp(9));
        messageText.setVisibility(View.GONE);
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(-1, -2);
        msgLp.setMargins(0, 0, 0, dp(10));
        card.addView(messageText, msgLp);

        actionHintText = text("Gunakan tombol angka di bawah", 12, "#8A96A8", false);
        actionHintText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.setMargins(0, dp(1), 0, dp(13));
        card.addView(actionHintText, hintLp);

        biometricButton = text("Gunakan sidik jari", 14, "#1677FF", true);
        biometricButton.setGravity(Gravity.CENTER);
        biometricButton.setPadding(dp(14), dp(12), dp(14), dp(12));
        biometricButton.setBackground(round("#EAF4FF", dp(16)));
        biometricButton.setVisibility(View.GONE);
        biometricButton.setOnClickListener(v -> showBiometricPrompt());
        LinearLayout.LayoutParams bioLp = new LinearLayout.LayoutParams(-1, -2);
        bioLp.setMargins(0, 0, 0, dp(12));
        card.addView(biometricButton, bioLp);

        keypadContainer = buildKeypad();
        card.addView(keypadContainer, new LinearLayout.LayoutParams(-1, -2));

        progressBar = new ProgressBar(this);
        progressBar.setVisibility(View.VISIBLE);
        FrameLayout.LayoutParams progressLp =
                new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER);
        page.addView(progressBar, progressLp);

        TextView logout = text("Keluar akun", 13, "#64748B", true);
        logout.setGravity(Gravity.CENTER);
        logout.setPadding(dp(16), dp(12), dp(16), dp(12));
        logout.setOnClickListener(v -> logout());
        root.addView(logout, new LinearLayout.LayoutParams(-1, -2));

        TextView footer = text(
                "PIN melindungi akses ke akun Anda pada perangkat ini.",
                11,
                "#8A96A8",
                false
        );
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(dp(18), dp(3), dp(18), 0);
        root.addView(footer, new LinearLayout.LayoutParams(-1, -2));

        setKeypadEnabled(false);
        return page;
    }

    private LinearLayout buildKeypad() {
        LinearLayout keypad = new LinearLayout(this);
        keypad.setOrientation(LinearLayout.VERTICAL);

        addKeyRow(keypad, "1", "2", "3");
        addKeyRow(keypad, "4", "5", "6");
        addKeyRow(keypad, "7", "8", "9");
        addKeyRow(keypad, "", "0", "⌫");

        return keypad;
    }

    private void addKeyRow(LinearLayout parent, String a, String b, String c) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        addKey(row, a);
        addKey(row, b);
        addKey(row, c);

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, dp(62));
        rowLp.setMargins(0, dp(3), 0, dp(3));
        parent.addView(row, rowLp);
    }

    private void addKey(LinearLayout row, String value) {
        if (value.isEmpty()) {
            View spacer = new View(this);
            row.addView(spacer, new LinearLayout.LayoutParams(0, -1, 1f));
            return;
        }

        TextView key = text(value, "⌫".equals(value) ? 24 : 22, "#123D7C", true);
        key.setGravity(Gravity.CENTER);
        key.setBackground(round("#F2F7FF", dp(18)));
        key.setClickable(true);
        key.setFocusable(true);

        LinearLayout.LayoutParams keyLp = new LinearLayout.LayoutParams(0, -1, 1f);
        keyLp.setMargins(dp(5), dp(2), dp(5), dp(2));
        row.addView(key, keyLp);

        key.setOnClickListener(v -> {
            if (loading) return;
            if ("⌫".equals(value)) {
                if (!currentPin.isEmpty()) {
                    currentPin = currentPin.substring(0, currentPin.length() - 1);
                    clearMessage();
                    renderDots();
                }
                return;
            }

            if (currentPin.length() >= PIN_LENGTH) return;
            currentPin += value;
            clearMessage();
            renderDots();

            if (currentPin.length() == PIN_LENGTH) {
                mainHandler.postDelayed(this::onPinComplete, 120);
            }
        });
    }

    private void onPinComplete() {
        if (loading || currentPin.length() != PIN_LENGTH) return;

        if (!setupMode) {
            verifyPin(currentPin);
            return;
        }

        if (!confirmingPin) {
            firstPin = currentPin;
            currentPin = "";
            confirmingPin = true;
            titleText.setText("Konfirmasi PIN");
            subtitleText.setText("Masukkan kembali 6 digit PIN yang baru Anda buat.");
            stepText.setText("Ulangi PIN baru");
            renderDots();
            return;
        }

        if (!firstPin.equals(currentPin)) {
            currentPin = "";
            firstPin = "";
            confirmingPin = false;
            titleText.setText("Buat PIN Transiva");
            subtitleText.setText("Gunakan 6 angka yang mudah Anda ingat tetapi sulit ditebak.");
            stepText.setText("Buat PIN 6 digit");
            renderDots();
            showMessage("PIN tidak sama. Silakan buat ulang PIN Anda.", false);
            return;
        }

        setPin(currentPin);
    }

    private void checkPinStatus() {
        setLoading(true);

        new Thread(() -> {
            ApiResult result = request(STATUS_URL, null);
            mainHandler.post(() -> {
                if (pinContentRoot != null) {
                    pinContentRoot.setVisibility(View.VISIBLE);
                }
                setLoading(false);

                if (!result.success) {
                    showMessage(result.message, false);
                    actionHintText.setText("Ketuk layar atau buka ulang aplikasi untuk mencoba lagi");
                    return;
                }

                setupMode = !result.data.optBoolean("has_pin", false);
                confirmingPin = false;
                currentPin = "";
                firstPin = "";

                if (setupMode) {
                    titleText.setText("Buat PIN Transiva");
                    subtitleText.setText("Buat PIN 6 digit sebelum melanjutkan ke akun Anda.");
                    stepText.setText("Buat PIN 6 digit");
                } else {
                    titleText.setText("Masukkan PIN");
                    String name = safe(session.getName()).trim();
                    subtitleText.setText(
                            name.isEmpty()
                                    ? "Masukkan PIN 6 digit untuk membuka akun Transiva."
                                    : "Halo " + name + ", masukkan PIN untuk melanjutkan."
                    );
                    stepText.setText("PIN akun");
                }

                actionHintText.setText(setupMode ? "Gunakan tombol angka di bawah" : "Masukkan PIN atau gunakan sidik jari");
                setKeypadEnabled(true);
                renderDots();
                updateBiometricAvailability();
                if (!setupMode && biometricButton != null && biometricButton.getVisibility() == View.VISIBLE && !biometricPromptShown) {
                    biometricPromptShown = true;
                    mainHandler.postDelayed(this::showBiometricPrompt, 250);
                }
            });
        }, "transiva-pin-status").start();
    }

    private void updateBiometricAvailability() {
        if (biometricButton == null) return;
        if (setupMode) { biometricButton.setVisibility(View.GONE); return; }
        BiometricManager bm = BiometricManager.from(this);
        int can = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        biometricButton.setVisibility(can == BiometricManager.BIOMETRIC_SUCCESS ? View.VISIBLE : View.GONE);
    }

    private void showBiometricPrompt() {
        if (setupMode || loading) return;
        BiometricManager bm = BiometricManager.from(this);
        if (bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) != BiometricManager.BIOMETRIC_SUCCESS) return;
        Executor executor = ContextCompat.getMainExecutor(this);
        BiometricPrompt prompt = new BiometricPrompt(this, executor, new BiometricPrompt.AuthenticationCallback() {
            @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                showMessage("Sidik jari terverifikasi. Membuka akun...", true);
                mainHandler.postDelayed(PinActivity.this::openRolePage, 180);
            }
            @Override public void onAuthenticationError(int errorCode, CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON && errorCode != BiometricPrompt.ERROR_CANCELED)
                    showMessage(String.valueOf(errString), false);
            }
            @Override public void onAuthenticationFailed() {
                super.onAuthenticationFailed();
                showMessage("Sidik jari tidak dikenali. Coba lagi atau gunakan PIN.", false);
            }
        });
        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Buka Transiva Driver")
                .setSubtitle("Verifikasi sidik jari untuk melewati PIN")
                .setNegativeButtonText("Gunakan PIN")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build();
        prompt.authenticate(info);
    }

    private void setPin(String pin) {
        JSONObject body = new JSONObject();
        try {
            body.put("pin", pin);
            body.put("confirm_pin", pin);
        } catch (Exception ignored) {}

        setLoading(true);
        new Thread(() -> {
            ApiResult result = request(SET_URL, body);
            mainHandler.post(() -> {
                if (pinContentRoot != null) {
                    pinContentRoot.setVisibility(View.VISIBLE);
                }
                setLoading(false);

                if (!result.success) {
                    currentPin = "";
                    firstPin = "";
                    confirmingPin = false;
                    renderDots();

                    if ("PIN_ALREADY_SET".equals(result.code)) {
                        setupMode = false;
                        titleText.setText("Masukkan PIN");
                        subtitleText.setText("PIN akun sudah tersedia. Masukkan PIN untuk melanjutkan.");
                        stepText.setText("PIN akun");
                    }

                    showMessage(result.message, false);
                    return;
                }

                showMessage("PIN berhasil dibuat. Membuka akun...", true);
                mainHandler.postDelayed(this::openRolePage, 450);
            });
        }, "transiva-pin-set").start();
    }

    private void verifyPin(String pin) {
        JSONObject body = new JSONObject();
        try {
            body.put("pin", pin);
        } catch (Exception ignored) {}

        setLoading(true);
        new Thread(() -> {
            ApiResult result = request(VERIFY_URL, body);
            mainHandler.post(() -> {
                if (pinContentRoot != null) {
                    pinContentRoot.setVisibility(View.VISIBLE);
                }
                setLoading(false);

                if (!result.success) {
                    currentPin = "";
                    renderDots();

                    if ("PIN_NOT_SET".equals(result.code)) {
                        setupMode = true;
                        confirmingPin = false;
                        firstPin = "";
                        titleText.setText("Buat PIN Transiva");
                        subtitleText.setText("Akun ini belum memiliki PIN. Buat PIN 6 digit untuk melanjutkan.");
                        stepText.setText("Buat PIN 6 digit");
                    }

                    showMessage(result.message, false);
                    return;
                }

                showMessage("PIN benar. Membuka akun...", true);
                mainHandler.postDelayed(this::openRolePage, 350);
            });
        }, "transiva-pin-verify").start();
    }

    private ApiResult request(String endpoint, JSONObject payload) {
        HttpURLConnection conn = null;

        try {
            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod(payload == null ? "GET" : "POST");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setDoInput(true);

            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Cache-Control", "no-store");
            conn.setRequestProperty("Authorization", "Bearer " + safe(session.getToken()).trim());
            conn.setRequestProperty("X-App-Scope", "driver");
            conn.setRequestProperty(
                    "X-Device-UUID",
                    DeviceIdentityManager.getInstallationUuid(this)
            );
            conn.setRequestProperty("X-Transiva-Client", "Android-Native");

            if (payload != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

                try (BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8)
                )) {
                    writer.write(payload.toString());
                }
            }

            int status = conn.getResponseCode();
            InputStream stream =
                    status >= 200 && status < 400
                            ? conn.getInputStream()
                            : conn.getErrorStream();

            String raw = readAll(stream);
            JSONObject json;

            try {
                json = raw.trim().isEmpty()
                        ? new JSONObject()
                        : new JSONObject(raw.trim());
            } catch (Exception parseError) {
                return new ApiResult(
                        false,
                        "INVALID_RESPONSE",
                        "Respons server PIN tidak valid.",
                        new JSONObject()
                );
            }

            String code = json.optString("code", "");
            String message = json.optString(
                    "message",
                    status >= 200 && status < 300
                            ? "Berhasil."
                            : "Permintaan PIN gagal."
            );

            // Jangan logout hanya karena HTTP 401/403 generik.
            // Endpoint PIN dapat memakai status tersebut untuk error PIN;
            // logout hanya untuk kode sesi/perangkat yang memang final.
            if (ForceLogoutManager.isForceLogoutCode(code)) {
                mainHandler.post(() ->
                        ForceLogoutManager.execute(
                                PinActivity.this,
                                code.isEmpty() ? "SESSION_REVOKED" : code
                        )
                );
            }

            return new ApiResult(
                    status >= 200 && status < 300 && json.optBoolean("success", false),
                    code,
                    message,
                    json
            );

        } catch (Exception e) {
            return new ApiResult(
                    false,
                    "NETWORK_ERROR",
                    "Tidak dapat terhubung ke server. Periksa koneksi internet Anda.",
                    new JSONObject()
            );
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void renderDots() {
        if (dotsContainer == null) return;

        dotsContainer.removeAllViews();

        for (int i = 0; i < PIN_LENGTH; i++) {
            TextView dot = new TextView(this);
            boolean filled = i < currentPin.length();

            dot.setGravity(Gravity.CENTER);
            dot.setText(filled ? "●" : "");
            dot.setTextSize(20);
            dot.setTextColor(Color.WHITE);
            dot.setBackground(
                    filled
                            ? round("#1677FF", dp(14))
                            : roundStroke("#F7FBFF", "#B9C7D8", dp(14), 1)
            );

            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(dp(38), dp(46));
            lp.setMargins(dp(5), 0, dp(5), 0);
            dotsContainer.addView(dot, lp);
        }
    }

    private void setLoading(boolean value) {
        loading = value;
        if (progressBar != null) {
            progressBar.setVisibility(value ? View.VISIBLE : View.GONE);
        }
        setKeypadEnabled(!value);
    }

    private void setKeypadEnabled(boolean enabled) {
        if (keypadContainer == null) return;
        setChildrenEnabled(keypadContainer, enabled);
        keypadContainer.setAlpha(enabled ? 1f : 0.55f);
    }

    private void setChildrenEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof LinearLayout) {
            LinearLayout group = (LinearLayout) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                setChildrenEnabled(group.getChildAt(i), enabled);
            }
        }
    }

    private void showMessage(String message, boolean success) {
        if (messageText == null) return;
        messageText.setVisibility(View.VISIBLE);
        messageText.setText(safe(message));
        messageText.setTextColor(Color.parseColor(success ? "#166534" : "#B91C1C"));
        messageText.setBackground(
                round(success ? "#DCFCE7" : "#FEE2E2", dp(12))
        );
    }

    private void clearMessage() {
        if (messageText == null) return;
        messageText.setText("");
        messageText.setVisibility(View.GONE);
    }

    private void openRolePage() {
        // PIN hanya membuka kunci lokal; jangan pernah menghapus sesi login yang masih valid.
        if (session == null || !session.isLoggedIn() || safe(session.getToken()).trim().isEmpty()) {
            showMessage("Sesi login tidak tersedia. Silakan login kembali.", false);
            return;
        }
        session.touchSession();
        try {
            TransivaSession.saveUser(this, session.getSessionJson());
        } catch (Exception ignored) {}

        Intent intent = new Intent(this, DriverDashboardActivity.class);
        intent.putExtra("native_role", "driver");
        intent.putExtra("request_gps_after_login", true);
        intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
        );
        startActivity(intent);
        finish();
    }

    private void logout() {
        try {
            session.forceLogout("pin_gate_logout");
        } catch (Exception ignored) {}
        openLogin();
    }

    private void openLogin() {
        Intent intent = new Intent(this, LoginActivity.class);
        intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
        );
        startActivity(intent);
        finish();
    }

    private String normalizeRole(String value) {
        String clean = safe(value).trim().toLowerCase(Locale.US);
        if (clean.equals("driver")
                || clean.equals("kurir")
                || clean.equals("ojek")
                || clean.equals("rider")) {
            return "driver";
        }
        return "";
    }

    private TextView text(String value, int size, String color, boolean bold) {
        TextView out = new TextView(this);
        out.setText(value);
        out.setTextSize(size);
        out.setTextColor(Color.parseColor(color));
        if (bold) out.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return out;
    }

    private GradientDrawable round(String color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.parseColor(color));
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private GradientDrawable roundStroke(
            String fill,
            String stroke,
            int radius,
            int width
    ) {
        GradientDrawable drawable = round(fill, radius);
        drawable.setStroke(dp(width), Color.parseColor(stroke));
        return drawable;
    }

    private int findDrawable(String name) {
        try {
            return getResources().getIdentifier(name, "drawable", getPackageName());
        } catch (Exception ignored) {
            return 0;
        }
    }

    private int dp(int value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density
        );
    }

    private String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8)
        );

        StringBuilder out = new StringBuilder();
        String line;

        while ((line = reader.readLine()) != null) {
            out.append(line);
        }

        reader.close();
        return out.toString();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class ApiResult {
        final boolean success;
        final String code;
        final String message;
        final JSONObject data;

        ApiResult(boolean success, String code, String message, JSONObject data) {
            this.success = success;
            this.code = code == null ? "" : code;
            this.message = message == null ? "" : message;
            this.data = data == null ? new JSONObject() : data;
        }
    }
}
