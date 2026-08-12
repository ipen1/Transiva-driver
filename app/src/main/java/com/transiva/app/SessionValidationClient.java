package com.transiva.app;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SessionValidationClient {
    private static final String URL_VALIDATE = "https://transiva.my.id/server/native_validate_session.php";
    private static final long MIN_VALIDATION_INTERVAL_MS = 90_000L;
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);
    private static volatile long lastSuccessAtMs = 0L;
    private SessionValidationClient() {}

    public static void validate(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        SessionManager session = new SessionManager(app);
        String token = session.getToken() == null ? "" : session.getToken().trim();
        if (!session.isLoggedIn() || token.isEmpty()) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (lastSuccessAtMs > 0L && now - lastSuccessAtMs < MIN_VALIDATION_INTERVAL_MS) return;
        if (!IN_FLIGHT.compareAndSet(false, true)) return;

        boolean accepted = DriverNetworkExecutor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(URL_VALIDATE).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(12000);
                conn.setUseCaches(false);
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + token);
                conn.setRequestProperty("X-Device-UUID", DeviceIdentityManager.getInstallationUuid(app));
                conn.setRequestProperty("X-App-Scope", "driver");

                int status = conn.getResponseCode();
                if (conn instanceof HttpsURLConnection) DriverTlsPinning.verify(app, (HttpsURLConnection) conn);
                InputStream stream = status >= 200 && status < 400 ? conn.getInputStream() : conn.getErrorStream();
                String raw = read(stream);
                String code = "";
                try { code = new JSONObject(raw).optString("code", ""); } catch (Exception ignored) {}

                if (ForceLogoutManager.isForceLogoutCode(code)) {
                    ForceLogoutManager.execute(app, code);
                } else if (status >= 200 && status < 300) {
                    session.touchSession();
                    lastSuccessAtMs = android.os.SystemClock.elapsedRealtime();
                }
            } catch (Exception ignored) {
                TransivaDriverCrashReporter.nonFatal("session_validate", ignored);
                // Gangguan internet tidak boleh memaksa logout.
            } finally {
                if (conn != null) conn.disconnect();
                IN_FLIGHT.set(false);
            }
        });
        if (!accepted) IN_FLIGHT.set(false);
    }

    private static String read(InputStream stream) throws Exception {
        if (stream == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
        StringBuilder out = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) out.append(line);
        reader.close();
        return out.toString();
    }
}
