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
abstract class BackgroundSyncServiceLayer1 extends Service {

    public static final String ACTION_START =
            "com.transiva.app.START_BACKGROUND_SYNC";

    public static final String ACTION_STOP =
            "com.transiva.app.STOP_BACKGROUND_SYNC";

    public static final String ACTION_SYNC_NOW =
            "com.transiva.app.SYNC_NOW";

    protected static final String CHANNEL_ID =
            "transiva_background_sync_channel";

    protected static final String CHANNEL_NAME =
            "Sinkronisasi Transiva";

    protected static final int NOTIFICATION_ID = 3030;

    protected static final String BASE_URL =
            "https://transiva.my.id/";

    protected static final String UPDATE_DRIVER_LOCATION_ENDPOINT =
            "server/driver_update_location_native.php";

    protected static final long IDLE_SYNC_INTERVAL = 45000L;
    protected static final long ACTIVE_SYNC_INTERVAL = 15000L;
    protected static final long FAST_RETRY_INTERVAL = 15000L;

    protected static final int CONNECT_TIMEOUT = 15000;
    protected static final int READ_TIMEOUT = 20000;

    protected Handler handler;
    protected SessionManager sessionManager;

    protected boolean isRunning = false;
    protected boolean foregroundStarted = false;
    protected int failCount = 0;

    protected final Runnable syncRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) {
                return;
            }

            if (!hasValidLoginSession()) {
                stopSyncServiceSilent("Belum login");
                return;
            }

            try {
                doSync();
            } catch (Exception ignored) {}

            long nextDelay = failCount > 0
                    ? FAST_RETRY_INTERVAL
                    : adaptiveSyncInterval();

            try {
                if (handler != null && isRunning) {
                    handler.postDelayed(this, WaveLoadGuard.jitter(nextDelay));
                }
            } catch (Exception ignored) {}
        }
    };

    public static void start(Context context) {
        try {
            if (!isLoggedIn(context)) {
                stop(context);
                return;
            }

            Intent intent =
                    new Intent(
                            context,
                            BackgroundSyncService.class
                    );

            intent.setAction(ACTION_START);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }

        } catch (Exception ignored) {}
    }

    public static void stop(Context context) {
        try {
            Intent intent =
                    new Intent(
                            context,
                            BackgroundSyncService.class
                    );

            intent.setAction(ACTION_STOP);

            context.startService(intent);

        } catch (Exception ignored) {}
    }

    public static void syncNow(Context context) {
        try {
            if (!isLoggedIn(context)) {
                stop(context);
                return;
            }

            Intent intent =
                    new Intent(
                            context,
                            BackgroundSyncService.class
                    );

            intent.setAction(ACTION_SYNC_NOW);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }

        } catch (Exception ignored) {}
    }

    protected static boolean isLoggedIn(Context context) {
        try {
            SessionManager manager =
                    new SessionManager(context);

            if (!manager.isLoggedIn()) {
                return false;
            }

            String username =
                    manager.getUsername() == null
                            ? ""
                            : manager.getUsername().trim();

            String role =
                    manager.getRole() == null
                            ? ""
                            : manager.getRole().trim();

            return !username.isEmpty()
                    && !role.isEmpty();

        } catch (Exception e) {
            return false;
        }
    }

    protected String readStream(InputStream stream) {
        try {
            if (stream == null) {
                return "";
            }

            BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    stream,
                                    "UTF-8"
                            )
                    );

            StringBuilder builder =
                    new StringBuilder();

            String line;

            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }

            reader.close();

            return builder.toString();

        } catch (Exception e) {
            return "";
        }
    }

    protected boolean isOnline() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager)
                            getSystemService(CONNECTIVITY_SERVICE);

            if (cm == null) {
                return false;
            }

            NetworkInfo info =
                    cm.getActiveNetworkInfo();

            return info != null && info.isConnected();

        } catch (Exception e) {
            return false;
        }
    }

    protected boolean hasLocationPermission() {
        return ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
                || ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED;
    }

    protected Notification buildNotification(String message) {
        Intent openIntent =
                new Intent(this, DriverDashboardActivity.class);

        openIntent.setFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP |
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
        );

        PendingIntent openPendingIntent =
                PendingIntent.getActivity(
                        this,
                        3031,
                        openIntent,
                        PendingIntent.FLAG_IMMUTABLE |
                                PendingIntent.FLAG_UPDATE_CURRENT
                );

        Intent stopIntent =
                new Intent(this, BackgroundSyncService.class);

        stopIntent.setAction(ACTION_STOP);

        PendingIntent stopPendingIntent =
                PendingIntent.getService(
                        this,
                        3032,
                        stopIntent,
                        PendingIntent.FLAG_IMMUTABLE |
                                PendingIntent.FLAG_UPDATE_CURRENT
                );

        return new NotificationCompat.Builder(
                this,
                CHANNEL_ID
        )
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Transiva")
                .setContentText(message)
                .setStyle(
                        new NotificationCompat.BigTextStyle()
                                .bigText(message)
                )
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setContentIntent(openPendingIntent)
                .addAction(
                        R.mipmap.ic_launcher,
                        "Stop",
                        stopPendingIntent
                )
                .build();
    }

    protected void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }

        try {
            NotificationManager manager =
                    (NotificationManager)
                            getSystemService(NOTIFICATION_SERVICE);

            if (manager == null) {
                return;
            }

            NotificationChannel old =
                    manager.getNotificationChannel(CHANNEL_ID);

            if (old != null) {
                return;
            }

            NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL_ID,
                            CHANNEL_NAME,
                            NotificationManager.IMPORTANCE_LOW
                    );

            channel.setDescription(
                    "Sinkronisasi background Transiva hanya saat user login"
            );

            channel.setLockscreenVisibility(
                    Notification.VISIBILITY_PRIVATE
            );

            channel.enableVibration(false);
            channel.enableLights(false);

            manager.createNotificationChannel(channel);

        } catch (Exception ignored) {}
    }

    protected void stopSyncService(String message) {
        shutdownLoop();

        try {
            if (sessionManager != null) {
                sessionManager.put(
                        "background_sync_running",
                        "0"
                );

                sessionManager.put(
                        "background_sync_status",
                        "stopped"
                );

                sessionManager.put(
                        "background_sync_message",
                        safe(message).isEmpty()
                                ? "Service dihentikan"
                                : message
                );
            }
        } catch (Exception ignored) {}

        stopForegroundAndSelf();
    }

    protected void stopSyncServiceSilent(String message) {
        shutdownLoop();

        try {
            if (sessionManager != null) {
                sessionManager.put(
                        "background_sync_running",
                        "0"
                );

                sessionManager.put(
                        "background_sync_status",
                        "idle"
                );

                sessionManager.put(
                        "background_sync_message",
                        safe(message).isEmpty()
                                ? "Service tidak aktif"
                                : message
                );
            }
        } catch (Exception ignored) {}

        stopForegroundAndSelf();
    }

    protected void shutdownLoop() {
        try {
            isRunning = false;

            if (handler != null) {
                handler.removeCallbacks(syncRunnable);
            }
        } catch (Exception ignored) {}
    }

    protected void stopForegroundAndSelf() {
        try {
            if (foregroundStarted) {
                if (Build.VERSION.SDK_INT >= 24) {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                } else {
                    stopForeground(true);
                }
            } else {
                /*
                 * Aman dipanggil walau startForeground belum pernah jalan.
                 * Tujuannya memastikan tidak ada notification lama tertinggal.
                 */
                stopForeground(true);
            }
        } catch (Exception ignored) {}

        foregroundStarted = false;

        try {
            stopSelf();
        } catch (Exception ignored) {}
    }

    protected String cleanEndpoint(String endpoint) {
        String e =
                safe(endpoint).trim();

        while (e.startsWith("/")) {
            e = e.substring(1);
        }

        return e;
    }

    protected String safe(String value) {
        return value == null ? "" : value;
    }

    @Override
    public void onDestroy() {
        shutdownLoop();

        try {
            if (sessionManager != null) {
                sessionManager.put(
                        "background_sync_running",
                        "0"
                );
            }
        } catch (Exception ignored) {}

        try {
            if (foregroundStarted) {
                stopForeground(true);
            }
        } catch (Exception ignored) {}

        foregroundStarted = false;

        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    // Cross-layer contracts keep the split type-safe without duplicating state.
    protected abstract void onCreate();
    protected abstract int onStartCommand( Intent intent, int flags, int startId );
    protected abstract void ensureForeground();
    protected abstract void startLoop();
    protected abstract void runSyncOnce();
    protected abstract void doSync();
    protected abstract void syncDriverLocation();
    protected abstract long adaptiveSyncInterval();
    protected abstract boolean hasValidLoginSession();
    protected abstract Location getQuickLastKnownLocation();
    protected abstract JSONObject postJson( String endpoint, JSONObject body );

}
