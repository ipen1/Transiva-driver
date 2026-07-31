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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Pemeriksa update APK khusus Transiva Driver yang dihosting sendiri. */
public final class AppUpdateClient {
    public static final String UPDATE_ENDPOINT = "https://transiva.my.id/server/getVersion.php";
    private static final String APP_ROLE = "driver";
    private static final String DRIVER_PACKAGE = "com.transiva.driver";

    public interface Callback {
        void onResult(AppUpdateInfo info, boolean updateAvailable);
        void onError(String message);
    }

    private AppUpdateClient() {}

    public static void check(Context context, Callback callback) {
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                String requestUrl = UPDATE_ENDPOINT
                        + "?app=" + URLEncoder.encode(APP_ROLE, "UTF-8")
                        + "&_=" + System.currentTimeMillis();

                connection = (HttpURLConnection) new URL(requestUrl).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(20000);
                connection.setUseCaches(false);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Cache-Control", "no-cache");

                int code = connection.getResponseCode();
                InputStream stream = code >= 200 && code < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                String response = read(stream);
                if (code < 200 || code >= 300) {
                    throw new IllegalStateException("Server merespons " + code);
                }

                JSONObject root = new JSONObject(response);
                if (!root.optBoolean("success", true)) {
                    throw new IllegalStateException(root.optString(
                            "message", "Informasi pembaruan tidak tersedia."));
                }

                JSONObject data = root.optJSONObject("data");
                if (data == null) data = root;

                String serverApp = data.optString("app", "").trim();
                String serverPackage = data.optString("package_name", "").trim();
                String installedPackage = context.getPackageName();

                if (!APP_ROLE.equalsIgnoreCase(serverApp)) {
                    throw new SecurityException(
                            "Server mengirim pembaruan bukan untuk aplikasi Driver.");
                }
                if (!DRIVER_PACKAGE.equals(installedPackage)) {
                    throw new SecurityException(
                            "Identitas paket aplikasi Driver tidak sesuai: " + installedPackage);
                }
                if (!DRIVER_PACKAGE.equals(serverPackage)) {
                    throw new SecurityException(
                            "Paket APK dari server bukan paket Transiva Driver.");
                }

                AppUpdateInfo info = AppUpdateInfo.fromJson(root);
                if (info.versionCode <= 0 || info.apkUrl.trim().isEmpty()) {
                    throw new IllegalStateException("Konfigurasi update server belum lengkap.");
                }

                String apkUrlLower = info.apkUrl.toLowerCase();
                if (apkUrlLower.contains("customer") || apkUrlLower.contains("custumer")
                        || apkUrlLower.contains("merchant")) {
                    throw new SecurityException(
                            "URL pembaruan bukan APK Transiva Driver. Download dibatalkan.");
                }

                callback.onResult(info, info.versionCode > installedVersionCode(context));
            } catch (Exception e) {
                callback.onError(e.getMessage() == null
                        ? "Gagal memeriksa pembaruan Driver." : e.getMessage());
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "transiva-driver-update-check").start();
    }

    public static int installedVersionCode(Context context) throws Exception {
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return (int) info.getLongVersionCode();
        }
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
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }
}
