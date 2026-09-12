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
public class LoginActivity extends LoginActivityLayer2 {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            getWindow().setStatusBarColor(
                    Color.parseColor("#0A1A2E"));
            getWindow().setNavigationBarColor(
                    Color.parseColor("#0A1A2E"));
        } catch (Exception ignored) {}

        /*
         * Jangan otomatis mengarahkan berdasarkan session lama di sini.
         * Session invalid akan dibersihkan saat login/dashboard.
         */
        setContentView(buildScreen());

        try {
            TransivaNotificationPermission.ask(this);
        } catch (Exception error) {
            Log.w(TAG, "Permission notifikasi gagal", error);
        }
    }

    protected View buildScreen() {
        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(Color.parseColor("#F4F8FF"));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        page.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(24));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        ImageView logo = new ImageView(this);
        int logoRes = findDrawable("transiva_logo");
        if (logoRes == 0) logoRes = findDrawable("logo_transiva");
        if (logoRes == 0) logoRes = findDrawable("logo");
        if (logoRes == 0) logoRes = getApplicationInfo().icon;
        logo.setImageResource(logoRes);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);

        LinearLayout.LayoutParams logoLp =
                new LinearLayout.LayoutParams(dp(190), dp(80));
        logoLp.setMargins(0, dp(6), 0, dp(5));
        root.addView(logo, logoLp);

        TextView title = text(
                "Masuk Transiva", 27, "#123F7A", true);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView subtitle = text(
                "Masuk khusus mitra Driver Transiva",
                14,
                "#667085",
                false
        );
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(7), 0, dp(18));
        root.addView(subtitle);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(20), dp(18), dp(20));
        card.setBackground(
                roundStroke("#FFFFFF", "#E5EBF3", dp(24), 1));
        card.setElevation(dp(5));
        root.addView(card, new LinearLayout.LayoutParams(-1, -2));

        messageText = text("", 12, "#B91C1C", true);
        messageText.setVisibility(View.GONE);
        messageText.setPadding(dp(14), dp(11), dp(14), dp(11));

        LinearLayout.LayoutParams messageLp =
                new LinearLayout.LayoutParams(-1, -2);
        messageLp.setMargins(0, 0, 0, dp(13));
        card.addView(messageText, messageLp);

        card.addView(label("Nama Pengguna"));

        usernameInput = input(
                "Masukkan Nama Pengguna",
                InputType.TYPE_CLASS_TEXT,
                false
        );
        card.addView(usernameInput, fieldLayout());

        card.addView(label("Kata Sandi"));

        FrameLayout passwordBox = new FrameLayout(this);

        passwordInput = input(
                "Masukkan Kata Sandi",
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                true
        );
        passwordBox.addView(
                passwordInput,
                new FrameLayout.LayoutParams(-1, -1)
        );

        eyeButton = new ImageButton(this);
        eyeButton.setImageResource(
                android.R.drawable.ic_menu_view);
        eyeButton.setBackgroundColor(Color.TRANSPARENT);
        eyeButton.setColorFilter(Color.parseColor("#1E88F5"));

        FrameLayout.LayoutParams eyeLp =
                new FrameLayout.LayoutParams(dp(44), dp(44));
        eyeLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        eyeLp.rightMargin = dp(5);
        passwordBox.addView(eyeButton, eyeLp);

        card.addView(passwordBox, fieldLayout());

        loginButton = new Button(this);
        loginButton.setText("Masuk →");
        loginButton.setAllCaps(false);
        loginButton.setTextSize(17);
        loginButton.setTextColor(Color.WHITE);
        loginButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        loginButton.setBackground(
                gradient("#006BEF", "#2E9BFF", dp(17)));

        LinearLayout.LayoutParams loginLp =
                new LinearLayout.LayoutParams(-1, dp(54));
        loginLp.setMargins(0, dp(8), 0, dp(14));
        card.addView(loginButton, loginLp);

        TextView register = text(
                "Akun driver dibuat dan diverifikasi oleh Transiva",
                14,
                "#1685F2",
                true
        );
        register.setGravity(Gravity.CENTER);
        register.setPadding(0, dp(3), 0, dp(15));
        card.addView(register);

        LinearLayout legal = new LinearLayout(this);
        legal.setGravity(Gravity.CENTER);

        TextView privacy =
                text("Kebijakan Privasi", 12, "#1685F2", true);
        TextView separator =
                text(" | ", 12, "#CBD5E1", false);
        TextView terms =
                text("Syarat & Ketentuan", 12, "#1685F2", true);

        legal.addView(privacy);
        legal.addView(separator);
        legal.addView(terms);
        card.addView(legal);

        loadingView = new ProgressBar(this);
        loadingView.setVisibility(View.GONE);

        FrameLayout.LayoutParams loadingLp =
                new FrameLayout.LayoutParams(
                        dp(52), dp(52), Gravity.CENTER);
        page.addView(loadingView, loadingLp);

        loginButton.setOnClickListener(v -> attemptLogin());
        eyeButton.setOnClickListener(v -> togglePassword());
        register.setOnClickListener(v -> showMessage("Pendaftaran driver dilakukan melalui admin Transiva.", true));
        privacy.setOnClickListener(v -> openBrowser(PRIVACY_URL));
        terms.setOnClickListener(v -> openBrowser(TERMS_URL));

        passwordInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        passwordInput.setOnEditorActionListener(
                (v, actionId, event) -> {
                    boolean enter = event != null
                            && event.getKeyCode()
                            == KeyEvent.KEYCODE_ENTER;

                    if (actionId == EditorInfo.IME_ACTION_DONE
                            || enter) {
                        attemptLogin();
                        return true;
                    }
                    return false;
                }
        );

        return page;
    }

    protected void attemptLogin() {
        if (loading) return;

        clearMessage();

        String username =
                usernameInput.getText().toString().trim();
        String password =
                passwordInput.getText().toString();

        if (username.isEmpty() || password.isEmpty()) {
            showMessage(
                    "Lengkapi Nama Pengguna dan Kata Sandi",
                    false
            );
            return;
        }

        setLoading(true);

        DriverNetworkExecutor.execute(() -> {
            LoginResult result = doLogin(username, password);

            mainHandler.post(() -> {
                setLoading(false);

                if (!result.success) {
                    showMessage(result.message, false);
                    return;
                }

                if (result.user == null) {
                    showMessage(
                            "Server tidak mengirim data pengguna.",
                            false
                    );
                    return;
                }

                String apiToken =
                        result.user.optString("token", "").trim();

                if (apiToken.isEmpty()) {
                    showMessage(
                            "Login berhasil, tetapi token sesi kosong. "
                                    + "Pastikan login.php sudah di-upgrade.",
                            false
                    );
                    return;
                }

                String role = normalizeRole(
                        result.user.optString(
                                "role",
                                result.role
                        )
                );

                if (!"driver".equals(role)) {
                    new SessionManager(LoginActivity.this)
                            .forceLogout("driver_app_role_rejected");
                    showMessage(
                            "Aplikasi ini khusus Driver Transiva. Akun ini bukan akun driver.",
                            false
                    );
                    return;
                }

                try {
                    result.user.put("role", role);
                } catch (Exception ignored) {}

                SessionManager session =
                        new SessionManager(LoginActivity.this);

                boolean sessionSaved;

                try {
                    /*
                     * Bersihkan session lama agar token/customer/driver
                     * tidak tercampur.
                     */
                    session.forceLogout("replace_login_session");
                    sessionSaved = session.saveUser(result.user);
                } catch (Exception error) {
                    Log.e(TAG, "Gagal menyimpan session", error);
                    sessionSaved = false;
                }

                if (!sessionSaved
                        || !session.isLoggedIn()
                        || session.getUsername().trim().isEmpty()
                        || session.getToken().trim().isEmpty()) {

                    session.forceLogout("login_session_invalid");

                    showMessage(
                            "Login berhasil, tetapi sesi gagal disimpan.",
                            false
                    );
                    return;
                }

                try {
                    TransivaSession.saveUser(
                            LoginActivity.this,
                            result.user
                    );
                } catch (Exception error) {
                    Log.w(TAG, "Legacy session gagal", error);
                }

                Log.d(
                        TAG,
                        "Session valid role=" + session.getRole()
                                + ", user=" + session.getUsername()
                                + ", tokenLength="
                                + session.getToken().length()
                );

                saveFcmTokenAfterLogin(result.user);

                showMessage("Login berhasil", true);

                String finalRole = role;

                mainHandler.postDelayed(
                        () -> openPinPage(finalRole),
                        500
                );
            });
        });
    }

    protected LoginResult doLogin(
            String username,
            String password
    ) {
        HttpURLConnection connection = null;

        try {
            connection = DriverHttpTransport.open(LOGIN_URL);

            connection.setRequestMethod("POST");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setDoInput(true);
            connection.setDoOutput(true);
            connection.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=UTF-8"
            );
            connection.setRequestProperty(
                    "Accept",
                    "application/json"
            );
            connection.setRequestProperty(
                    "X-Transiva-Client",
                    "Android-Native"
            );

            JSONObject payload = new JSONObject();
            payload.put("username", username);
            payload.put("password", password);
            payload.put(
                    "device_name",
                    Build.MANUFACTURER + " " + Build.MODEL
            );
            payload.put("platform", "android_native");
            payload.put("app_scope", "driver");
            payload.put("installation_uuid", DeviceIdentityManager.getInstallationUuid(this));
            payload.put("manufacturer", Build.MANUFACTURER);
            payload.put("model", Build.MODEL);
            payload.put("android_version", Build.VERSION.RELEASE);
            try {
                payload.put("app_version", getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
            } catch (Exception ignored) {
                payload.put("app_version", "unknown");
            }

            String cachedFcmToken = getCachedFcmToken();

            if (!cachedFcmToken.isEmpty()) {
                /*
                 * FCM token hanya untuk notifikasi.
                 * Jangan kirim sebagai field token autentikasi.
                 */
                payload.put("fcm_token", cachedFcmToken);
            }

            try (BufferedWriter writer =
                         new BufferedWriter(
                                 new OutputStreamWriter(
                                         connection.getOutputStream(),
                                         StandardCharsets.UTF_8
                                 )
                         )) {
                writer.write(payload.toString());
                writer.flush();
            }

            int httpCode = connection.getResponseCode();

            InputStream stream =
                    httpCode >= 200 && httpCode < 300
                            ? connection.getInputStream()
                            : connection.getErrorStream();

            String raw = readStream(stream).trim();

            Log.d(
                    TAG,
                    "Login HTTP=" + httpCode
                            + ", bodyLength=" + raw.length()
            );

            if (raw.isEmpty()) {
                return LoginResult.fail(
                        "Server tidak mengirim response."
                );
            }

            JSONObject response = new JSONObject(raw);

            boolean success =
                    response.optBoolean("success", false);

            String message = response.optString(
                    "message",
                    success ? "Login berhasil" : "Login gagal"
            );

            if (!success || httpCode < 200 || httpCode >= 300) {
                return LoginResult.fail(message);
            }

            JSONObject user =
                    response.optJSONObject("user");

            if (user == null) {
                return LoginResult.fail(
                        "Data pengguna tidak ditemukan."
                );
            }

            /*
             * Kompatibilitas jika server menaruh token di root.
             */
            if (user.optString("token", "").trim().isEmpty()) {
                String rootToken =
                        response.optString("token", "").trim();

                if (!rootToken.isEmpty()) {
                    user.put("token", rootToken);
                }
            }

            String role = normalizeRole(
                    user.optString("role", "customer")
            );

            user.put("role", role);

            return LoginResult.ok(
                    message,
                    role,
                    user
            );

        } catch (Exception error) {
            Log.e(TAG, "Login gagal", error);
            return LoginResult.fail(
                    "Server error atau koneksi gagal."
            );
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
