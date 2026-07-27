package com.transiva.app;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public final class SessionValidationClient {
    private static final String URL_VALIDATE = "https://transiva.my.id/server/native_validate_session.php";
    private SessionValidationClient() {}

    public static void validate(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        SessionManager session = new SessionManager(app);
        String token = session.getToken() == null ? "" : session.getToken().trim();
        if (!session.isLoggedIn() || token.isEmpty()) return;

        new Thread(() -> {
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

                int status = conn.getResponseCode();
                InputStream stream = status >= 200 && status < 400 ? conn.getInputStream() : conn.getErrorStream();
                String raw = read(stream);
                String code = "";
                try { code = new JSONObject(raw).optString("code", ""); } catch (Exception ignored) {}

                if (status == 401 || status == 403 || ForceLogoutManager.isForceLogoutCode(code)) {
                    ForceLogoutManager.execute(app, code.isEmpty() ? "SESSION_REVOKED" : code);
                } else if (status >= 200 && status < 300) {
                    session.touchSession();
                }
            } catch (Exception ignored) {
                // Gangguan internet tidak boleh memaksa logout.
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "transiva-session-validate").start();
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
