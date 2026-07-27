package com.transiva.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class ChangePinActivity extends Activity {

    private static final String CHANGE_PIN_URL = "https://transiva.my.id/server/pin_change.php";
    private static final int TIMEOUT_MS = 25000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private SessionManager session;
    private EditText oldPinInput;
    private EditText newPinInput;
    private EditText confirmPinInput;
    private Button saveButton;
    private ProgressBar progressBar;
    private TextView messageView;
    private boolean loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.parseColor("#0B7CFF"));
        getWindow().setNavigationBarColor(Color.parseColor("#081423"));

        session = new SessionManager(this);
        if (!session.isLoggedIn() || safe(session.getToken()).isEmpty()) {
            startActivity(new Intent(this, LoginActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
            return;
        }

        setContentView(buildScreen());
    }

    private View buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.parseColor("#F4F8FF"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(30));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView back = text("‹  Kembali", 14, "#0B7CFF", true);
        back.setPadding(0, dp(8), 0, dp(18));
        back.setOnClickListener(v -> finish());
        root.addView(back);

        TextView title = text("Ubah PIN", 26, "#0B3A78", true);
        root.addView(title);

        TextView subtitle = text(
                "Perbarui PIN 6 digit untuk menjaga keamanan akun Transiva Anda.",
                13,
                "#64748B",
                false
        );
        subtitle.setPadding(0, dp(6), 0, dp(20));
        root.addView(subtitle);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(20), dp(18), dp(20));
        card.setBackground(roundStroke("#FFFFFF", "#D9E4F2", dp(24), 1));
        card.setElevation(dp(4));
        root.addView(card, new LinearLayout.LayoutParams(-1, -2));

        messageView = text("", 12, "#B91C1C", true);
        messageView.setVisibility(View.GONE);
        messageView.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(-1, -2);
        msgLp.setMargins(0, 0, 0, dp(14));
        card.addView(messageView, msgLp);

        card.addView(label("PIN Lama"));
        oldPinInput = pinInput("Masukkan PIN lama");
        card.addView(oldPinInput, fieldLp());

        card.addView(label("PIN Baru"));
        newPinInput = pinInput("Masukkan PIN baru 6 digit");
        card.addView(newPinInput, fieldLp());

        card.addView(label("Konfirmasi PIN Baru"));
        confirmPinInput = pinInput("Ulangi PIN baru");
        card.addView(confirmPinInput, fieldLp());

        TextView hint = text(
                "Gunakan 6 angka yang mudah Anda ingat tetapi sulit ditebak orang lain.",
                11,
                "#7A8798",
                false
        );
        hint.setPadding(0, 0, 0, dp(14));
        card.addView(hint);

        saveButton = new Button(this);
        saveButton.setText("Simpan PIN Baru");
        saveButton.setTextColor(Color.WHITE);
        saveButton.setTextSize(15);
        saveButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        saveButton.setAllCaps(false);
        saveButton.setBackground(round("#0B7CFF", dp(16)));
        saveButton.setOnClickListener(v -> submit());
        card.addView(saveButton, new LinearLayout.LayoutParams(-1, dp(54)));

        progressBar = new ProgressBar(this);
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        progressLp.gravity = Gravity.CENTER_HORIZONTAL;
        progressLp.setMargins(0, dp(15), 0, 0);
        card.addView(progressBar, progressLp);

        return scroll;
    }

    private void submit() {
        if (loading) return;

        String oldPin = oldPinInput.getText().toString().trim();
        String newPin = newPinInput.getText().toString().trim();
        String confirmPin = confirmPinInput.getText().toString().trim();

        if (!validPin(oldPin)) {
            showMessage("PIN lama harus terdiri dari 6 angka.", false);
            return;
        }
        if (!validPin(newPin)) {
            showMessage("PIN baru harus terdiri dari 6 angka.", false);
            return;
        }
        if (!newPin.equals(confirmPin)) {
            showMessage("Konfirmasi PIN baru tidak sama.", false);
            return;
        }
        if (oldPin.equals(newPin)) {
            showMessage("PIN baru harus berbeda dari PIN lama.", false);
            return;
        }

        setLoading(true);
        new Thread(() -> {
            ApiResult result = changePin(oldPin, newPin);
            mainHandler.post(() -> {
                setLoading(false);
                showMessage(result.message, result.success);
                if (result.success) {
                    oldPinInput.setText("");
                    newPinInput.setText("");
                    confirmPinInput.setText("");
                    mainHandler.postDelayed(this::finish, 900);
                }
            });
        }).start();
    }

    private ApiResult changePin(String oldPin, String newPin) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(CHANGE_PIN_URL).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setDoInput(true);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + safe(session.getToken()));
            connection.setRequestProperty("X-Transiva-Client", "Android-Native");

            JSONObject payload = new JSONObject();
            payload.put("old_pin", oldPin);
            payload.put("new_pin", newPin);

            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    connection.getOutputStream(), StandardCharsets.UTF_8))) {
                writer.write(payload.toString());
            }

            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 400
                    ? connection.getInputStream() : connection.getErrorStream();
            String raw = readStream(stream);
            JSONObject json = raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
            boolean success = json.optBoolean("success", false);
            String message = json.optString("message", success ? "PIN berhasil diubah." : "Gagal mengubah PIN.");
            return new ApiResult(success, message);
        } catch (Exception e) {
            return new ApiResult(false, "Tidak dapat terhubung ke server. Coba lagi.");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String readStream(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line);
        }
        return out.toString();
    }

    private void setLoading(boolean value) {
        loading = value;
        progressBar.setVisibility(value ? View.VISIBLE : View.GONE);
        saveButton.setEnabled(!value);
        oldPinInput.setEnabled(!value);
        newPinInput.setEnabled(!value);
        confirmPinInput.setEnabled(!value);
        saveButton.setAlpha(value ? 0.6f : 1f);
    }

    private void showMessage(String message, boolean success) {
        messageView.setVisibility(View.VISIBLE);
        messageView.setText(message);
        messageView.setTextColor(Color.parseColor(success ? "#166534" : "#B91C1C"));
        messageView.setBackground(round(success ? "#DCFCE7" : "#FEE2E2", dp(12)));
    }

    private boolean validPin(String value) {
        return value != null && value.matches("\\d{6}");
    }

    private EditText pinInput(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setHintTextColor(Color.parseColor("#A0AAB8"));
        input.setTextColor(Color.parseColor("#0B3A78"));
        input.setTextSize(16);
        input.setSingleLine(true);
        input.setGravity(Gravity.CENTER_VERTICAL);
        input.setPadding(dp(14), 0, dp(14), 0);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setBackground(roundStroke("#F8FAFD", "#D3DFED", dp(14), 1));
        return input;
    }

    private TextView label(String value) {
        TextView view = text(value, 12, "#3E5874", true);
        view.setPadding(dp(2), dp(2), 0, dp(7));
        return view;
    }

    private LinearLayout.LayoutParams fieldLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(54));
        lp.setMargins(0, 0, 0, dp(14));
        return lp;
    }

    private TextView text(String value, int size, String color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Color.parseColor(color));
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable round(String color, int radiusPx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.parseColor(color));
        drawable.setCornerRadius(radiusPx);
        return drawable;
    }

    private GradientDrawable roundStroke(String fill, String stroke, int radiusPx, int strokeDp) {
        GradientDrawable drawable = round(fill, radiusPx);
        drawable.setStroke(dp(strokeDp), Color.parseColor(stroke));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static class ApiResult {
        final boolean success;
        final String message;
        ApiResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }
}
