package com.transiva.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class TransivaDriverForegroundService extends Service {

    private static final String TAG = "TRANSIVA_DRIVER_SERVICE";

    public static final String ACTION_START = "TRANSIVA_DRIVER_SERVICE_START";
    public static final String ACTION_STOP = "TRANSIVA_DRIVER_SERVICE_STOP";

    public static final String CHANNEL_ID = "transiva_order_channel";
    public static final String CHANNEL_NAME = "Order Transiva";

    private static final int NOTIFICATION_ID = 2001;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Log.d(TAG, "Service dibuat");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopServiceNow();
            return START_NOT_STICKY;
        }

        getSharedPreferences("transiva", MODE_PRIVATE)
                .edit()
                .putBoolean("driver_online", true)
                .apply();

        Notification notification = buildNotification();

        try {

            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                );
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }

            Log.d(TAG, "Service aktif foreground");

        } catch (Exception e) {
            Log.e(TAG, "Gagal start foreground: " + e.getMessage());
        }

        return START_STICKY;
    }

    private Notification buildNotification() {

        Intent openIntent = new Intent(this, DriverDashboardActivity.class);
        openIntent.setAction("OPEN_TRANSIVA");
        openIntent.setFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP |
                        Intent.FLAG_ACTIVITY_CLEAR_TOP |
                        Intent.FLAG_ACTIVITY_NEW_TASK
        );

        PendingIntent pendingIntent =
                PendingIntent.getActivity(
                        this,
                        2001,
                        openIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT |
                                PendingIntent.FLAG_IMMUTABLE
                );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Transiva Aktif")
                .setContentText("Menjaga order dan lokasi tetap stabil.")
                .setStyle(
                        new NotificationCompat.BigTextStyle()
                                .bigText("Transiva berjalan di latar belakang untuk menjaga notifikasi order, status online, dan lokasi tetap stabil.")
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setContentIntent(pendingIntent)
                .build();
    }

    private void createNotificationChannel() {

        if (Build.VERSION.SDK_INT < 26) {
            return;
        }

        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (manager == null) {
            return;
        }

        NotificationChannel old = manager.getNotificationChannel(CHANNEL_ID);

        if (old != null) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
        );

        channel.setDescription("Notifikasi order dan layanan latar belakang Transiva");
        channel.enableVibration(true);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

        manager.createNotificationChannel(channel);
    }

    private void stopServiceNow() {

        getSharedPreferences("transiva", MODE_PRIVATE)
                .edit()
                .putBoolean("driver_online", false)
                .putBoolean("merchant_online", false)
                .apply();

        try {
            stopForeground(true);
        } catch (Exception ignored) {}

        stopSelf();

        Log.d(TAG, "Service dihentikan");
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);

        boolean driverOnline =
                getSharedPreferences("transiva", MODE_PRIVATE)
                        .getBoolean("driver_online", false);

        boolean merchantOnline =
                getSharedPreferences("transiva", MODE_PRIVATE)
                        .getBoolean("merchant_online", false);

        if (driverOnline || merchantOnline) {
            DriverServiceController.startAfterSystemEvent(getApplicationContext());
            Log.d(TAG, "Restart service didelegasikan ke controller dengan pembatas.");
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service dihancurkan");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
