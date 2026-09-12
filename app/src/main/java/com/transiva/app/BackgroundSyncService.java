package com.transiva.app;

import android.annotation.SuppressLint;
import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
public class BackgroundSyncService extends BackgroundSyncServiceLayer1 {

    @Override
    public void onCreate() {
        super.onCreate();

        handler = new Handler(Looper.getMainLooper());
        sessionManager = new SessionManager(this);

        createNotificationChannel();
    }

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId
    ) {
        String action =
                intent == null || intent.getAction() == null
                        ? ACTION_START
                        : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopSyncService("Service dihentikan");
            return START_NOT_STICKY;
        }

        /*
         * FIX TERPENTING:
         * Jangan startForeground sebelum session valid.
         * Dengan ini status "Sinkronisasi Transiva aktif" tidak muncul
         * saat user belum login / session kosong.
         */
        if (!hasValidLoginSession()) {
            stopSyncServiceSilent("Belum login");
            return START_NOT_STICKY;
        }

        ensureForeground();

        if (ACTION_SYNC_NOW.equals(action)) {
            runSyncOnce();
        } else {
            startLoop();
        }

        return START_STICKY;
    }

    protected void ensureForeground() {
        if (foregroundStarted) {
            return;
        }

        try {
            startForeground(
                    NOTIFICATION_ID,
                    buildNotification("Sinkronisasi Transiva aktif")
            );

            foregroundStarted = true;

        } catch (Exception ignored) {}
    }

    protected void startLoop() {
        if (isRunning) {
            return;
        }

        if (!hasValidLoginSession()) {
            stopSyncServiceSilent("Belum login");
            return;
        }

        isRunning = true;

        try {
            if (handler != null) {
                handler.removeCallbacks(syncRunnable);
                handler.post(syncRunnable);
            }
        } catch (Exception ignored) {}
    }

    protected void runSyncOnce() {
        DriverNetworkExecutor.execute(() -> {
            try {
                if (!hasValidLoginSession()) {
                    stopSyncServiceSilent("Belum login");
                    return;
                }

                doSync();

                /*
                 * ACTION_SYNC_NOW hanya sync sekali.
                 * Kalau sebelumnya service belum running, tidak perlu loop.
                 */
                if (!isRunning) {
                    stopSyncServiceSilent("Sync sekali selesai");
                }

            } catch (Exception ignored) {}
        });
    }

    protected void doSync() {
        if (!hasValidLoginSession()) {
            stopSyncServiceSilent("Belum login");
            return;
        }

        try {
            sessionManager.put(
                    "background_sync_running",
                    "1"
            );

            sessionManager.put(
                    "background_sync_last_attempt",
                    String.valueOf(System.currentTimeMillis())
            );

            if (!isOnline()) {
                failCount++;

                sessionManager.put(
                        "background_sync_status",
                        "offline"
                );

                sessionManager.put(
                        "background_sync_message",
                        "Internet offline"
                );

                return;
            }

            String role =
                    safe(sessionManager.getRole())
                            .toLowerCase();

            if (role.contains("driver")) {
                syncDriverLocation();
                return;
            }

            /*
             * Untuk role customer / merchant / admin,
             * background sync driver tidak perlu berjalan.
             */
            failCount = 0;

            sessionManager.put(
                    "background_sync_status",
                    "idle"
            );

            sessionManager.put(
                    "background_sync_message",
                    "Role bukan driver"
            );

            stopSyncServiceSilent("Role bukan driver");

        } catch (Exception e) {
            failCount++;

            try {
                sessionManager.put(
                        "background_sync_status",
                        "error"
                );

                sessionManager.put(
                        "background_sync_message",
                        safe(e.getMessage())
                );

                sessionManager.put(
                        "background_sync_error_at",
                        String.valueOf(System.currentTimeMillis())
                );
            } catch (Exception ignored) {}
        }
    }

    protected void syncDriverLocation() {
        if (!hasValidLoginSession()) {
            stopSyncServiceSilent("Belum login");
            return;
        }

        try {
            String username = safe(sessionManager.getUsername());

            if (username.isEmpty()) {
                stopSyncServiceSilent("Username kosong");
                return;
            }

            String latitude =
                    safe(sessionManager.get("last_latitude"));

            String longitude =
                    safe(sessionManager.get("last_longitude"));

            if (latitude.isEmpty() || longitude.isEmpty()) {
                Location lastKnown = getQuickLastKnownLocation();

                if (lastKnown != null) {
                    latitude =
                            String.valueOf(lastKnown.getLatitude());

                    longitude =
                            String.valueOf(lastKnown.getLongitude());

                    sessionManager.saveLastLocation(
                            latitude,
                            longitude
                    );
                }
            }

            if (latitude.isEmpty() || longitude.isEmpty()) {
                failCount++;

                sessionManager.put(
                        "background_sync_status",
                        "waiting_location"
                );

                sessionManager.put(
                        "background_sync_message",
                        "Lokasi terakhir belum tersedia"
                );

                return;
            }

            JSONObject body = new JSONObject();

            body.put("username", username);
            body.put("order_id", sessionManager.get("current_order_id"));
            body.put("latitude", latitude);
            body.put("longitude", longitude);

            JSONObject extra = new JSONObject();

            extra.put("id", sessionManager.getId());
            extra.put("user_id", sessionManager.getUserId());
            extra.put("role", sessionManager.getRole());
            extra.put("source", "android_background_sync");
            extra.put("timestamp", System.currentTimeMillis());

            body.put("extra", extra);

            JSONObject response =
                    postJson(
                            UPDATE_DRIVER_LOCATION_ENDPOINT,
                            body
                    );

            boolean ok =
                    response.optBoolean("success", false);

            if (ok) {
                failCount = 0;

                sessionManager.put(
                        "background_sync_status",
                        "success"
                );

                sessionManager.put(
                        "background_sync_message",
                        response.optString(
                                "message",
                                "Sinkron berhasil"
                        )
                );

                sessionManager.put(
                        "background_sync_last_success",
                        String.valueOf(System.currentTimeMillis())
                );

                sessionManager.put(
                        "background_sync_last_response",
                        response.toString()
                );

            } else {
                failCount++;

                sessionManager.put(
                        "background_sync_status",
                        "server_failed"
                );

                sessionManager.put(
                        "background_sync_message",
                        response.optString(
                                "message",
                                "Server menolak sync"
                        )
                );

                sessionManager.put(
                        "background_sync_last_response",
                        response.toString()
                );
            }

        } catch (Exception e) {
            failCount++;

            try {
                sessionManager.put(
                        "background_sync_status",
                        "error"
                );

                sessionManager.put(
                        "background_sync_message",
                        safe(e.getMessage())
                );
            } catch (Exception ignored) {}
        }
    }

    protected long adaptiveSyncInterval() {
        try {
            String orderId = safe(sessionManager.get("current_order_id"));
            return orderId.isEmpty() ? IDLE_SYNC_INTERVAL : ACTIVE_SYNC_INTERVAL;
        } catch (Exception ignored) {
            return IDLE_SYNC_INTERVAL;
        }
    }

    protected boolean hasValidLoginSession() {
        try {
            if (sessionManager == null) {
                sessionManager = new SessionManager(this);
            }

            if (!sessionManager.isLoggedIn()) {
                return false;
            }

            String username =
                    safe(sessionManager.getUsername());

            String role =
                    safe(sessionManager.getRole());

            return !username.isEmpty()
                    && !role.isEmpty();

        } catch (Exception e) {
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    protected Location getQuickLastKnownLocation() {
        try {
            if (!hasLocationPermission()) {
                return null;
            }

            android.location.LocationManager lm =
                    (android.location.LocationManager)
                            getSystemService(LOCATION_SERVICE);

            if (lm == null) {
                return null;
            }

            Location gps = null;
            Location network = null;

            try {
                gps = lm.getLastKnownLocation(
                        android.location.LocationManager.GPS_PROVIDER
                );
            } catch (Exception ignored) {}

            try {
                network = lm.getLastKnownLocation(
                        android.location.LocationManager.NETWORK_PROVIDER
                );
            } catch (Exception ignored) {}

            if (gps != null && network != null) {
                return gps.getTime() >= network.getTime()
                        ? gps
                        : network;
            }

            if (gps != null) {
                return gps;
            }

            return network;

        } catch (Exception e) {
            return null;
        }
    }

    protected JSONObject postJson(
            String endpoint,
            JSONObject body
    ) {
        HttpURLConnection conn = null;

        try {
            URL url =
                    new URL(BASE_URL + cleanEndpoint(endpoint));

            conn =
                    DriverHttpTransport.open(url);

            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setUseCaches(false);
            conn.setDoInput(true);
            conn.setDoOutput(true);

            conn.setRequestProperty(
                    "Accept",
                    "application/json"
            );

            conn.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=UTF-8"
            );

            conn.setRequestProperty(
                    "X-Transiva-Channel",
                    "TransivaNative"
            );

            conn.setRequestProperty(
                    "X-Transiva-Client",
                    "Android-BackgroundSync"
            );

            String token = safe(sessionManager.getToken());
            if (!token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            conn.setRequestProperty("X-Device-UUID", DeviceIdentityManager.getInstallationUuid(this));
            conn.setRequestProperty("X-App-Scope", "driver");

            BufferedWriter writer =
                    new BufferedWriter(
                            new OutputStreamWriter(
                                    conn.getOutputStream(),
                                    "UTF-8"
                            )
                    );

            writer.write(
                    body == null ? "{}" : body.toString()
            );

            writer.flush();
            writer.close();

            int status =
                    conn.getResponseCode();

            InputStream stream =
                    status >= 200 && status < 400
                            ? conn.getInputStream()
                            : conn.getErrorStream();

            String raw =
                    readStream(stream);

            JSONObject result;

            try {
                result = new JSONObject(raw);
            } catch (Exception e) {
                result = new JSONObject();

                result.put(
                        "success",
                        false
                );

                result.put(
                        "message",
                        raw == null || raw.isEmpty()
                                ? "Response kosong"
                                : raw
                );
            }

            result.put("http_status", status);
            result.put("endpoint", endpoint);

            return result;

        } catch (Exception e) {
            try {
                JSONObject error = new JSONObject();

                error.put("success", false);
                error.put("message", safe(e.getMessage()));
                error.put("endpoint", endpoint);

                return error;

            } catch (Exception ignored) {
                return new JSONObject();
            }

        } finally {
            try {
                if (conn != null) {
                    conn.disconnect();
                }
            } catch (Exception ignored) {}
        }
    }
}
