package com.transiva.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Single native path for releasing a Driver account from the current device.
 * Used by both Settings > Reset perangkat and Account > Keluar akun so that
 * logging out also revokes the server-side device binding before local state is cleared.
 */
public final class DriverDeviceDisconnectClient {
    private static final String DEVICE_URL = "https://transiva.my.id/server/driver_device_native.php";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private DriverDeviceDisconnectClient() {}

    public interface Callback {
        void onSuccess();
        void onError(String message);
    }

    public static void disconnect(Context context, Callback callback) {
        final Context app = context.getApplicationContext();
        DriverNetworkExecutor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                SessionManager session = new SessionManager(app);
                String token = session.getToken();
                if (token == null || token.trim().isEmpty()) {
                    throw new Exception("Sesi Driver tidak tersedia. Silakan login kembali.");
                }

                JSONObject body = new JSONObject();
                body.put("action", "disconnect_device");

                conn = (HttpURLConnection) new URL(DEVICE_URL).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(20000);
                conn.setUseCaches(false);
                conn.setDoInput(true);
                conn.setDoOutput(true);
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("Cache-Control", "no-store");
                conn.setRequestProperty("Authorization", "Bearer " + token.trim());
                conn.setRequestProperty("X-Device-UUID", DeviceIdentityManager.getInstallationUuid(app));
                conn.setRequestProperty("X-App-Scope", "driver");

                try (BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8))) {
                    writer.write(body.toString());
                }

                int status = conn.getResponseCode();
                InputStream stream = status >= 200 && status < 400
                        ? conn.getInputStream() : conn.getErrorStream();
                String raw = readStream(stream);
                if (raw.trim().isEmpty()) throw new Exception("Server tidak mengirim respons.");

                JSONObject json = new JSONObject(raw);
                if (status < 200 || status >= 400 || !json.optBoolean("success", false)) {
                    throw new Exception(json.optString("message", "Gagal melepas perangkat Driver."));
                }

                MAIN.post(callback::onSuccess);
            } catch (Exception e) {
                final String message = e.getMessage() == null || e.getMessage().trim().isEmpty()
                        ? "Gagal melepas perangkat Driver." : e.getMessage().trim();
                MAIN.post(() -> callback.onError(message));
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private static String readStream(InputStream stream) throws Exception {
        if (stream == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) out.append(line);
            return out.toString();
        }
    }
}
