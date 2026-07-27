package com.transiva.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Pemeriksa update APK Transiva yang dihosting sendiri. */
public final class AppUpdateClient {
    public static final String UPDATE_ENDPOINT = "https://transiva.my.id/server/getVersion.php";

    public interface Callback {
        void onResult(AppUpdateInfo info, boolean updateAvailable);
        void onError(String message);
    }

    private AppUpdateClient() {}

    public static void check(Context context, Callback callback) {
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(UPDATE_ENDPOINT + "?t=" + System.currentTimeMillis()).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(20000);
                connection.setUseCaches(false);
                connection.setRequestProperty("Accept", "application/json");
                int code = connection.getResponseCode();
                InputStream stream = code >= 200 && code < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                String response = read(stream);
                if (code < 200 || code >= 300) throw new IllegalStateException("Server merespons " + code);
                JSONObject json = new JSONObject(response);
                if (!json.optBoolean("success", true)) {
                    throw new IllegalStateException(json.optString("message", "Informasi pembaruan tidak tersedia."));
                }
                AppUpdateInfo info = AppUpdateInfo.fromJson(json);
                if (info.versionCode <= 0 || info.apkUrl.trim().isEmpty()) {
                    throw new IllegalStateException("Konfigurasi update server belum lengkap.");
                }
                callback.onResult(info, info.versionCode > installedVersionCode(context));
            } catch (Exception e) {
                callback.onError(e.getMessage() == null ? "Gagal memeriksa pembaruan." : e.getMessage());
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "transiva-update-check").start();
    }

    public static int installedVersionCode(Context context) throws Exception {
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return (int) info.getLongVersionCode();
        return info.versionCode;
    }

    public static String installedVersionName(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName == null ? "-" : info.versionName;
        } catch (Exception ignored) {
            return "-";
        }
    }

    private static String read(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }
}
