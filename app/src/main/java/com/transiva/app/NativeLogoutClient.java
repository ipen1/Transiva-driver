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

public class NativeLogoutClient {

    private static final String LOGOUT_URL = "https://transiva.my.id/server/logout.php";
    private static final int TIMEOUT_MS = 15000;

    public interface Callback {
        void onDone(boolean success, String response);
    }

    public static void logoutAndDeleteToken(Context context, Callback callback) {
        Context appContext = context.getApplicationContext();

        new Thread(() -> {
            boolean success = false;
            String response = "";
            HttpURLConnection conn = null;

            try {
                SessionManager session = new SessionManager(appContext);

                String userId = firstNonEmpty(session.getId(), session.getUserId(), session.get("id"), session.get("user_id"));
                String username = firstNonEmpty(session.getUsername(), session.getName(), session.get("username"), session.get("name"));
                String role = firstNonEmpty(session.getRole(), session.get("role"));
                String fcmToken = firstNonEmpty(
                        session.get("fcm_token"),
                        session.get("firebase_token"),
                        session.get("token"),
                        session.get("device_token")
                );

                JSONObject payload = new JSONObject();
                payload.put("user_id", userId);
                payload.put("id", userId);
                payload.put("username", username);
                payload.put("role", role);
                payload.put("fcm_token", fcmToken);
                payload.put("token", fcmToken);
                payload.put("source", "android_profile_logout");

                URL url = new URL(LOGOUT_URL);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(TIMEOUT_MS);
                conn.setReadTimeout(TIMEOUT_MS);
                conn.setUseCaches(false);
                conn.setDoInput(true);
                conn.setDoOutput(true);
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("Cache-Control", "no-store");
                conn.setRequestProperty("X-Transiva-App", "Android-Native");

                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(conn.getOutputStream(), "UTF-8"));
                writer.write(payload.toString());
                writer.flush();
                writer.close();

                int code = conn.getResponseCode();
                InputStream stream = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream();
                response = readStream(stream);
                success = code >= 200 && code < 400;

            } catch (Exception e) {
                response = e.getMessage() == null ? "logout error" : e.getMessage();
            } finally {
                if (conn != null) conn.disconnect();
            }

            boolean finalSuccess = success;
            String finalResponse = response;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (callback != null) callback.onDone(finalSuccess, finalResponse);
            });
        }).start();
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String v : values) {
            if (v != null) {
                String clean = v.trim();
                if (clean.length() > 0 && !clean.equalsIgnoreCase("null") && !clean.equalsIgnoreCase("undefined")) {
                    return clean;
                }
            }
        }
        return "";
    }

    private static String readStream(InputStream stream) {
        try {
            if (stream == null) return "";
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) builder.append(line);
            reader.close();
            return builder.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
