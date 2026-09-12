package com.transiva.app;

import android.annotation.SuppressLint;

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
public class PinActivity extends PinActivityLayer1 {

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
    @SuppressLint("MissingSuperCall")
    public void onBackPressed() {
        // PIN gate tidak boleh dilewati dengan tombol Back.
        // Pengguna tetap bisa keluar akun melalui tombol "Keluar akun".
    }

    protected View buildScreen() {
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

    protected LinearLayout buildKeypad() {
        LinearLayout keypad = new LinearLayout(this);
        keypad.setOrientation(LinearLayout.VERTICAL);

        addKeyRow(keypad, "1", "2", "3");
        addKeyRow(keypad, "4", "5", "6");
        addKeyRow(keypad, "7", "8", "9");
        addKeyRow(keypad, "", "0", "⌫");

        return keypad;
    }

    protected void addKeyRow(LinearLayout parent, String a, String b, String c) {
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

    protected void addKey(LinearLayout row, String value) {
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

    protected void onPinComplete() {
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

    protected void checkPinStatus() {
        setLoading(true);

        DriverNetworkExecutor.execute(() -> {
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
        });
    }

    protected void updateBiometricAvailability() {
        if (biometricButton == null) return;
        if (setupMode) { biometricButton.setVisibility(View.GONE); return; }
        BiometricManager bm = BiometricManager.from(this);
        int can = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        biometricButton.setVisibility(can == BiometricManager.BIOMETRIC_SUCCESS ? View.VISIBLE : View.GONE);
    }

    protected void showBiometricPrompt() {
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

    protected void setPin(String pin) {
        JSONObject body = new JSONObject();
        try {
            body.put("pin", pin);
            body.put("confirm_pin", pin);
        } catch (Exception ignored) {}

        setLoading(true);
        DriverNetworkExecutor.execute(() -> {
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
        });
    }
}
