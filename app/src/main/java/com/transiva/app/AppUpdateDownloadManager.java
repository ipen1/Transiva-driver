package com.transiva.app;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;

import java.io.File;

/** Download APK via Android DownloadManager agar tetap berjalan di background. */
public final class AppUpdateDownloadManager {
    public static final class State {
        public final int status;
        public final int reason;
        public final long downloaded;
        public final long total;
        public final File file;

        State(int status, int reason, long downloaded, long total, File file) {
            this.status = status;
            this.reason = reason;
            this.downloaded = downloaded;
            this.total = total;
            this.file = file;
        }

        public boolean running() {
            return status == DownloadManager.STATUS_PENDING
                    || status == DownloadManager.STATUS_RUNNING
                    || status == DownloadManager.STATUS_PAUSED;
        }
        public boolean complete() { return status == DownloadManager.STATUS_SUCCESSFUL; }
        public boolean failed() { return status == DownloadManager.STATUS_FAILED; }
    }

    private AppUpdateDownloadManager() {}

    public static synchronized long ensureDownload(Context context, AppUpdateInfo info) {
        Context app = context.getApplicationContext();
        int installed;
        try { installed = AppUpdateClient.installedVersionCode(app); }
        catch (Exception e) { installed = 0; }
        if (!info.isUpdateAvailable(installed)) return -1L;

        long existing = AppUpdateStore.downloadId(app);
        int existingVersion = AppUpdateStore.downloadVersion(app);
        if (existing > 0 && existingVersion == info.versionCode) {
            State state = query(app, existing);
            if (state != null && (state.running() || state.complete())) return existing;
            AppUpdateStore.clearDownload(app);
        }

        // Hapus state download versi lama agar tidak pernah dipasang.
        if (existing > 0) {
            try {
                DownloadManager old = (DownloadManager) app.getSystemService(Context.DOWNLOAD_SERVICE);
                if (old != null) old.remove(existing);
            } catch (Exception ignored) {}
            AppUpdateStore.clearDownload(app);
        }

        String filename = "transiva-driver-v" + info.versionCode + ".apk";
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(info.apkUrl));
        request.setTitle("Pembaruan Transiva Driver");
        request.setDescription("Mengunduh versi " + info.versionName);
        request.setMimeType("application/vnd.android.package-archive");
        request.setAllowedOverMetered(true);
        request.setAllowedOverRoaming(false);
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, filename);

        DownloadManager dm = (DownloadManager) app.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) throw new IllegalStateException("DownloadManager Android tidak tersedia.");
        long id = dm.enqueue(request);
        AppUpdateStore.saveDownload(app, id, info.versionCode);
        return id;
    }

    public static State current(Context context) {
        long id = AppUpdateStore.downloadId(context);
        return id > 0 ? query(context, id) : null;
    }

    public static State query(Context context, long id) {
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null || id <= 0) return null;
        Cursor c = null;
        try {
            c = dm.query(new DownloadManager.Query().setFilterById(id));
            if (c == null || !c.moveToFirst()) return null;
            int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            int reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
            long done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            long total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            String local = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
            File file = null;
            if (local != null && local.startsWith("file:")) file = new File(Uri.parse(local).getPath());
            return new State(status, reason, done, total, file);
        } catch (Exception ignored) {
            return null;
        } finally {
            if (c != null) c.close();
        }
    }
}
