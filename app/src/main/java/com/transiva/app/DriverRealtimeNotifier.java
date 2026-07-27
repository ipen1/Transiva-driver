package com.transiva.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;

import androidx.core.app.NotificationCompat;

public class DriverRealtimeNotifier {
    private static final String CHANNEL_ID = "transiva_driver_wallet";

    public static void showWalletNotif(Context context, String title, String message) {
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Transiva Driver Wallet", NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("Notifikasi saldo, deposit, dan withdraw driver");
                ch.enableVibration(true);
                ch.setLightColor(Color.parseColor("#0B7CFF"));
                nm.createNotificationChannel(ch);
            }
            Intent intent = new Intent(context, DriverDashboardActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(context, 8101, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(pi);
            nm.notify((int) System.currentTimeMillis(), b.build());
        } catch (Exception ignored) {}
    }
}
