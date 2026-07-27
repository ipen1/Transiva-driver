package com.transiva.app;

import org.json.JSONObject;

/** Model informasi versi aplikasi dari server. */
public final class AppUpdateInfo {
    public final int versionCode;
    public final String versionName;
    public final String title;
    public final String message;
    public final String apkUrl;
    public final String sha256;
    public final long fileSize;
    public final boolean forceUpdate;

    private AppUpdateInfo(int versionCode, String versionName, String title,
                          String message, String apkUrl, String sha256,
                          long fileSize, boolean forceUpdate) {
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.title = title;
        this.message = message;
        this.apkUrl = apkUrl;
        this.sha256 = sha256;
        this.fileSize = fileSize;
        this.forceUpdate = forceUpdate;
    }

    public static AppUpdateInfo fromJson(JSONObject root) {
        JSONObject data = root.optJSONObject("data");
        if (data == null) data = root;
        return new AppUpdateInfo(
                data.optInt("version_code", data.optInt("versionCode", 0)),
                data.optString("version_name", data.optString("versionName", "")),
                data.optString("title", "Pembaruan Transiva tersedia"),
                data.optString("message", "Versi terbaru Transiva siap dipasang."),
                data.optString("apk_url", data.optString("apkUrl", "")),
                data.optString("sha256", ""),
                data.optLong("file_size", data.optLong("fileSize", 0L)),
                data.optBoolean("force_update", data.optBoolean("forceUpdate", false))
        );
    }
}
