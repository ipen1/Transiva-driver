package com.transiva.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.firebase.messaging.FirebaseMessaging;

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
abstract class LoginActivityLayer2 extends LoginActivityLayer1 {

    protected void saveFcmTokenAfterLogin(
            JSONObject user
    ) {
        FcmTokenCoordinator.forceSync(getApplicationContext());
    }

    protected void uploadFcmToken(
            int userId,
            String username,
            String role,
            String fcmToken
    ) {
        FcmTokenCoordinator.forceSync(getApplicationContext());
    }

    protected String getCachedFcmToken() {
        try {
            String value = getSharedPreferences(
                    "transiva_fcm",
                    MODE_PRIVATE
            ).getString("fcm_token", "");

            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        } catch (Exception ignored) {}

        try {
            String value =
                    new SessionManager(this).getFcmToken();

            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        } catch (Exception ignored) {}

        return "";
    }

    protected void saveFcmLocal(
            String token,
            int userId,
            String username,
            String role
    ) {
        String cleanToken =
                token == null ? "" : token.trim();

        getSharedPreferences(
                "transiva_fcm",
                MODE_PRIVATE
        ).edit()
                .putString("fcm_token", cleanToken)
                .putInt("user_id", userId)
                .putString("username", username)
                .putString("role", role)
                .putLong(
                        "fcm_token_saved_at",
                        System.currentTimeMillis()
                )
                .apply();

        try {
            new SessionManager(this)
                    .saveFcmToken(cleanToken);
        } catch (Exception ignored) {}
    }

    protected void openPinPage(String role) {
        Intent intent = new Intent(this, PinActivity.class);
        intent.putExtra("native_role", normalizeRole(role));
        intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
        );
        startActivity(intent);
        finish();
    }

    protected void openRolePage(String role) {
        if (!"driver".equals(normalizeRole(role))) {
            new SessionManager(this).forceLogout("driver_app_role_rejected");
            showMessage("Aplikasi ini khusus Driver Transiva.", false);
            return;
        }

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

    protected String normalizeRole(String role) {
        String clean = role == null ? "" : role.trim().toLowerCase(Locale.US);
        if (clean.equals("driver")
                || clean.equals("kurir")
                || clean.equals("ojek")
                || clean.equals("rider")) {
            return "driver";
        }
        return "";
    }
    protected void togglePassword() {
        int selection =
                passwordInput.getSelectionStart();

        passwordVisible = !passwordVisible;

        passwordInput.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | (
                        passwordVisible
                                ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                                : InputType.TYPE_TEXT_VARIATION_PASSWORD
                )
        );

        passwordInput.setSelection(
                Math.max(0, selection)
        );
    }

    protected void setLoading(boolean value) {
        loading = value;

        loadingView.setVisibility(
                value ? View.VISIBLE : View.GONE);

        loginButton.setEnabled(!value);
        usernameInput.setEnabled(!value);
        passwordInput.setEnabled(!value);
        eyeButton.setEnabled(!value);

        loginButton.setText(
                value ? "Memuat..." : "Masuk →");
    }

    protected void showMessage(
            String message,
            boolean success
    ) {
        messageText.setVisibility(View.VISIBLE);
        messageText.setText(message);
        messageText.setTextColor(
                Color.parseColor(
                        success ? "#166534" : "#B91C1C"
                )
        );
        messageText.setBackground(
                round(
                        success ? "#DCFCE7" : "#FEE2E2",
                        dp(12)
                )
        );
    }

    protected void clearMessage() {
        messageText.setText("");
        messageText.setVisibility(View.GONE);
    }

    protected void openRegister() {
        showMessage("Pendaftaran driver dilakukan melalui admin Transiva.", true);
    }

    protected void openBrowser(String url) {
        try {
            startActivity(
                    new Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(url)
                    )
            );
        } catch (Exception error) {
            showInfo(
                    "Tidak dapat membuka halaman",
                    url
            );
        }
    }
}
