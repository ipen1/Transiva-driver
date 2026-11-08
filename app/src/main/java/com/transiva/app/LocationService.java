package com.transiva.app;

import androidx.core.content.ContextCompat;
import android.annotation.SuppressLint;
import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
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
    private static final long IDLE_HEARTBEAT = 90_000L;
    private static final long IDLE_GPS_WINDOW = 20_000L;
    private static final long IDLE_PROBE_INTERVAL = 100_000L;
    private static final float IDLE_MOVE_METERS = 75f;
    private static final long MAX_AGE = 60000L;
    private static final float MAX_ACCURACY = 150f;

    private SessionManager session;
    private DriverApiClient api;
    private LocationManager locationManager;
    private final AtomicBoolean sendInFlight = new AtomicBoolean(false);
    private long lastAttempt, lastSyncedAt;
    private int modeGeneration;
    private Location standbyAnchor;
    private boolean tracking, activeTrip, gpsRequested;
    private final android.os.Handler guardianHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable endProbe = () -> { if (!activeTrip) stopGps(); };
    private final Runnable idleProbe = new Runnable() {
        @Override public void run() {
            if (!tracking || activeTrip) return;
            if (lastSyncedAt == 0 || SystemClock.elapsedRealtime() - lastSyncedAt >= IDLE_HEARTBEAT) {
                startIdleProbe();
            }
            guardianHandler.postDelayed(this, IDLE_PROBE_INTERVAL);
        }
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener sessionChanges = (prefs, key) -> {
        if (!tracking) return;
        if ("current_order_id".equals(key)) configureMode(false);
        if ("driver_server_online".equals(key) && !canTrack()) stopTracking();
    };
    private final SmoothLocationEngine smoothLocation = new SmoothLocationEngine(5000L);
    private final DriverGuardianManager guardian = new DriverGuardianManager();
    private final Runnable guardianPoll=new Runnable(){public void run(){
        if(!tracking || !activeTrip || !canTrack())return;
        guardian.refresh(LocationService.this);guardianHandler.postDelayed(this,15000L);
    }};

    private final LocationListener listener = locationListener(false);
    private final LocationListener gpsListener = locationListener(true);

    private LocationListener locationListener(boolean gps) {
        return new LocationListener() {
            @Override public void onLocationChanged(Location location) { handle(location, gps); }
            @Override public void onProviderEnabled(String provider) {
                if (tracking) configureMode(true);
            }
            @Override public void onProviderDisabled(String provider) {
                if (tracking) configureMode(true);
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
        };
    }

    @Override public void onCreate() {
        super.onCreate();
        session = new SessionManager(this);
        session.observe(sessionChanges);
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
                    notification(tracking ? modeLabel() : "Menyiapkan lokasi driver…"));
        } catch (SecurityException error) {
            Log.e(TAG, "Foreground location service ditolak; dashboard tetap berjalan", error);
            stopTracking();
            return START_NOT_STICKY;
        } catch (Exception error) {
            Log.e(TAG, "Gagal memulai foreground location service", error);
            stopTracking();
            return START_NOT_STICKY;
        }

        // Saat driver ONLINE, foreground location service menjadi "anchor"
        // proses. Bubble ikut dihidupkan dari sini agar tidak bergantung pada
        // Activity/Recent Apps dan tetap ada setelah UI diswipe.
        DriverBubbleController.start(this);

        if (!tracking) {
            tracking = true;
            configureMode(true);
        }
        return START_STICKY;
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

    private void configureMode(boolean force) {
        if (!canTrack()) {
            stopTracking();
            return;
        }
        boolean trip = !session.get("current_order_id").trim().isEmpty();
        if (!force && trip == activeTrip) return;
        activeTrip = trip;
        modeGeneration++;
        lastAttempt = 0;
        lastSyncedAt = 0;
        standbyAnchor = null;
        guardianHandler.removeCallbacks(idleProbe);
        guardianHandler.removeCallbacks(guardianPoll);
        guardian.refresh(this);
        stopGps();
        locationManager.removeUpdates(listener);
        if (activeTrip) {
            guardianHandler.post(guardianPoll);
            listen(LocationManager.GPS_PROVIDER, ACTIVE_INTERVAL, 1f, gpsListener);
            listen(LocationManager.NETWORK_PROVIDER, ACTIVE_INTERVAL, 1f, listener);
        } else {
            listen(LocationManager.NETWORK_PROVIDER, 30_000L, 0f, listener);
            listen(LocationManager.PASSIVE_PROVIDER, 15_000L, IDLE_MOVE_METERS, listener);
            startIdleProbe();
            guardianHandler.postDelayed(idleProbe, IDLE_PROBE_INTERVAL);
        }
        updateNotification(modeLabel());
    }

    private String modeLabel() {
        return activeTrip ? "Online • perjalanan aktif" : "Online • standby pesanan";
    }

    @SuppressLint("MissingPermission")
    private boolean listen(String provider, long interval, float distance, LocationListener target) {
        try {
            if (!locationManager.isProviderEnabled(provider)) return false;
            locationManager.requestLocationUpdates(provider, interval, distance, target);
            return true;
        } catch (RuntimeException error) {
            Log.w(TAG, "Provider lokasi gagal: " + provider, error);
            return false;
        }
    }

    private void startIdleProbe() {
        if (gpsRequested || activeTrip || !tracking) return;
        gpsRequested = listen(LocationManager.GPS_PROVIDER, 3_000L, 0f, gpsListener);
        if (gpsRequested) guardianHandler.postDelayed(endProbe, IDLE_GPS_WINDOW);
    }

    private void stopGps() {
        guardianHandler.removeCallbacks(endProbe);
        if (locationManager != null) locationManager.removeUpdates(gpsListener);
        gpsRequested = false;
    }

    private void handle(Location location, boolean gps) {
        if (!tracking) return;
        if (!canTrack()) {
            stopTracking();
            return;
        }
        if (DriverSecurityPolicy.fakeGpsEnabledCached(this) && MockLocationGuard.isMock(location)) {
            Log.w(TAG, "Lokasi mock ditolak oleh policy keamanan Driver.");
            return;
        }
        if (!usable(location)) return;
        long now = SystemClock.elapsedRealtime();
        if (activeTrip) {
            SmoothLocationEngine.Fix fix = smoothLocation.offer(location);
            if (fix == null || !fix.upload) return;
            guardian.onLocation(this, fix.location);
            upload(fix.location, false, now);
            return;
        }

        float movement = standbyAnchor == null ? Float.POSITIVE_INFINITY : standbyAnchor.distanceTo(location);
        float threshold = Math.max(IDLE_MOVE_METERS, location.hasAccuracy() ? location.getAccuracy() : 0f);
        boolean moved = movement >= threshold;
        if (!gps && moved && standbyAnchor != null && threshold > IDLE_MOVE_METERS) {
            startIdleProbe();
            if (gpsRequested) return;
        }
        boolean heartbeat = !moved && lastSyncedAt > 0 && now - lastSyncedAt >= IDLE_HEARTBEAT
                && location.hasAccuracy() && location.getAccuracy() > 0 && location.getAccuracy() <= 100f;
        if (moved || heartbeat) upload(location, heartbeat, now);
        if (gps && gpsRequested && standbyAnchor != null && !moved && now - lastSyncedAt < IDLE_HEARTBEAT
                && location.hasAccuracy() && location.getAccuracy() <= 45f) stopGps();
    }

    private void upload(Location location, boolean heartbeat, long now) {
        if ((lastAttempt > 0 && now - lastAttempt < ACTIVE_INTERVAL) || !sendInFlight.compareAndSet(false, true)) return;
        lastAttempt = now;
        String orderId = session.get("current_order_id");
        String orderSource = session.get("current_order_source");
        Location fix = new Location(location);
        int generation = modeGeneration;
        if (!heartbeat) {
            session.saveLastLocation(String.valueOf(fix.getLatitude()), String.valueOf(fix.getLongitude()));
        }
        boolean accepted = DriverNetworkExecutor.execute(() -> {
            try { send(fix, heartbeat, orderId, orderSource, generation); }
            finally { sendInFlight.set(false); }
        });
        if (!accepted) sendInFlight.set(false);
    }

    private void send(Location location, boolean heartbeat, String orderId, String orderSource, int generation) {
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
            body.put("order_id", orderId);
            body.put("order_source", orderSource.isEmpty() ? "orders" : orderSource);
            if (heartbeat) body.put("standby_heartbeat", true);

            DriverApiClient.Result result =
                    api.post("driver_update_location_native.php", body);

            if (!result.body.optBoolean("driver_online", true)) {
                session.put("driver_server_online", "0");
                guardianHandler.post(this::stopTracking);
            } else {
                session.put("last_location_sync_at",
                        String.valueOf(System.currentTimeMillis()));
                boolean unchanged = result.body.optBoolean("standby_heartbeat", false);
                guardianHandler.post(() -> {
                    if (!tracking || activeTrip || generation != modeGeneration) return;
                    lastSyncedAt = SystemClock.elapsedRealtime();
                    if (!unchanged) {
                        standbyAnchor = location;
                        if (heartbeat) session.saveLastLocation(String.valueOf(location.getLatitude()),
                                String.valueOf(location.getLongitude()));
                    }
                    if (gpsRequested && location.hasAccuracy() && location.getAccuracy() <= 45f) stopGps();
                });
            }
        } catch (DriverApiClient.ApiException e) {
            Log.e(TAG, e.code + ": " + e.getMessage(), e);
            if (e.status == 401 || "UNAUTHORIZED".equalsIgnoreCase(e.code)
                    || "TOKEN_EXPIRED".equalsIgnoreCase(e.code)
                    || "TOKEN_REVOKED".equalsIgnoreCase(e.code)) {
                session.forceLogout("session_expired");
                guardianHandler.post(this::stopTracking);
            } else if ("DRIVER_OFFLINE".equalsIgnoreCase(e.code)) {
                // 403 DRIVER_OFFLINE adalah state operasional, bukan sesi invalid.
                // Jangan logout driver hanya karena status server berubah offline.
                session.put("driver_server_online", "0");
                session.put("driver_is_online", "0");
                guardianHandler.post(this::stopTracking);
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

        return true;
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
        tracking = false;
        guardianHandler.removeCallbacks(idleProbe);
        guardianHandler.removeCallbacks(guardianPoll);
        DriverGuardianManager.invalidate(this);
        try {
            stopGps();
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
                .setContentText(text)
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
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;
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

    @Override public void onTaskRemoved(Intent rootIntent) {
        // stopWithTask=false + START_STICKY menjaga layanan ONLINE saat aplikasi
        // dibuang dari Recent Apps. Pastikan overlay juga diminta hidup kembali.
        if (session != null && "1".equals(session.get("driver_server_online"))) {
            DriverBubbleController.start(this);
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        tracking = false;
        session.stopObserving(sessionChanges);
        guardianHandler.removeCallbacksAndMessages(null);guardian.close(this);
        try {
            stopGps();
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
