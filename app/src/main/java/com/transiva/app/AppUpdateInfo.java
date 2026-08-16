package com.transiva.app;

import org.json.JSONObject;

/** Kontrak versi aplikasi dari server Transiva. */
public final class AppUpdateInfo {
    public final int versionCode;
    public final int minimumVersionCode;
    public final String versionName;
    public final String title;
    public final String message;
    public final String apkUrl;
    public final String sha256;
    public final long fileSize;
    public final boolean forceUpdate;

    private AppUpdateInfo(int versionCode, int minimumVersionCode, String versionName,
                          String title, String message, String apkUrl, String sha256,
                          long fileSize, boolean forceUpdate) {
        this.versionCode = versionCode;
        this.minimumVersionCode = minimumVersionCode;
        this.versionName = versionName;
        this.title = title;
        this.message = message;
        this.apkUrl = apkUrl;
        this.sha256 = sha256;
        this.fileSize = fileSize;
        this.forceUpdate = forceUpdate;
    }

    public boolean isUpdateAvailable(int installedVersionCode) {
        return versionCode > installedVersionCode;
    }

    /**
     * Force berlaku jika versi terpasang berada di bawah minimum_version_code,
     * atau server memakai flag force_update untuk latest version.
     */
    public boolean isForceRequired(int installedVersionCode) {
        if (minimumVersionCode > 0 && installedVersionCode < minimumVersionCode) return true;
        return forceUpdate && versionCode > installedVersionCode;
    }

    public static AppUpdateInfo fromJson(JSONObject root) {
        JSONObject data = root.optJSONObject("data");
        if (data == null) data = root;

        int latest = data.optInt("version_code", data.optInt("versionCode", 0));
        int minimum = data.optInt("minimum_version_code",
                data.optInt("minimumVersionCode", 0));

        // Backward compatible: server lama hanya punya force_update + version_code.
        if (minimum <= 0 && data.optBoolean("force_update",
                data.optBoolean("forceUpdate", false))) {
            minimum = latest;
        }

        long fileSize = data.optLong("file_size_bytes",
                data.optLong("fileSizeBytes", 0L));
        if (fileSize <= 0L) {
            // file_size lama di server Transiva memakai MB (double), bukan bytes.
            double legacyMb = data.optDouble("file_size", data.optDouble("fileSize", 0d));
            if (legacyMb > 0d) fileSize = (long) (legacyMb * 1024d * 1024d);
        }

        return new AppUpdateInfo(
                latest,
                minimum,
                data.optString("version_name", data.optString("versionName", "")),
                data.optString("title", "Pembaruan Transiva tersedia"),
                data.optString("message", "Versi terbaru Transiva siap dipasang."),
                data.optString("apk_url", data.optString("apkUrl", "")),
                data.optString("sha256", ""),
                fileSize,
                data.optBoolean("force_update", data.optBoolean("forceUpdate", false))
        );
    }
}
