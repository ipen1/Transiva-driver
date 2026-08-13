package com.transiva.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.transiva.app.driver.data.DriverApiClient;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

public class LocationService extends Service {

    public static final String ACTION_START =
            "com.transiva.app.START_DRIVER_LOCATION";
    public static final String ACTION_STOP =
            "com.transiva.app.STOP_DRIVER_LOCATION";

    private static final String TAG = "DriverLocationService";
    private static final String CHANNEL_ID = "driver_location_native_v2";
    private static final int NOTIFICATION_ID = 2206;
    private static final long ACTIVE_INTERVAL = 5000L;
    private static final long IDLE_INTERVAL = 12000L;
    private static final float MIN_DISTANCE = 1f;
    private static final long MAX_AGE = 60000L;
    private static final float MAX_ACCURACY = 150f;

    private SessionManager session;
    private DriverApiClient api;
    private LocationManager locationManager;
    private final AtomicBoolean sendInFlight = new AtomicBoolean(false);
    private long lastSentAt;
    private Location lastSent;
    private final SmoothLocationEngine smoothLocation = new SmoothLocationEngine(5000L);

    private final LocationListener listener = new LocationListener() {
        @Override public void onLocationChanged(Location location) {
            handle(location);
        }
        @Override public void onProviderEnabled(String provider) {}
        @Override public void onProviderDisabled(String provider) {
            Log.w(TAG, "Provider lokasi nonaktif: " + provider);
        }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    };

    @Override public void onCreate() {
        super.onCreate();
        session = new SessionManager(this);
        api = new DriverApiClient(session);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopTracking();
            return START_NOT_STICKY;
        }

        // Jangan masuk foreground-location mode bila syarat tracking belum
        // terpenuhi. Ini penting di Android 12-14+: memulai foreground service
        // lokasi tanpa kondisi/izin yang siap dapat melempar SecurityException
        // dan menutup proses aplikasi saat driver baru login.
        if (!canTrack()) {
            stopTracking();
            return START_NOT_STICKY;
        }

        try {
            startForeground(NOTIFICATION_ID,
                    notification("Menyiapkan lokasi driver…"));
        } catch (SecurityException error) {
            Log.e(TAG, "Foreground location service ditolak; dashboard tetap berjalan", error);
            stopTracking();
            return START_NOT_STICKY;
        } catch (Exception error) {
            Log.e(TAG, "Gagal memulai foreground location service", error);
            stopTracking();
            return START_NOT_STICKY;
        }

        requestUpdates();
        return START_STICKY;
    }

    private long desiredInterval() {
        try {
            String orderId = session == null ? "" : session.get("current_order_id");
            return orderId == null || orderId.trim().isEmpty() ? IDLE_INTERVAL : ACTIVE_INTERVAL;
        } catch (Exception ignored) {
            return IDLE_INTERVAL;
        }
    }

    private boolean canTrack() {
        return session.isLoggedIn()
                && "driver".equals(session.normalizeRole(session.getRole()))
                && "1".equals(session.get("driver_server_online"))
                && hasPermission()
                && isLocationProviderEnabled();
    }

    private boolean isLocationProviderEnabled() {
        if (locationManager == null) return false;
        try {
            return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception error) {
            Log.w(TAG, "Tidak dapat membaca status provider lokasi", error);
            return false;
        }
    }

    private void requestUpdates() {
        if (locationManager == null || !hasPermission()) {
            stopTracking();
            return;
        }

        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        ACTIVE_INTERVAL,
                        MIN_DISTANCE,
                        listener
                );
            }
        } catch (Exception e) {
            Log.e(TAG, "GPS listener gagal", e);
        }

        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        ACTIVE_INTERVAL,
                        MIN_DISTANCE,
                        listener
                );
            }
        } catch (Exception e) {
            Log.e(TAG, "Network listener gagal", e);
        }

        // Notification foreground dibuat sekali saat service start.
        // Jangan notify ulang setiap update GPS agar panel notifikasi tetap tenang.
    }

    private void handle(Location location) {
        if (!canTrack()) {
            stopTracking();
            return;
        }
        if (!usable(location)) return;
        SmoothLocationEngine.Fix fix = smoothLocation.offer(location);
        if (fix == null || !fix.upload) return;

        Location accepted = fix.location;
        long now = System.currentTimeMillis();
        if (lastSentAt > 0L && now - lastSentAt < desiredInterval()) return;
        lastSentAt = now;
        lastSent = new Location(accepted);
        session.saveLastLocation(
                String.valueOf(accepted.getLatitude()),
                String.valueOf(accepted.getLongitude())
        );

        if (sendInFlight.compareAndSet(false, true)) {
            boolean acceptedTask = DriverNetworkExecutor.execute(() -> {
                try { send(accepted); }
                finally { sendInFlight.set(false); }
            });
            if (!acceptedTask) sendInFlight.set(false);
        }
    }

    private void send(Location location) {
        try {
            JSONObject body = new JSONObject();
            body.put("latitude", location.getLatitude());
            body.put("longitude", location.getLongitude());
            body.put("accuracy",
                    location.hasAccuracy() ? location.getAccuracy() : JSONObject.NULL);
            body.put("speed_kmh",
                    location.hasSpeed() ? location.getSpeed() * 3.6d : JSONObject.NULL);
            body.put("bearing",
                    location.hasBearing() ? location.getBearing() : JSONObject.NULL);
            body.put("location_time", location.getTime());
            body.put("order_id", session.get("current_order_id"));

            DriverApiClient.Result result =
                    api.post("driver_update_location_native.php", body);

            if (!result.body.optBoolean("driver_online", true)) {
                session.put("driver_server_online", "0");
                stopTracking();
            } else {
                session.put("last_location_sync_at",
                        String.valueOf(System.currentTimeMillis()));
                // Sukses tidak perlu memperbarui notifikasi setiap 5-12 detik.
            }
        } catch (DriverApiClient.ApiException e) {
            Log.e(TAG, e.code + ": " + e.getMessage(), e);
            if (e.status == 401 || "UNAUTHORIZED".equalsIgnoreCase(e.code)
                    || "TOKEN_EXPIRED".equalsIgnoreCase(e.code)
                    || "TOKEN_REVOKED".equalsIgnoreCase(e.code)) {
                session.forceLogout("session_expired");
                stopTracking();
            } else if ("DRIVER_OFFLINE".equalsIgnoreCase(e.code)) {
                // 403 DRIVER_OFFLINE adalah state operasional, bukan sesi invalid.
                // Jangan logout driver hanya karena status server berubah offline.
                session.put("driver_server_online", "0");
                session.put("driver_is_online", "0");
                stopTracking();
            } else {
                Log.w(TAG, "Lokasi belum tersinkron: " + e.code);
            }
        } catch (Exception e) {
            Log.e(TAG, "Payload lokasi gagal", e);
        }
    }

    private boolean usable(Location location) {
        if (location == null) return false;
        double lat = location.getLatitude();
        double lng = location.getLongitude();
        if (lat == 0 || lng == 0
                || lat < -90 || lat > 90
                || lng < -180 || lng > 180) return false;
        if (location.hasAccuracy() && location.getAccuracy() > MAX_ACCURACY) {
            return false;
        }

        long age;
        if (Build.VERSION.SDK_INT >= 17) {
            age = SystemClock.elapsedRealtime()
                    - location.getElapsedRealtimeNanos() / 1000000L;
        } else {
            age = System.currentTimeMillis() - location.getTime();
        }
        if (age < 0 || age > MAX_AGE) return false;

        return true; // mock-provider juga diterima agar simulasi/debug mengikuti jalur yang sama.
    }

    private boolean hasPermission() {
        return ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void stopTracking() {
        try {
            if (locationManager != null) locationManager.removeUpdates(listener);
        } catch (Exception error) {
            Log.w(TAG, "Gagal melepas listener lokasi", error);
        }
        try {
            stopForeground(true);
        } catch (Exception error) {
            Log.w(TAG, "Gagal menghentikan foreground lokasi", error);
        }
        stopSelf();
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, DriverDashboardActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(
                this,
                2206,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Transiva Driver")
                .setContentText("Online • lokasi aktif")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setContentIntent(pending)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification(text));
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Lokasi Driver",
                NotificationManager.IMPORTANCE_MIN
        );
        channel.setDescription("Tracking lokasi driver saat online");
        channel.enableVibration(false);
        channel.enableLights(false);
        channel.setShowBadge(false);
        channel.setSound(null, null);
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        manager.createNotificationChannel(channel);
    }

    @Override public void onDestroy() {
        try {
            if (locationManager != null) locationManager.removeUpdates(listener);
        } catch (Exception error) {
            Log.w(TAG, "Gagal melepas listener saat destroy", error);
        }
        api.shutdown();
        super.onDestroy();
    }

    @Nullable
    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
