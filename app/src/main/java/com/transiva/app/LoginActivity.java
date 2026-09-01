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

public class LoginActivity extends Activity {

    private static final String TAG = "TRANSIVA_LOGIN";
    private static final String BASE_URL = "https://transiva.my.id/";
    private static final String LOGIN_URL = BASE_URL + "server/login.php";
    private static final String SAVE_FCM_URL =
            BASE_URL + "server/save_fcm_token.php";
    private static final String PRIVACY_URL = BASE_URL + "privacy.html";
    private static final String TERMS_URL = BASE_URL + "terms.html";
    private static final int TIMEOUT_MS = 25000;

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    private EditText usernameInput;
    private EditText passwordInput;
    private Button loginButton;
    private TextView messageText;
    private ProgressBar loadingView;
    private ImageButton eyeButton;

    private boolean loading;
    private boolean passwordVisible;

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

    private View buildScreen() {
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

    private void attemptLogin() {
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

        new Thread(() -> {
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
        }).start();
    }

    private LoginResult doLogin(
            String username,
            String password
    ) {
        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection)
                    new URL(LOGIN_URL).openConnection();

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

    private void saveFcmTokenAfterLogin(
            JSONObject user
    ) {
        try {
            int userId = firstPositiveInt(
                    user.optInt("id", 0),
                    user.optInt("user_id", 0),
                    user.optInt("uid", 0)
            );

            String username = firstNotEmpty(
                    user.optString("username", ""),
                    user.optString("user_name", ""),
                    user.optString("name", "")
            );

            String role = normalizeRole(
                    user.optString("role", "customer")
            );

            String cachedToken = getCachedFcmToken();

            if (!cachedToken.isEmpty()) {
                saveFcmLocal(
                        cachedToken,
                        userId,
                        username,
                        role
                );

                uploadFcmToken(
                        userId,
                        username,
                        role,
                        cachedToken
                );
            }

            FirebaseMessaging.getInstance()
                    .getToken()
                    .addOnSuccessListener(token -> {
                        String clean =
                                token == null
                                        ? ""
                                        : token.trim();

                        if (clean.isEmpty()) return;

                        saveFcmLocal(
                                clean,
                                userId,
                                username,
                                role
                        );

                        uploadFcmToken(
                                userId,
                                username,
                                role,
                                clean
                        );
                    })
                    .addOnFailureListener(
                            error -> Log.e(
                                    TAG,
                                    "FCM token gagal",
                                    error
                            )
                    );

        } catch (Exception error) {
            Log.e(TAG, "FCM setelah login gagal", error);
        }
    }

    private void uploadFcmToken(
            int userId,
            String username,
            String role,
            String fcmToken
    ) {
        if (fcmToken == null || fcmToken.trim().isEmpty()) {
            return;
        }

        new Thread(() -> {
            HttpURLConnection connection = null;

            try {
                connection = (HttpURLConnection)
                        new URL(SAVE_FCM_URL).openConnection();

                String authToken = new SessionManager(this).getToken();
                if (authToken != null && !authToken.trim().isEmpty()) {
                    connection.setRequestProperty(
                            "Authorization",
                            "Bearer " + authToken.trim()
                    );
                    connection.setRequestProperty(
                            "X-Device-UUID",
                            DeviceIdentityManager.getInstallationUuid(this)
                    );
                    connection.setRequestProperty(
                            "X-App-Scope",
                            "driver"
                    );
                }

                connection.setRequestMethod("POST");
                connection.setConnectTimeout(TIMEOUT_MS);
                connection.setReadTimeout(TIMEOUT_MS);
                connection.setDoInput(true);
                connection.setDoOutput(true);
                connection.setUseCaches(false);
                connection.setRequestProperty(
                        "Content-Type",
                        "application/json; charset=UTF-8"
                );
                connection.setRequestProperty(
                        "Accept",
                        "application/json"
                );

                JSONObject payload = new JSONObject();
                payload.put("user_id", userId);
                payload.put("id", userId);
                payload.put("username", username);
                payload.put("role", role);
                payload.put("fcm_token", fcmToken.trim());
                payload.put("token", fcmToken.trim());
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

                try (BufferedWriter writer =
                             new BufferedWriter(
                                     new OutputStreamWriter(
                                             connection.getOutputStream(),
                                             StandardCharsets.UTF_8
                                     )
                             )) {
                    writer.write(payload.toString());
                }

                int code = connection.getResponseCode();

                InputStream stream =
                        code >= 200 && code < 300
                                ? connection.getInputStream()
                                : connection.getErrorStream();

                Log.d(
                        TAG,
                        "FCM upload HTTP=" + code
                                + ", body=" + readStream(stream)
                );

            } catch (Exception error) {
                Log.e(TAG, "Upload FCM gagal", error);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).start();
    }

    private String getCachedFcmToken() {
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

    private void saveFcmLocal(
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

    private void openPinPage(String role) {
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

    private void openRolePage(String role) {
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

    private String normalizeRole(String role) {
        String clean = role == null ? "" : role.trim().toLowerCase(Locale.US);
        if (clean.equals("driver")
                || clean.equals("kurir")
                || clean.equals("ojek")
                || clean.equals("rider")) {
            return "driver";
        }
        return "";
    }
    private void togglePassword() {
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

    private void setLoading(boolean value) {
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

    private void showMessage(
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

    private void clearMessage() {
        messageText.setText("");
        messageText.setVisibility(View.GONE);
    }

    private void openRegister() {
        showMessage("Pendaftaran driver dilakukan melalui admin Transiva.", true);
    }

    private void openBrowser(String url) {
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

    private void showInfo(
            String title,
            String message
    ) {
        PremiumDialogs.builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    private String readStream(
            InputStream stream
    ) throws Exception {
        if (stream == null) return "";

        try (BufferedReader reader =
                     new BufferedReader(
                             new InputStreamReader(
                                     stream,
                                     StandardCharsets.UTF_8
                             )
                     )) {
            StringBuilder result = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                result.append(line);
            }

            return result.toString();
        }
    }

    private TextView label(String value) {
        TextView view =
                text(value, 14, "#123F7A", true);
        view.setPadding(0, dp(5), 0, dp(6));
        return view;
    }

    private EditText input(
            String hint,
            int inputType,
            boolean hasEye
    ) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setTextSize(14);
        field.setTextColor(Color.parseColor("#1F2937"));
        field.setHintTextColor(Color.parseColor("#98A2B3"));
        field.setHint(hint);
        field.setInputType(inputType);
        field.setPadding(
                dp(18),
                0,
                hasEye ? dp(52) : dp(18),
                0
        );
        field.setBackground(
                roundStroke(
                        "#FFFFFF",
                        "#D8E1ED",
                        dp(16),
                        1
                )
        );
        return field;
    }

    private LinearLayout.LayoutParams fieldLayout() {
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(-1, dp(52));
        lp.setMargins(0, 0, 0, dp(13));
        return lp;
    }

    private TextView text(
            String value,
            int size,
            String color,
            boolean bold
    ) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Color.parseColor(color));

        if (bold) {
            view.setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD
            );
        }

        return view;
    }

    private GradientDrawable round(
            String fill,
            int radius
    ) {
        GradientDrawable drawable =
                new GradientDrawable();
        drawable.setColor(Color.parseColor(fill));
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private GradientDrawable roundStroke(
            String fill,
            String stroke,
            int radius,
            int width
    ) {
        GradientDrawable drawable =
                round(fill, radius);

        drawable.setStroke(
                dp(width),
                Color.parseColor(stroke)
        );

        return drawable;
    }

    private GradientDrawable gradient(
            String start,
            String end,
            int radius
    ) {
        GradientDrawable drawable =
                new GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[]{
                                Color.parseColor(start),
                                Color.parseColor(end)
                        }
                );

        drawable.setCornerRadius(radius);
        return drawable;
    }

    private int findDrawable(String name) {
        return getResources().getIdentifier(
                name,
                "drawable",
                getPackageName()
        );
    }

    private int dp(int value) {
        return Math.round(
                value
                        * getResources()
                        .getDisplayMetrics()
                        .density
        );
    }

    private int firstPositiveInt(int... values) {
        if (values == null) return 0;

        for (int value : values) {
            if (value > 0) return value;
        }

        return 0;
    }

    private String firstNotEmpty(String... values) {
        if (values == null) return "";

        for (String value : values) {
            if (value != null
                    && !value.trim().isEmpty()) {
                return value.trim();
            }
        }

        return "";
    }

    private static final class LoginResult {
        final boolean success;
        final String message;
        final String role;
        final JSONObject user;

        private LoginResult(
                boolean success,
                String message,
                String role,
                JSONObject user
        ) {
            this.success = success;
            this.message = message;
            this.role = role;
            this.user = user;
        }

        static LoginResult ok(
                String message,
                String role,
                JSONObject user
        ) {
            return new LoginResult(
                    true,
                    message,
                    role,
                    user
            );
        }

        static LoginResult fail(String message) {
            return new LoginResult(
                    false,
                    message,
                    "",
                    null
            );
        }
    }
}
