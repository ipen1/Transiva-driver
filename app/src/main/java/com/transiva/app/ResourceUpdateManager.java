package com.transiva.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
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
 * MLBB-style Transiva resource updater.
 *
 * Only data/resources are updated (images/config/json/audio/etc). Executable Android
 * code, manifest, SDK and permissions remain APK updates. Package promotion is atomic:
 * download -> SHA-256 -> staging extract -> resource.json -> active swap -> rollback.
 */
public final class ResourceUpdateManager {
    public static final String ENDPOINT = "https://transiva.my.id/server/driver_resource_manifest.php";
    private static final String PREF = "transiva_resource_update";
    private static final String K_VERSION = "active_version";
    private static final String K_LAST_CHECK = "last_check";
    private static final long CHECK_INTERVAL_MS = 30L * 60L * 1000L;
    private static final int CONNECT_TIMEOUT_MS = 12000;
    private static final int READ_TIMEOUT_MS = 45000;
    private static final long MAX_PACKAGE_BYTES = 120L * 1024L * 1024L;
    private static final long MAX_EXTRACTED_BYTES = 240L * 1024L * 1024L;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    public enum Stage { CHECKING, UPDATE_FOUND, DOWNLOADING, VERIFYING, INSTALLING, COMPLETE, NO_UPDATE, ERROR }

    public interface Listener {
        void onProgress(Stage stage, int percent, long downloadedBytes, long totalBytes, int targetVersion, String message);
        void onFinished(boolean updated, int activeVersion);
        void onError(String message, boolean canContinue);
    }

    private ResourceUpdateManager() {}

    /** Non-blocking periodic updater for normal foreground/background use. */
    public static void checkInBackground(Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        SharedPreferences p = app.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (now - p.getLong(K_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return;
        p.edit().putLong(K_LAST_CHECK, now).apply();
        IO.execute(() -> {
            try { execute(app, null); } catch (Throwable ignored) {}
        });
    }

    /** Interactive startup updater. Callback is invoked from worker thread. */
    public static void checkInteractive(Context context, Listener listener) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        IO.execute(() -> {
            try { execute(app, listener); }
            catch (Throwable t) {
                if (listener != null) listener.onError(safeMessage(t), true);
            }
        });
    }

    private static void execute(Context app, Listener l) throws Exception {
        emit(l, Stage.CHECKING, 0, 0, 0, 0, "Memeriksa data terbaru...");
        int installed = AppUpdateClient.installedVersionCode(app);
        int currentResource = activeVersion(app);
        ManifestInfo m = fetchManifest(installed, currentResource);
        if (m == null || m.version <= currentResource || m.version <= 0 || installed < m.minAppVersionCode) {
            emit(l, Stage.NO_UPDATE, 100, 0, 0, currentResource, "Data Transiva sudah terbaru");
            if (l != null) l.onFinished(false, currentResource);
            return;
        }

        emit(l, Stage.UPDATE_FOUND, 0, existingPartSize(app, m.version), m.size, m.version,
                "Pembaruan data v" + m.version + " tersedia");

        File base = baseDir(app);
        if (!base.exists() && !base.mkdirs()) throw new IllegalStateException("Folder update tidak dapat dibuat");
        File part = new File(base, "download-v" + m.version + ".zip.part");
        downloadResume(m.url, part, m.size, m.version, l);

        emit(l, Stage.VERIFYING, 100, m.size, m.size, m.version, "Memverifikasi pembaruan...");
        if (!m.sha256.equals(sha256(part))) {
            part.delete();
            throw new SecurityException("Verifikasi SHA-256 gagal. Paket dibuang.");
        }

        emit(l, Stage.INSTALLING, 100, m.size, m.size, m.version, "Memasang data terbaru...");
        File staging = new File(base, "staging-v" + m.version);
        deleteTree(staging);
        if (!staging.mkdirs()) throw new IllegalStateException("Folder staging tidak dapat dibuat");
        try {
            unzipSafe(part, staging);
            File marker = new File(staging, "resource.json");
            if (!marker.isFile()) throw new SecurityException("resource.json tidak ditemukan");
            JSONObject markerJson = new JSONObject(readString(new FileInputStream(marker), 128 * 1024));
            if (markerJson.optInt("version", -1) != m.version) throw new SecurityException("Versi paket tidak cocok");
            promote(app, staging, m.version);
            part.delete();
        } catch (Exception t) {
            deleteTree(staging);
            throw t;
        }

        emit(l, Stage.COMPLETE, 100, m.size, m.size, m.version, "Pembaruan selesai");
        if (l != null) l.onFinished(true, m.version);
    }

    private static ManifestInfo fetchManifest(int installed, int currentResource) throws Exception {
        URL u = new URL(ENDPOINT + "?role=driver&resource_version=" + currentResource + "&app_version_code=" + installed);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setRequestMethod("GET");
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Cache-Control", "no-cache");
        try {
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new IllegalStateException("Server resource HTTP " + code);
            JSONObject root = new JSONObject(readString(c.getInputStream(), 512 * 1024));
            if (!root.optBoolean("success", true)) throw new IllegalStateException(root.optString("message", "Manifest resource ditolak server"));
            JSONObject data = root.optJSONObject("data");
            if (data == null) data = root;
            ManifestInfo m = new ManifestInfo();
            m.version = data.optInt("version", 0);
            m.minAppVersionCode = data.optInt("min_app_version_code", 0);
            m.url = data.optString("url", "").trim();
            m.sha256 = data.optString("sha256", "").trim().toLowerCase(Locale.US);
            m.size = data.optLong("size", 0L);
            if (m.version <= currentResource) return m;
            if (!m.url.startsWith("https://transiva.my.id/")) throw new SecurityException("Host resource tidak diizinkan");
            if (!m.sha256.matches("[0-9a-f]{64}")) throw new SecurityException("SHA-256 manifest tidak valid");
            if (m.size < 1L || m.size > MAX_PACKAGE_BYTES) throw new SecurityException("Ukuran paket tidak valid");
            return m;
        } finally { c.disconnect(); }
    }

    private static long existingPartSize(Context app, int version) {
        File f = new File(baseDir(app), "download-v" + version + ".zip.part");
        return f.isFile() ? f.length() : 0L;
    }

    private static void downloadResume(String url, File out, long expectedSize, int version, Listener l) throws Exception {
        long existing = out.isFile() ? out.length() : 0L;
        if (existing < 0 || existing > expectedSize) { out.delete(); existing = 0L; }

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Accept-Encoding", "identity");
        if (existing > 0L) c.setRequestProperty("Range", "bytes=" + existing + "-");
        try {
            int code = c.getResponseCode();
            boolean append = existing > 0L && code == HttpURLConnection.HTTP_PARTIAL;
            if (existing > 0L && code == HttpURLConnection.HTTP_OK) {
                // Server ignores Range: safely restart from zero.
                existing = 0L;
                append = false;
            } else if (code / 100 != 2) {
                throw new IllegalStateException("Download resource HTTP " + code);
            }

            long total = existing;
            emitDownload(l, total, expectedSize, version);
            try (InputStream in = new BufferedInputStream(c.getInputStream());
                 BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(out, append))) {
                byte[] buf = new byte[32 * 1024];
                int n;
                long lastUi = 0L;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_PACKAGE_BYTES || total > expectedSize) throw new SecurityException("Paket melebihi ukuran manifest");
                    os.write(buf, 0, n);
                    long now = System.currentTimeMillis();
                    if (now - lastUi >= 90L || total == expectedSize) {
                        lastUi = now;
                        emitDownload(l, total, expectedSize, version);
                    }
                }
            }
            if (total != expectedSize) throw new IllegalStateException("Download belum lengkap (" + total + "/" + expectedSize + ")");
        } finally { c.disconnect(); }
    }

    private static void emitDownload(Listener l, long got, long total, int version) {
        int p = total > 0 ? (int)Math.max(0, Math.min(100, (got * 100L) / total)) : 0;
        emit(l, Stage.DOWNLOADING, p, got, total, version, "Mengunduh pembaruan terbaru...");
    }

    private static void promote(Context app, File staging, int version) throws Exception {
        File base = baseDir(app);
        File active = new File(base, "active");
        File old = new File(base, "old");
        deleteTree(old);
        if (active.exists() && !active.renameTo(old)) throw new IllegalStateException("Resource aktif tidak dapat dibackup");
        if (!staging.renameTo(active)) {
            if (old.exists()) old.renameTo(active);
            throw new IllegalStateException("Resource baru tidak dapat diaktifkan");
        }
        app.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt(K_VERSION, version).apply();
        deleteTree(old);
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

    private static void unzipSafe(File zip, File dest) throws Exception {
        String root = dest.getCanonicalPath() + File.separator;
        long total = 0L;
        int entries = 0;
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry e;
            byte[] buf = new byte[24 * 1024];
            while ((e = zis.getNextEntry()) != null) {
                if (++entries > 1500) throw new SecurityException("Terlalu banyak file di paket");
                File out = new File(dest, e.getName());
                if (!out.getCanonicalPath().startsWith(root)) throw new SecurityException("ZIP traversal terdeteksi");
                if (e.isDirectory()) { if (!out.exists() && !out.mkdirs()) throw new IllegalStateException("Gagal membuat folder"); continue; }
                File parent = out.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IllegalStateException("Gagal membuat folder");
                try (BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                    int n;
                    while ((n = zis.read(buf)) != -1) {
                        total += n;
                        if (total > MAX_EXTRACTED_BYTES) throw new SecurityException("Paket extract terlalu besar");
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
        try (InputStream source = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int n, total = 0;
            while ((n = source.read(b)) != -1) {
                total += n; if (total > maxBytes) throw new SecurityException("Response terlalu besar");
                out.write(b, 0, n);
            }
            return out.toString("UTF-8");
        }
    }

    private static void emit(Listener l, Stage s, int p, long got, long total, int v, String msg) {
        if (l != null) l.onProgress(s, p, got, total, v, msg);
    }

    private static String safeMessage(Throwable t) {
        String m = t == null ? "" : t.getMessage();
        if (m == null || m.trim().isEmpty()) return "Pembaruan data tidak dapat diselesaikan";
        return m.length() > 160 ? m.substring(0, 160) : m;
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) { File[] kids = f.listFiles(); if (kids != null) for (File k : kids) deleteTree(k); }
        try { f.delete(); } catch (Exception ignored) {}
    }

    private static final class ManifestInfo {
        int version;
        int minAppVersionCode;
        String url;
        String sha256;
        long size;
    }
}
