package com.transiva.app;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Packs and artwork survive app upgrades. Files use immutable content hashes. */
final class StickerPackStore {
    private static final int IMAGE_LIMIT = 512 * 1024;
    private static final int CATALOG_LIMIT = 8 * 1024 * 1024;
    private static final Object METADATA_LOCK = new Object();
    private static final Object[] ASSET_LOCKS = new Object[16];
    private static final ThreadPoolExecutor WORK = new ThreadPoolExecutor(2, 2, 20,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(128));
    static { for (int i = 0; i < ASSET_LOCKS.length; i++) ASSET_LOCKS[i] = new Object(); }

    private final Context context;
    private final File root;

    StickerPackStore(Context context) {
        this.context = context.getApplicationContext();
        root = new File(this.context.getNoBackupFilesDir(), "sticker-packs-v1");
    }

    static boolean submit(Runnable action) {
        try {
            WORK.execute(action);
            return true;
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            return false;
        }
    }

    JSONArray catalog(boolean refresh) throws Exception {
        File file = new File(root, "catalog.json");
        JSONObject cached = readJson(file);
        if (!refresh) return cached == null ? null : cached.getJSONArray("packs");
        HttpURLConnection request = open("sticker_catalog.php");
        try {
            if (cached != null) request.setRequestProperty("If-None-Match", cached.optString("etag"));
            int status = request.getResponseCode();
            if (status == 304 && cached != null) return cached.getJSONArray("packs");
            if (status != 200) throw new IOException("Toko Stiker belum tersedia");
            JSONObject data = new JSONObject(new String(read(request.getInputStream(), CATALOG_LIMIT), StandardCharsets.UTF_8));
            JSONArray packs = data.getJSONArray("packs");
            data.put("etag", request.getHeaderField("ETag"));
            writeJson(file, data);
            return packs;
        } finally {
            request.disconnect();
        }
    }

    JSONObject installed(JSONObject pack) throws Exception {
        JSONObject local = readJson(packFile(pack));
        if (local != null) return local;
        if (!"travi-siva".equals(pack.optString("slug"))) return null;
        JSONArray items = pack.getJSONArray("items");
        JSONArray bundled = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (TraviSivaStickers.index(TraviSivaStickers.PREFIX + item.optString("id")) >= 0) {
                bundled.put(item);
            }
        }
        JSONObject result = new JSONObject(pack.toString()).put("items", bundled);
        if (bundled.length() != items.length()) result.put("revision", 0);
        return result;
    }

    void install(JSONObject pack, Progress progress) throws Exception {
        JSONArray items = pack.getJSONArray("items");
        if (items.length() < 1 || items.length() > 96) throw new IOException("Isi pack tidak valid");
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (TraviSivaStickers.index(TraviSivaStickers.PREFIX + item.getString("id")) < 0) {
                asset(item);
                writeJson(itemFile(item.getString("id")), item);
            }
            progress.update(i + 1, items.length());
        }
        writeJson(packFile(pack), pack);
    }

    File lookup(String id) throws Exception {
        File info = itemFile(id);
        JSONObject item = readJson(info);
        if (item == null) {
            HttpURLConnection request = open("sticker_catalog.php?id=" + id);
            try {
                if (request.getResponseCode() != 200) throw new IOException("Stiker belum tersedia");
                item = new JSONObject(new String(read(request.getInputStream(), 4096), StandardCharsets.UTF_8))
                        .getJSONObject("item");
                if (!id.equals(item.getString("id"))) throw new IOException("ID stiker tidak sesuai");
                writeJson(info, item);
            } finally {
                request.disconnect();
            }
        }
        return asset(item);
    }

    File asset(JSONObject item) throws Exception {
        String name = item.getString("asset");
        int expected = item.getInt("bytes");
        if (!name.matches("[a-f0-9]{64}\\.(webp|png)") || expected < 1 || expected > IMAGE_LIMIT) {
            throw new IOException("File stiker tidak valid");
        }
        File file = new File(root, name);
        synchronized (ASSET_LOCKS[(name.hashCode() & 0x7fffffff) % ASSET_LOCKS.length]) {
            if (file.isFile() && file.length() == expected) return file;
            HttpURLConnection request = open("sticker_asset.php?file=" + name);
            try {
                if (request.getResponseCode() != 200) throw new IOException("Gambar stiker belum tersedia");
                byte[] bytes = read(request.getInputStream(), expected);
                if (bytes.length != expected || !name.startsWith(digest(bytes) + ".")) {
                    throw new IOException("Gambar stiker tidak lengkap");
                }
                write(new AtomicFile(file), bytes);
                return file;
            } finally {
                request.disconnect();
            }
        }
    }

    private File packFile(JSONObject pack) throws Exception {
        String slug = pack.getString("slug");
        if (!slug.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || slug.length() > 48) throw new IOException("Kode pack tidak valid");
        return new File(root, "pack-" + slug + ".json");
    }

    private File itemFile(String id) throws IOException {
        if (!id.matches("sp_[a-f0-9]{24}")) throw new IOException("ID stiker tidak valid");
        return new File(root, id + ".json");
    }

    private HttpURLConnection open(String path) throws IOException {
        HttpURLConnection request = com.transiva.app.driver.data.DriverConnectionRepository.open(DriverApiConfig.endpoint(path));
        request.setConnectTimeout(10000);
        request.setReadTimeout(15000);
        request.setInstanceFollowRedirects(false);
        return request;
    }

    private static JSONObject readJson(File path) throws Exception {
        synchronized (METADATA_LOCK) {
            AtomicFile file = new AtomicFile(path);
            try { return new JSONObject(new String(read(file.openRead(), CATALOG_LIMIT), StandardCharsets.UTF_8)); }
            catch (java.io.FileNotFoundException missing) { return null; }
        }
    }

    private static void writeJson(File file, JSONObject json) throws IOException {
        synchronized (METADATA_LOCK) {
            write(new AtomicFile(file), json.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void write(AtomicFile file, byte[] bytes) throws IOException {
        File parent = file.getBaseFile().getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory())) {
            throw new IOException("Penyimpanan stiker belum tersedia");
        }
        FileOutputStream output = file.startWrite();
        try {
            output.write(bytes);
            file.finishWrite(output);
        } catch (IOException error) {
            file.failWrite(output);
            throw error;
        }
    }

    private static byte[] read(InputStream input, int max) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (output.size() + count > max) throw new IOException("Respons stiker terlalu besar");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static String digest(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            hex.append(Character.forDigit((b >> 4) & 15, 16));
            hex.append(Character.forDigit(b & 15, 16));
        }
        return hex.toString();
    }

    interface Progress { void update(int done, int total); }
}
