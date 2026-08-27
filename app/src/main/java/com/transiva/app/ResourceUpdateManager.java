package com.transiva.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Transiva resource updater (MLBB-style data update, not executable-code update).
 *
 * Server package may contain images/, config/, json/, audio/, etc. The APK remains
 * installed; a successfully verified package is atomically promoted to active/.
 */
public final class ResourceUpdateManager {
    private static final String ENDPOINT = "https://transiva.my.id/server/driver_resource_manifest.php";
    private static final String PREF = "transiva_resource_update";
    private static final String K_VERSION = "active_version";
    private static final String K_LAST_CHECK = "last_check";
    private static final long CHECK_INTERVAL_MS = 30L * 60L * 1000L;
    private static final int CONNECT_TIMEOUT_MS = 12000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final long MAX_PACKAGE_BYTES = 80L * 1024L * 1024L;
    private static final long MAX_EXTRACTED_BYTES = 160L * 1024L * 1024L;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private ResourceUpdateManager() {}

    public static void checkInBackground(Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        SharedPreferences p = app.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (now - p.getLong(K_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return;
        p.edit().putLong(K_LAST_CHECK, now).apply();
        IO.execute(() -> {
            try { checkAndInstall(app); } catch (Throwable ignored) {}
        });
    }

    private static void checkAndInstall(Context app) throws Exception {
        int installed = AppUpdateClient.installedVersionCode(app);
        int currentResource = activeVersion(app);
        URL u = new URL(ENDPOINT + "?role=driver&resource_version=" + currentResource + "&app_version_code=" + installed);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setRequestMethod("GET");
        c.setRequestProperty("Accept", "application/json");
        try {
            if (c.getResponseCode() / 100 != 2) return;
            String body = readString(c.getInputStream(), 512 * 1024);
            JSONObject root = new JSONObject(body);
            JSONObject data = root.optJSONObject("data");
            if (data == null) data = root;
            if (!root.optBoolean("success", true)) return;
            int version = data.optInt("version", 0);
            int minApp = data.optInt("min_app_version_code", 0);
            String url = data.optString("url", "").trim();
            String sha = data.optString("sha256", "").trim().toLowerCase(Locale.US);
            long expectedSize = data.optLong("size", 0L);
            if (version <= currentResource || version <= 0 || installed < minApp || url.isEmpty()) return;
            if (!url.startsWith("https://transiva.my.id/")) return;
            if (!sha.matches("[0-9a-f]{64}")) return;
            if (expectedSize < 1L || expectedSize > MAX_PACKAGE_BYTES) return;

            File base = baseDir(app);
            if (!base.exists() && !base.mkdirs()) return;
            File zip = new File(base, "download-v" + version + ".zip.part");
            download(url, zip, expectedSize);
            if (!sha.equals(sha256(zip))) { zip.delete(); return; }

            File staging = new File(base, "staging-v" + version);
            deleteTree(staging);
            if (!staging.mkdirs()) { zip.delete(); return; }
            unzipSafe(zip, staging);
            zip.delete();

            File marker = new File(staging, "resource.json");
            if (!marker.isFile()) { deleteTree(staging); return; }
            JSONObject markerJson = new JSONObject(readString(new FileInputStream(marker), 128 * 1024));
            if (markerJson.optInt("version", -1) != version) { deleteTree(staging); return; }

            File active = new File(base, "active");
            File old = new File(base, "old");
            deleteTree(old);
            if (active.exists() && !active.renameTo(old)) { deleteTree(staging); return; }
            if (!staging.renameTo(active)) {
                if (old.exists()) old.renameTo(active);
                deleteTree(staging);
                return;
            }
            app.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt(K_VERSION, version).apply();
            deleteTree(old);
        } finally {
            c.disconnect();
        }
    }

    public static int activeVersion(Context context) {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(K_VERSION, 0);
    }

    public static File activeFile(Context context, String relativePath) {
        if (context == null || relativePath == null) return null;
        try {
            File active = new File(baseDir(context), "active");
            File target = new File(active, relativePath);
            String root = active.getCanonicalPath() + File.separator;
            if (!target.getCanonicalPath().startsWith(root)) return null;
            return target.isFile() ? target : null;
        } catch (Exception ignored) { return null; }
    }

    public static Bitmap loadBitmapOverride(Context context, String relativePath) {
        File f = activeFile(context, relativePath);
        if (f == null || f.length() <= 0 || f.length() > 8L * 1024L * 1024L) return null;
        try { return BitmapFactory.decodeFile(f.getAbsolutePath()); }
        catch (Throwable ignored) { return null; }
    }

    public static JSONObject loadJsonOverride(Context context, String relativePath) {
        File f = activeFile(context, relativePath);
        if (f == null || f.length() > 512L * 1024L) return null;
        try { return new JSONObject(readString(new FileInputStream(f), 512 * 1024)); }
        catch (Throwable ignored) { return null; }
    }

    private static File baseDir(Context c) { return new File(c.getFilesDir(), "resource_update"); }

    private static void download(String url, File out, long expectedSize) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setInstanceFollowRedirects(false);
        try {
            if (c.getResponseCode() / 100 != 2) throw new IllegalStateException("HTTP " + c.getResponseCode());
            long declared = c.getContentLengthLong();
            if (declared > MAX_PACKAGE_BYTES || (declared > 0 && declared != expectedSize)) throw new SecurityException("size mismatch");
            long total = 0L;
            try (InputStream in = new BufferedInputStream(c.getInputStream());
                 BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                byte[] buf = new byte[32 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_PACKAGE_BYTES || total > expectedSize) throw new SecurityException("package too large");
                    os.write(buf, 0, n);
                }
            }
            if (total != expectedSize) throw new SecurityException("download incomplete");
        } finally { c.disconnect(); }
    }

    private static void unzipSafe(File zip, File dest) throws Exception {
        String root = dest.getCanonicalPath() + File.separator;
        long total = 0L;
        int entries = 0;
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry e;
            byte[] buf = new byte[24 * 1024];
            while ((e = zis.getNextEntry()) != null) {
                if (++entries > 1200) throw new SecurityException("too many files");
                File out = new File(dest, e.getName());
                if (!out.getCanonicalPath().startsWith(root)) throw new SecurityException("zip traversal");
                if (e.isDirectory()) { if (!out.exists() && !out.mkdirs()) throw new IllegalStateException("mkdir"); continue; }
                File parent = out.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IllegalStateException("mkdir");
                try (BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                    int n;
                    while ((n = zis.read(buf)) != -1) {
                        total += n;
                        if (total > MAX_EXTRACTED_BYTES) throw new SecurityException("expanded package too large");
                        os.write(buf, 0, n);
                    }
                }
            }
        }
    }

    private static String sha256(File f) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
            byte[] buf = new byte[32 * 1024]; int n;
            while ((n = in.read(buf)) != -1) d.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : d.digest()) sb.append(String.format(Locale.US, "%02x", b & 0xff));
        return sb.toString();
    }

    private static String readString(InputStream in, int maxBytes) throws Exception {
        try (InputStream src = in) {
            byte[] buf = new byte[8192]; int n, total = 0;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            while ((n = src.read(buf)) != -1) {
                total += n; if (total > maxBytes) throw new SecurityException("response too large");
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        }
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) { File[] kids = f.listFiles(); if (kids != null) for (File k : kids) deleteTree(k); }
        try { f.delete(); } catch (Exception ignored) {}
    }
}
