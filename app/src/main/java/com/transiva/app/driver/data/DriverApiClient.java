package com.transiva.app.driver.data;

import android.content.Context;
import android.util.Log;

import com.transiva.app.DeviceIdentityManager;
import com.transiva.app.DriverNetworkExecutor;
import com.transiva.app.DriverCircuitBreaker;
import com.transiva.app.DriverRetryPolicy;
import com.transiva.app.DriverTlsPinning;
import com.transiva.app.SessionManager;
import com.transiva.app.TransivaDriverCrashReporter;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;

public final class DriverApiClient {
    public static final class Result {
        public final int status;
        public final JSONObject body;
        Result(int status, JSONObject body) { this.status = status; this.body = body; }
    }

    public static final class ApiException extends Exception {
        public final int status;
        public final String code;
        ApiException(int status, String code, String message, Throwable cause) {
            super(message, cause); this.status = status; this.code = code;
        }
    }

    private static final String TAG = "DriverApiClient";
    private static final String BASE_URL = "https://transiva.my.id/server/";
    private static final int MAX_ATTEMPTS = 4;
    private final SessionManager session;
    private final Context appContext;

    public DriverApiClient(SessionManager session) {
        this.session = session;
        this.appContext = session.getContext().getApplicationContext();
    }

    public ExecutorService executor() { return DriverNetworkExecutor.executor(); }
    public Result get(String endpoint) throws ApiException { return requestWithRetry("GET", endpoint, null); }
    public Result post(String endpoint, JSONObject payload) throws ApiException { return requestWithRetry("POST", endpoint, payload); }

    private Result requestWithRetry(String method, String endpoint, JSONObject payload) throws ApiException {
        if (!DriverCircuitBreaker.allowRequest()) {
            throw new ApiException(503, "CIRCUIT_OPEN",
                    "Server sedang dalam masa pemulihan. Coba lagi beberapa detik.", null);
        }

        ApiException last = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                Result result = requestOnce(method, endpoint, payload);
                DriverCircuitBreaker.onSuccess();
                return result;
            } catch (ApiException error) {
                last = error;
                DriverCircuitBreaker.onFailure(error.status);

                int retryAfter = error.status == 429 ? parseRetryAfter(error.getMessage()) : 0;
                long delay = DriverRetryPolicy.delayFor(error.status, retryAfter, attempt);
                boolean retryable = error.status == 429
                        || ("GET".equals(method) && (error.status >= 500 || error.status == 0));

                // POST lokasi/status tidak diulang otomatis agar transaksi tidak terduplikasi.
                // Siklus lokasi berikutnya akan mencoba lagi setelah backoff/circuit pulih.
                if (!retryable || attempt + 1 >= MAX_ATTEMPTS) throw error;

                try {
                    Thread.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw error;
                }
            }
        }
        throw last == null ? new ApiException(0, "NETWORK_ERROR", "Permintaan gagal.", null) : last;
    }

    private Result requestOnce(String method, String endpoint, JSONObject payload) throws ApiException {
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
            if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("X-Device-UUID", DeviceIdentityManager.getInstallationUuid(appContext));
            connection.setRequestProperty("X-App-Scope", "driver");

            if ("POST".equals(method)) {
                connection.setDoOutput(true);
                try (BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(connection.getOutputStream(), StandardCharsets.UTF_8))) {
                    writer.write(payload == null ? "{}" : payload.toString());
                }
            }

            int status = connection.getResponseCode();
            if (connection instanceof HttpsURLConnection) {
                DriverTlsPinning.verify(appContext, (HttpsURLConnection) connection);
            }
            TransivaDriverCrashReporter.http(endpoint, status);
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String raw = read(stream);
            JSONObject body;
            try {
                body = new JSONObject(raw == null || raw.trim().isEmpty() ? "{}" : raw);
            } catch (Exception parse) {
                throw new ApiException(status, "INVALID_JSON", "Respons server tidak valid.", parse);
            }
            if (status < 200 || status >= 300 || !body.optBoolean("success", false)) {
                String message = body.optString("message", status == 429
                        ? "Terlalu banyak permintaan." : "Permintaan gagal.");
                int retryAfter = body.optInt("retry_after", 0);
                if (retryAfter > 0) message = message + " retry_after=" + retryAfter;
                throw new ApiException(status,
                        body.optString("code", status == 401 ? "UNAUTHORIZED" : "API_ERROR"),
                        message, null);
            }
            return new Result(status, body);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            Log.e(TAG, method + " " + endpoint + " gagal", e);
            TransivaDriverCrashReporter.nonFatal("driver_api_" + endpoint, e);

            String code = "NETWORK_ERROR";
            String message = "Tidak dapat terhubung ke server.";
            if (e instanceof UnknownHostException) {
                code = "DNS_ERROR";
                message = "Alamat server tidak dapat ditemukan. Periksa internet atau DNS.";
            } else if (e instanceof SocketTimeoutException) {
                code = "NETWORK_TIMEOUT";
                message = "Server terlalu lama merespons. Silakan coba lagi.";
            } else if (e instanceof SSLException) {
                code = "TLS_ERROR";
                message = "Koneksi HTTPS ke server gagal.";
            }
            throw new ApiException(0, code, message, e);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    public void shutdown() { /* shared executor is app-wide; do not shut it down here */ }

    private static int parseRetryAfter(String message) {
        if (message == null) return 0;
        int i = message.indexOf("retry_after=");
        if (i < 0) return 0;
        try { return Integer.parseInt(message.substring(i + 12).replaceAll("[^0-9].*$", "")); }
        catch (Exception ignored) { return 0; }
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
    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
