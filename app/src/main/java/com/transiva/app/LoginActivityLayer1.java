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
abstract class LoginActivityLayer1 extends Activity {

    protected static final String TAG = "TRANSIVA_LOGIN";
    protected static final String BASE_URL = "https://transiva.my.id/";
    protected static final String LOGIN_URL = BASE_URL + "server/login.php";
    protected static final String SAVE_FCM_URL =
            BASE_URL + "server/save_fcm_token.php";
    protected static final String PRIVACY_URL = BASE_URL + "privacy.html";
    protected static final String TERMS_URL = BASE_URL + "terms.html";
    protected static final int TIMEOUT_MS = 25000;

    protected final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    protected EditText usernameInput;
    protected EditText passwordInput;
    protected Button loginButton;
    protected TextView messageText;
    protected ProgressBar loadingView;
    protected ImageButton eyeButton;

    protected boolean loading;
    protected boolean passwordVisible;

    protected static final class LoginResult {
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

    protected void showInfo(
            String title,
            String message
    ) {
        PremiumDialogs.builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    protected String readStream(
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

    protected TextView label(String value) {
        TextView view =
                text(value, 14, "#123F7A", true);
        view.setPadding(0, dp(5), 0, dp(6));
        return view;
    }

    protected EditText input(
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

    protected LinearLayout.LayoutParams fieldLayout() {
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(-1, dp(52));
        lp.setMargins(0, 0, 0, dp(13));
        return lp;
    }

    protected TextView text(
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

    protected GradientDrawable round(
            String fill,
            int radius
    ) {
        GradientDrawable drawable =
                new GradientDrawable();
        drawable.setColor(Color.parseColor(fill));
        drawable.setCornerRadius(radius);
        return drawable;
    }

    protected GradientDrawable roundStroke(
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

    protected GradientDrawable gradient(
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

    protected int findDrawable(String name) {
        return getResources().getIdentifier(
                name,
                "drawable",
                getPackageName()
        );
    }

    protected int dp(int value) {
        return Math.round(
                value
                        * getResources()
                        .getDisplayMetrics()
                        .density
        );
    }

    protected int firstPositiveInt(int... values) {
        if (values == null) return 0;

        for (int value : values) {
            if (value > 0) return value;
        }

        return 0;
    }

    protected String firstNotEmpty(String... values) {
        if (values == null) return "";

        for (String value : values) {
            if (value != null
                    && !value.trim().isEmpty()) {
                return value.trim();
            }
        }

        return "";
    }
    // Cross-layer contracts keep the split type-safe without duplicating state.
    protected abstract View buildScreen();
    protected abstract void attemptLogin();
    protected abstract LoginResult doLogin( String username, String password );
    protected abstract void saveFcmTokenAfterLogin( JSONObject user );
    protected abstract void uploadFcmToken( int userId, String username, String role, String fcmToken );
    protected abstract String getCachedFcmToken();
    protected abstract void saveFcmLocal( String token, int userId, String username, String role );
    protected abstract void openPinPage(String role);
    protected abstract void openRolePage(String role);
    protected abstract String normalizeRole(String role);
    protected abstract void togglePassword();
    protected abstract void setLoading(boolean value);
    protected abstract void showMessage( String message, boolean success );
    protected abstract void clearMessage();
    protected abstract void openRegister();
    protected abstract void openBrowser(String url);

}
