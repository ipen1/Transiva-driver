package com.transiva.app;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Temporary remote diagnostics for WebRTC crash hunting. Remove after issue is solved. */
public final class RemoteWebRtcLog {
    private static final String ENDPOINT = "https://transiva.my.id/server/webrtc_debug_log.php";
    private static final String KEY = "x-5kcoQNGj8goHsDBf50hrjnJxvu6dzE7CpcFjgHjkU";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private RemoteWebRtcLog() {}

    public static void async(Context context, String callId, String kind, String message) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> send(app, callId, kind, message, 1800));
    }

    /**
     * Sends a breadcrumb and waits briefly. Use immediately before/after JNI/native WebRTC calls.
     * The short blocking wait is intentional in this temporary diagnostic build so a native
     * process death still leaves the last reached marker on the server.
     */
    public static void critical(Context context, String callId, String message) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        Thread t = new Thread(() -> send(app, callId, "CRITICAL", message, 1400), "wr-log-critical");
        try {
            t.start();
            t.join(1600L);
        } catch (Throwable ignored) {}
    }

    private static void send(Context context, String callId, String kind, String message, int timeout) {
        HttpURLConnection c = null;
        try {
            JSONObject j = new JSONObject();
            j.put("key", KEY);
            j.put("app", context.getPackageName());
            j.put("device", deviceId(context));
            j.put("call_id", clean(callId));
            j.put("kind", clean(kind));
            j.put("message", clean(message) + "\n" + deviceSummary());

            byte[] body = j.toString().getBytes(StandardCharsets.UTF_8);
            c = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(timeout);
            c.setReadTimeout(timeout);
            c.setUseCaches(false);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            c.setRequestProperty("Accept", "application/json");
            try (OutputStream out = c.getOutputStream()) { out.write(body); out.flush(); }
            c.getResponseCode();
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String deviceId(Context c) {
        try {
            String id = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (id != null && !id.trim().isEmpty()) return id.trim();
        } catch (Throwable ignored) {}
        return "unknown";
    }

    private static String deviceSummary() {
        return "device=" + clean(Build.MANUFACTURER) + " " + clean(Build.MODEL)
                + " android=" + Build.VERSION.RELEASE + " sdk=" + Build.VERSION.SDK_INT
                + " abi=" + (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?");
    }

    private static String clean(String s) { return s == null ? "" : s.trim(); }
}
