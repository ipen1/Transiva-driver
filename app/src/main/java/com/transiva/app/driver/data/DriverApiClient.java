package com.transiva.app.driver.data;

import android.util.Log;

import com.transiva.app.SessionManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DriverApiClient {

    public static final class Result {
        public final int status;
        public final JSONObject body;

        Result(int status, JSONObject body) {
            this.status = status;
            this.body = body;
        }
    }

    public static final class ApiException extends Exception {
        public final int status;
        public final String code;

        ApiException(int status, String code, String message, Throwable cause) {
            super(message, cause);
            this.status = status;
            this.code = code;
        }
    }

    private static final String TAG = "DriverApiClient";
    private static final String BASE_URL = "https://transiva.my.id/server/";
    private final SessionManager session;
    private final ExecutorService executor = Executors.newFixedThreadPool(3);

    public DriverApiClient(SessionManager session) {
        this.session = session;
    }

    public ExecutorService executor() {
        return executor;
    }

    public Result get(String endpoint) throws ApiException {
        return request("GET", endpoint, null);
    }

    public Result post(String endpoint, JSONObject payload) throws ApiException {
        return request("POST", endpoint, payload);
    }

    private Result request(String method, String endpoint, JSONObject payload) throws ApiException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(BASE_URL + endpoint).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setUseCaches(false);
            connection.setDoInput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("X-Transiva-Client", "Android-Native");

            String token = clean(session.getToken());
            if (!token.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }

            if ("POST".equals(method)) {
                connection.setDoOutput(true);
                try (BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(connection.getOutputStream(), StandardCharsets.UTF_8))) {
                    writer.write(payload == null ? "{}" : payload.toString());
                }
            }

            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();

            String raw = read(stream);
            JSONObject body;
            try {
                body = new JSONObject(raw == null || raw.trim().isEmpty() ? "{}" : raw);
            } catch (Exception parse) {
                throw new ApiException(status, "INVALID_JSON",
                        "Respons server tidak valid.", parse);
            }

            if (status < 200 || status >= 300 || !body.optBoolean("success", false)) {
                throw new ApiException(
                        status,
                        body.optString("code", status == 401 ? "UNAUTHORIZED" : "API_ERROR"),
                        body.optString("message", "Permintaan gagal."),
                        null
                );
            }

            return new Result(status, body);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            Log.e(TAG, method + " " + endpoint + " gagal", e);
            throw new ApiException(0, "NETWORK_ERROR",
                    "Tidak dapat terhubung ke server.", e);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private static String read(InputStream stream) throws Exception {
        if (stream == null) return "";
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
            return result.toString();
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
