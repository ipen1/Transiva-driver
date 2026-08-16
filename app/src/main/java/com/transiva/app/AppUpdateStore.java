package com.transiva.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Penyimpanan state update yang kecil dan persisten. */
public final class AppUpdateStore {
    private static final String PREF = "transiva_driver_update";
    private static final String K_LATEST = "latest_version";
    private static final String K_MIN = "minimum_version";
    private static final String K_NAME = "version_name";
    private static final String K_URL = "apk_url";
    private static final String K_SHA = "sha256";
    private static final String K_TITLE = "title";
    private static final String K_MESSAGE = "message";
    private static final String K_SIZE = "file_size";
    private static final String K_FORCE = "force_update";
    private static final String K_DOWNLOAD_ID = "download_id";
    private static final String K_DOWNLOAD_VERSION = "download_version";
    private static final String K_LAST_CHECK = "last_check";

    private AppUpdateStore() {}

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static void saveServerInfo(Context c, AppUpdateInfo i) {
        p(c).edit()
                .putInt(K_LATEST, i.versionCode)
                .putInt(K_MIN, i.minimumVersionCode)
                .putString(K_NAME, i.versionName)
                .putString(K_URL, i.apkUrl)
                .putString(K_SHA, i.sha256)
                .putString(K_TITLE, i.title)
                .putString(K_MESSAGE, i.message)
                .putLong(K_SIZE, i.fileSize)
                .putBoolean(K_FORCE, i.forceUpdate)
                .putLong(K_LAST_CHECK, System.currentTimeMillis())
                .apply();
    }

    public static AppUpdateInfo cachedInfo(Context c) {
        SharedPreferences s = p(c);
        int latest = s.getInt(K_LATEST, 0);
        if (latest <= 0) return null;
        try {
            org.json.JSONObject data = new org.json.JSONObject();
            data.put("version_code", latest);
            data.put("minimum_version_code", s.getInt(K_MIN, 0));
            data.put("version_name", s.getString(K_NAME, ""));
            data.put("apk_url", s.getString(K_URL, ""));
            data.put("sha256", s.getString(K_SHA, ""));
            data.put("title", s.getString(K_TITLE, "Pembaruan Transiva tersedia"));
            data.put("message", s.getString(K_MESSAGE, "Versi terbaru Transiva siap dipasang."));
            data.put("file_size_bytes", s.getLong(K_SIZE, 0L));
            data.put("force_update", s.getBoolean(K_FORCE, false));
            return AppUpdateInfo.fromJson(data);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static long lastCheck(Context c) { return p(c).getLong(K_LAST_CHECK, 0L); }

    public static void saveDownload(Context c, long id, int targetVersion) {
        p(c).edit().putLong(K_DOWNLOAD_ID, id).putInt(K_DOWNLOAD_VERSION, targetVersion).apply();
    }

    public static long downloadId(Context c) { return p(c).getLong(K_DOWNLOAD_ID, -1L); }
    public static int downloadVersion(Context c) { return p(c).getInt(K_DOWNLOAD_VERSION, 0); }

    public static void clearDownload(Context c) {
        p(c).edit().remove(K_DOWNLOAD_ID).remove(K_DOWNLOAD_VERSION).apply();
    }
}
