package com.transiva.app;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.transiva.app.driver.data.DriverConnectionRepository;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.Locale;
import java.util.Map;

/**
 * Transiva Rich Notification 1.0.
 *
 * Deliberately isolated from order/call/chat FCM routing. Only payloads whose type is
 * "transiva_rich" or "rich_notification" enter this path. A rich push can therefore
 * never create an order action, full-screen call intent, order ringtone or call state.
 */
public final class TransivaRichNotificationManager {
    public static final String TYPE = "transiva_rich";
    public static final String TYPE_ALIAS = "rich_notification";
    public static final String CHANNEL_ID = "transiva_rich_notification_v1";
    private static final int MAX_IMAGE_BYTES = 6 * 1024 * 1024;

    private TransivaRichNotificationManager() { }

    public static boolean isRichType(String type) {
        String t = type == null ? "" : type.trim().toLowerCase(Locale.US);
        return TYPE.equals(t) || TYPE_ALIAS.equals(t);
    }

    public static void createChannel(Context context) {
        if (context == null || Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Transiva Info & Promo",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        channel.setDescription("Banner promo, salam dan informasi Transiva. Terpisah dari order dan panggilan.");
        channel.enableVibration(true);
        nm.createNotificationChannel(channel);
    }

    public static boolean show(Context context, Map<String,String> data) {
        if (context == null || data == null) return false;
        createChannel(context);
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }

        String title = first(data.get("title"), "Transiva");
        String body = first(data.get("body"), data.get("message"), "Informasi terbaru dari Transiva");
        String imageUrl = first(data.get("image_url"), data.get("image"), data.get("banner_url"), "");
        String actionUrl = first(data.get("action_url"), data.get("url"), data.get("link"), "");
        String campaignId = first(data.get("campaign_id"), data.get("notification_id"), data.get("id"), "");

        Intent open = buildOpenIntent(context, actionUrl);
        int requestCode = stableId(campaignId.isEmpty() ? title + "|" + body + "|" + imageUrl : campaignId);
        PendingIntent content = PendingIntent.getActivity(context, requestCode, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_bell)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(content)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROMO)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

        Bitmap banner = loadBanner(imageUrl);
        if (banner != null) {
            builder.setLargeIcon(banner)
                    .setStyle(new NotificationCompat.BigPictureStyle()
                            .bigPicture(banner)
                            .bigLargeIcon((Bitmap) null)
                            .setBigContentTitle(title)
                            .setSummaryText(body));
        }

        NotificationManagerCompat.from(context).notify(requestCode, builder.build());
        return true;
    }

    private static Intent buildOpenIntent(Context context, String actionUrl) {
        if (actionUrl != null && !actionUrl.trim().isEmpty()) {
            try {
                Uri uri = Uri.parse(actionUrl.trim());
                String scheme = uri.getScheme();
                if ("https".equalsIgnoreCase(scheme)) {
                    Intent external = new Intent(Intent.ACTION_VIEW, uri);
                    external.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    return external;
                }
            } catch (Throwable ignored) { }
        }
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (launch == null) launch = new Intent(context, SplashActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return launch;
    }

    private static Bitmap loadBanner(String url) {
        if (url == null || url.trim().isEmpty()) return null;
        HttpURLConnection c = null;
        try {
            Uri uri = Uri.parse(url.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())) return null;
            c = DriverConnectionRepository.open(url.trim());
            c.setConnectTimeout(7000);
            c.setReadTimeout(9000);
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(true);
            c.setRequestProperty("Accept", "image/avif,image/webp,image/png,image/jpeg,*/*;q=0.5");
            int status = c.getResponseCode();
            if (status < 200 || status >= 300) return null;
            int len = c.getContentLength();
            if (len > MAX_IMAGE_BYTES) return null;
            try (InputStream in = new LimitedInputStream(c.getInputStream(), MAX_IMAGE_BYTES)) {
                return BitmapFactory.decodeStream(in);
            }
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static int stableId(String raw) {
        int h = raw == null ? 0 : raw.hashCode();
        return 730000 + Math.abs(h % 200000);
    }

    private static String first(String... values) {
        if (values != null) for (String v : values) if (v != null && !v.trim().isEmpty()) return v.trim();
        return "";
    }

    /** Hard byte ceiling so an oversized/malicious image cannot exhaust FCM service memory. */
    private static final class LimitedInputStream extends java.io.FilterInputStream {
        private long remaining;
        LimitedInputStream(InputStream in, long max) { super(in); remaining = max; }
        @Override public int read() throws java.io.IOException {
            if (remaining <= 0) return -1;
            int r = super.read(); if (r >= 0) remaining--; return r;
        }
        @Override public int read(byte[] b, int off, int len) throws java.io.IOException {
            if (remaining <= 0) return -1;
            int n = super.read(b, off, (int)Math.min(len, remaining)); if (n > 0) remaining -= n; return n;
        }
    }
}
