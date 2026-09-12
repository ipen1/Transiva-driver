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
abstract class PinActivityLayer1 extends FragmentActivity {

    protected static final String TAG = "TRANSIVA_PIN";
    protected static final String BASE_URL = "https://transiva.my.id/server/";
    protected static final String STATUS_URL = BASE_URL + "pin_status.php";
    protected static final String SET_URL = BASE_URL + "pin_set.php";
    protected static final String VERIFY_URL = BASE_URL + "pin_verify.php";
    protected static final int TIMEOUT_MS = 25000;
    protected static final int PIN_LENGTH = 6;

    protected final Handler mainHandler = new Handler(Looper.getMainLooper());

    protected SessionManager session;
    protected LinearLayout dotsContainer;
    protected TextView titleText;
    protected TextView subtitleText;
    protected TextView messageText;
    protected TextView stepText;
    protected TextView actionHintText;
    protected ProgressBar progressBar;
    protected LinearLayout keypadContainer;
    protected LinearLayout pinContentRoot;
    protected TextView biometricButton;
    protected boolean biometricPromptShown = false;

    protected boolean loading;
    protected boolean setupMode;
    protected boolean confirmingPin;
    protected String firstPin = "";
    protected String currentPin = "";
    protected String role = "driver";

    protected static final class ApiResult {
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

    protected void verifyPin(String pin) {
        JSONObject body = new JSONObject();
        try {
            body.put("pin", pin);
        } catch (Exception ignored) {}

        setLoading(true);
        DriverNetworkExecutor.execute(() -> {
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
        });
    }

    protected ApiResult request(String endpoint, JSONObject payload) {
        HttpURLConnection conn = null;

        try {
            conn = DriverHttpTransport.open(endpoint);
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
                                PinActivityLayer1.this,
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

    protected void renderDots() {
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

    protected void setLoading(boolean value) {
        loading = value;
        if (progressBar != null) {
            progressBar.setVisibility(value ? View.VISIBLE : View.GONE);
        }
        setKeypadEnabled(!value);
    }

    protected void setKeypadEnabled(boolean enabled) {
        if (keypadContainer == null) return;
        setChildrenEnabled(keypadContainer, enabled);
        keypadContainer.setAlpha(enabled ? 1f : 0.55f);
    }

    protected void setChildrenEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof LinearLayout) {
            LinearLayout group = (LinearLayout) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                setChildrenEnabled(group.getChildAt(i), enabled);
            }
        }
    }

    protected void showMessage(String message, boolean success) {
        if (messageText == null) return;
        messageText.setVisibility(View.VISIBLE);
        messageText.setText(safe(message));
        messageText.setTextColor(Color.parseColor(success ? "#166534" : "#B91C1C"));
        messageText.setBackground(
                round(success ? "#DCFCE7" : "#FEE2E2", dp(12))
        );
    }

    protected void clearMessage() {
        if (messageText == null) return;
        messageText.setText("");
        messageText.setVisibility(View.GONE);
    }

    protected void openRolePage() {
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

    protected void logout() {
        try {
            session.forceLogout("pin_gate_logout");
        } catch (Exception ignored) {}
        openLogin();
    }

    protected void openLogin() {
        Intent intent = new Intent(this, LoginActivity.class);
        intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
        );
        startActivity(intent);
        finish();
    }

    protected String normalizeRole(String value) {
        String clean = safe(value).trim().toLowerCase(Locale.US);
        if (clean.equals("driver")
                || clean.equals("kurir")
                || clean.equals("ojek")
                || clean.equals("rider")) {
            return "driver";
        }
        return "";
    }

    protected TextView text(String value, int size, String color, boolean bold) {
        TextView out = new TextView(this);
        out.setText(value);
        out.setTextSize(size);
        out.setTextColor(Color.parseColor(color));
        if (bold) out.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return out;
    }

    protected GradientDrawable round(String color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.parseColor(color));
        drawable.setCornerRadius(radius);
        return drawable;
    }

    protected GradientDrawable roundStroke(
            String fill,
            String stroke,
            int radius,
            int width
    ) {
        GradientDrawable drawable = round(fill, radius);
        drawable.setStroke(dp(width), Color.parseColor(stroke));
        return drawable;
    }

    protected int findDrawable(String name) {
        try {
            return getResources().getIdentifier(name, "drawable", getPackageName());
        } catch (Exception ignored) {
            return 0;
        }
    }

    protected int dp(int value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density
        );
    }

    protected String readAll(InputStream stream) throws Exception {
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

    protected String safe(String value) {
        return value == null ? "" : value;
    }
    // Cross-layer contracts keep the split type-safe without duplicating state.
    protected abstract View buildScreen();
    protected abstract LinearLayout buildKeypad();
    protected abstract void addKeyRow(LinearLayout parent, String a, String b, String c);
    protected abstract void addKey(LinearLayout row, String value);
    protected abstract void onPinComplete();
    protected abstract void checkPinStatus();
    protected abstract void updateBiometricAvailability();
    protected abstract void showBiometricPrompt();
    protected abstract void setPin(String pin);

}
