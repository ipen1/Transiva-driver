package com.transiva.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Policy keamanan Driver dari server.
 * Default fail-secure: root dan fake GPS aktif bila policy belum dapat dibaca.
 */
public final class DriverSecurityPolicy {
    private static final String POLICY_URL =
            "https://transiva.my.id/server/driver_security_policy.php";
    private static final String PREF = "driver_security_policy";
    private static final long CACHE_MS = 5L * 60L * 1000L;

    private DriverSecurityPolicy() {}

    public static final class Policy {
        public final boolean rootEnabled;
        public final boolean fakeGpsEnabled;
        public final String source;
        Policy(boolean rootEnabled, boolean fakeGpsEnabled, String source) {
            this.rootEnabled = rootEnabled;
            this.fakeGpsEnabled = fakeGpsEnabled;
            this.source = source == null ? "" : source;
        }
    }

    /** Blocking; panggil hanya dari worker/background thread. */
    public static Policy resolve(Context context) {
        return resolve(context, false);
    }

    /** Paksa baca server, dipakai saat device sedang diblokir atau admin baru mengubah policy. */
    public static Policy resolveFresh(Context context) {
        return resolve(context, true);
    }

    private static Policy resolve(Context context, boolean forceRefresh) {
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long checkedAt = prefs.getLong("checked_at", 0L);
        if (!forceRefresh && checkedAt > 0L && now - checkedAt < CACHE_MS) {
            return cached(app);
        }

        String userId = "";
        String username = "";
        try {
            SessionManager session = new SessionManager(app);
            userId = safe(session.getUserId());
            username = safe(session.getUsername());
        } catch (Throwable ignored) { }

        HttpURLConnection connection = null;
        try {
            StringBuilder link = new StringBuilder(POLICY_URL);
            link.append("?app=driver");
            if (!userId.isEmpty()) link.append("&user_id=").append(Uri.encode(userId));
            if (!username.isEmpty()) link.append("&username=").append(Uri.encode(username));

            connection = (HttpURLConnection) new URL(link.toString()).openConnection();
            connection.setConnectTimeout(4500);
            connection.setReadTimeout(4500);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("X-Transiva-App", "Android-Driver");

            int code = connection.getResponseCode();
            InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            StringBuilder body = new StringBuilder();
            if (input != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(input))) {
                    String line;
                    while ((line = reader.readLine()) != null) body.append(line);
                }
            }
            if (code < 200 || code >= 300) return cached(app);

            JSONObject json = new JSONObject(body.toString());
            if (!json.optBoolean("success", false)) return cached(app);

            boolean root = json.optBoolean("root_detection_enabled", true);
            boolean fake = json.optBoolean("fake_gps_detection_enabled", true);
            String source = json.optString("policy_source", "global");

            prefs.edit()
                    .putBoolean("root_detection_enabled", root)
                    .putBoolean("fake_gps_detection_enabled", fake)
                    .putString("policy_source", source)
                    .putLong("checked_at", now)
                    .apply();
            return new Policy(root, fake, source);
        } catch (Throwable ignored) {
            return cached(app);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    public static Policy cached(Context context) {
        SharedPreferences p = context.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);
        return new Policy(
                p.getBoolean("root_detection_enabled", true),
                p.getBoolean("fake_gps_detection_enabled", true),
                p.getString("policy_source", "fallback")
        );
    }

    public static boolean rootEnabledCached(Context context) {
        return cached(context).rootEnabled;
    }

    public static boolean fakeGpsEnabledCached(Context context) {
        return cached(context).fakeGpsEnabled;
    }

    public static void invalidate(Context context) {
        try {
            context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().remove("checked_at").apply();
        } catch (Throwable ignored) { }
    }

    public static long checkedAt(Context context) {
        try {
            return context.getApplicationContext()
                    .getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .getLong("checked_at", 0L);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
