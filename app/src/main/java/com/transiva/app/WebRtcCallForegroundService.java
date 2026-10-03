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

/** Foreground microphone eligibility and persistent return-to-call notification.
 * WebRTC ownership remains in Activity in this compatibility patch. */
public final class WebRtcCallForegroundService extends Service {
    private static final String CHANNEL = "transiva_active_voice_call";
    private static final int ID = 7821;
    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL, "Panggilan Transiva", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Panggilan suara sedang berlangsung");
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Intent open = new Intent(this, WebRtcCallActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int immutable = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pending = PendingIntent.getActivity(this, 7821, open, PendingIntent.FLAG_UPDATE_CURRENT | immutable);
        String peer = intent == null ? "" : intent.getStringExtra("peer");
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Notification notification = b.setSmallIcon(getApplicationInfo().icon)
            .setContentTitle("Panggilan Transiva berlangsung")
            .setContentText(peer == null || peer.isEmpty() ? "Ketuk untuk kembali ke panggilan" : "Dengan " + peer)
            .setContentIntent(pending).setOngoing(true).setCategory(Notification.CATEGORY_CALL).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        else startForeground(ID, notification);
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
