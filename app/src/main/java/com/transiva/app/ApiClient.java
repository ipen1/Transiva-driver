package com.transiva.app;

import android.app.Activity;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;

public class ApiClient {

    private final Activity activity;
    private final WebView webView;
    private final Handler mainHandler;

    private static final String CHANNEL_NAME = "TransivaNative";
    private static final String BASE_URL = "https://transiva.my.id/";

    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 25000;

    public ApiClient(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    @JavascriptInterface
    public String getBaseUrl() {
        return BASE_URL;
    }

    @JavascriptInterface
    public void get(String path, String callbackId) {
        request("GET", path, "", callbackId);
    }

    @JavascriptInterface
    public void post(String path, String jsonBody, String callbackId) {
        request("POST", path, jsonBody, callbackId);
    }

    @JavascriptInterface
    public void request(
            final String method,
            final String path,
            final String jsonBody,
            final String callbackId
    ) {
        new Thread(() -> {

            HttpURLConnection conn = null;

            try {
                String cleanMethod = safe(method, "GET").toUpperCase();
                String cleanPath = cleanPath(path);
                String fullUrl = BASE_URL + cleanPath;

                URL url = new URL(fullUrl);

                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT);
                conn.setReadTimeout(READ_TIMEOUT);
                conn.setUseCaches(false);
                conn.setDoInput(true);

                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("Cache-Control", "no-store");
                conn.setRequestProperty("X-Transiva-Channel", CHANNEL_NAME);
                conn.setRequestProperty("X-Transiva-App", "Android-Hybrid");
                conn.setRequestProperty("X-Android-SDK", String.valueOf(Build.VERSION.SDK_INT));

                String sessionToken = new SessionManager(activity).getToken().trim();
                if (!sessionToken.isEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer " + sessionToken);
                    conn.setRequestProperty("X-Device-UUID", DeviceIdentityManager.getInstallationUuid(activity));
                }

                if ("POST".equals(cleanMethod)) {
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);

                    BufferedWriter writer = new BufferedWriter(
                            new OutputStreamWriter(conn.getOutputStream(), "UTF-8")
                    );

                    writer.write(safeJsonBody(jsonBody));
                    writer.flush();
                    writer.close();

                } else {
                    conn.setRequestMethod("GET");
                }

                int status = conn.getResponseCode();

                InputStream stream =
                        status >= 200 && status < 400
                                ? conn.getInputStream()
                                : conn.getErrorStream();

                String raw = readStream(stream);

                JSONObject result = new JSONObject();
                result.put("success", status >= 200 && status < 400);
                result.put("http_status", status);
                result.put("method", cleanMethod);
                result.put("url", fullUrl);
                result.put("path", cleanPath);
                result.put("callbackId", safe(callbackId, ""));
                result.put("is_json", isJson(raw));
                result.put("response", parseAny(raw));

                if (!isJson(raw)) {
                    result.put("message", shortText(cleanServerText(raw)));
                    result.put("raw_response", shortText(cleanServerText(raw)));
                }

                sendToWeb("api_response", result.toString());

            } catch (Exception e) {
                sendToWeb(
                        "api_error",
                        makeError("request", e, callbackId)
                );
            } finally {
                try {
                    if (conn != null) conn.disconnect();
                } catch (Exception ignored) {}
            }

        }).start();
    }

    private String cleanPath(String path) {
        String p = safe(path, "").trim();

        if (p.startsWith("https://transiva.my.id/")) {
            p = p.replace("https://transiva.my.id/", "");
        }

        if (p.startsWith("http://transiva.my.id/")) {
            p = p.replace("http://transiva.my.id/", "");
        }

        while (p.startsWith("/")) {
            p = p.substring(1);
        }

        return p;
    }

    private String safeJsonBody(String body) {
        try {
            if (body == null || body.trim().isEmpty()) {
                return "{}";
            }

            new JSONObject(body);
            return body;

        } catch (Exception e) {
            return "{}";
        }
    }

    private Object parseAny(String raw) {
        try {
            if (raw == null || raw.trim().isEmpty()) {
                return new JSONObject();
            }

            String text = raw.trim();

            if (text.startsWith("{")) {
                return new JSONObject(text);
            }

            if (text.startsWith("[")) {
                return new JSONArray(text);
            }

            JSONObject obj = new JSONObject();
            obj.put("raw", cleanServerText(text));
            return obj;

        } catch (Exception e) {
            try {
                JSONObject obj = new JSONObject();
                obj.put("raw", cleanServerText(raw));
                return obj;
            } catch (Exception ignored) {
                return new JSONObject();
            }
        }
    }

    private boolean isJson(String raw) {
        try {
            if (raw == null) return false;

            String text = raw.trim();

            if (text.startsWith("{")) {
                new JSONObject(text);
                return true;
            }

            if (text.startsWith("[")) {
                new JSONArray(text);
                return true;
            }

            return false;

        } catch (Exception e) {
            return false;
        }
    }

    private String readStream(InputStream stream) {
        try {
            if (stream == null) return "";

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, "UTF-8")
            );

            StringBuilder builder = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }

            reader.close();
            return builder.toString();

        } catch (Exception e) {
            return "";
        }
    }

    private void sendToWeb(final String eventName, final String jsonData) {
        runOnUi(() -> {
            try {
                if (webView == null) return;

                String js =
                        "window.dispatchEvent(new CustomEvent('transiva-native', {" +
                                "detail: {" +
                                "channel: '" + CHANNEL_NAME + "'," +
                                "event: '" + escapeJs(eventName) + "'," +
                                "data: " + safeJsonData(jsonData) +
                                "}" +
                                "}));";

                webView.evaluateJavascript(js, null);

            } catch (Exception ignored) {}
        });
    }

    private String safeJsonData(String data) {
        try {
            if (data == null || data.trim().isEmpty()) return "{}";
            new JSONObject(data);
            return data;
        } catch (Exception e) {
            return "{}";
        }
    }

    private String makeError(String from, Exception e, String callbackId) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("success", false);
            obj.put("from", from);
            obj.put("callbackId", safe(callbackId, ""));
            obj.put("message", e == null ? "Unknown error" : e.getMessage());
            obj.put("time", System.currentTimeMillis());
            return obj.toString();
        } catch (Exception ex) {
            return "{\"success\":false}";
        }
    }

    private String cleanServerText(String text) {
        return safe(text, "")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p>", "\n")
                .replaceAll("<[^>]+>", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String shortText(String text) {
        text = safe(text, "");
        if (text.length() > 350) {
            return text.substring(0, 350) + "...";
        }
        return text;
    }

    private String safe(String value, String fallback) {
        if (value == null) return fallback;
        return value;
    }

    private String escapeJs(String value) {
        if (value == null) return "";

        return value
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    private void runOnUi(Runnable runnable) {
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                runnable.run();
            } else {
                mainHandler.post(runnable);
            }
        } catch (Exception ignored) {}
    }
            }
