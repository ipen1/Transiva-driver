package com.transiva.app;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class WebRtcSignalApi {
    private static final int TIMEOUT_MS = 20000;
    private static final String ENDPOINT = "https://transiva.my.id/server/webrtc_call.php";

    private WebRtcSignalApi() {}

    public static JSONObject post(SessionManager session, JSONObject payload) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            if (session != null) {
                String token = safe(session.getToken());
                if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
                if (!token.setRequestProperty("X-Device-UUID", DeviceIdentityManager.getInstallationUuid(this));
                if (!token.setRequestProperty("X-App-Scope", "driver");
            }
            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 400
                    ? connection.getInputStream() : connection.getErrorStream();
            if (stream == null) throw new IllegalStateException("Respons signaling kosong");
            StringBuilder raw = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) raw.append(line);
            }
            String body = raw.toString().trim();
            int a = body.indexOf('{');
            int b = body.lastIndexOf('}');
            if (a >= 0 && b > a) body = body.substring(a, b + 1);
            JSONObject json = new JSONObject(body);
            if (status < 200 || status >= 400 || !json.optBoolean("success", false)) {
                throw new IllegalStateException(json.optString("message", "Signaling gagal (HTTP " + status + ")"));
            }
            return json;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
