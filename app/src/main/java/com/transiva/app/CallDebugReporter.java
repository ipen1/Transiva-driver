package com.transiva.app;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Persistent on-device WebRTC diagnostics for temporary DEBUG builds. */
public final class CallDebugReporter {
    private static final String PREF = "webrtc_debug_v1";
    private static final String KEY_LOG = "last_log";
    private static final String KEY_CALL = "last_call";
    private static final String CH = "transiva_webrtc_debug_v1";
    private static final int NOTIF = 907711;
    private static final int MAX_CHARS = 12000;

    private CallDebugReporter() {}

    public static synchronized void clear(Context c, String callId) {
        prefs(c).edit().putString(KEY_LOG, "").putString(KEY_CALL, clean(callId)).apply();
    }

    public static synchronized void log(Context c, String callId, String event) {
        if (c == null) return;
        String line = now() + "  " + clean(event);
        SharedPreferences p = prefs(c);
        String old = p.getString(KEY_LOG, "");
        String next = old.isEmpty() ? line : old + "\n" + line;
        if (next.length() > MAX_CHARS) next = next.substring(next.length() - MAX_CHARS);
        p.edit().putString(KEY_LOG, next).putString(KEY_CALL, clean(callId)).apply();
        RemoteWebRtcLog.async(c.getApplicationContext(), clean(callId), "BREADCRUMB", line);
    }

    public static String getLog(Context c) { return prefs(c).getString(KEY_LOG, ""); }

    public static void notifyError(Context c, String title, String reason) {
        if (c == null) return;
        createChannel(c);
        String log = getLog(c);
        String last = lastLines(log, 7);
        String body = clean(reason);
        if (!last.isEmpty()) body = body + "\n\nTAHAP TERAKHIR:\n" + last;
        if (body.length() > 3500) body = body.substring(body.length() - 3500);

        Intent open = new Intent(c, WebRtcCallActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, NOTIF, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CH)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(clean(title).isEmpty() ? "WebRTC DEBUG ERROR" : title)
                .setContentText(clean(reason))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi);
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(c,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManagerCompat.from(c).notify(NOTIF, b.build());
    }

    public static void installCrashHandler(Context c, String callId) {
        final Context app = c.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        if (previous instanceof DebugCrashHandler) return;
        Thread.setDefaultUncaughtExceptionHandler(new DebugCrashHandler(app, callId, previous));
    }

    private static final class DebugCrashHandler implements Thread.UncaughtExceptionHandler {
        private final Context app;
        private final String callId;
        private final Thread.UncaughtExceptionHandler previous;
        DebugCrashHandler(Context app, String callId, Thread.UncaughtExceptionHandler previous) {
            this.app = app; this.callId = callId; this.previous = previous;
        }
        @Override public void uncaughtException(Thread t, Throwable e) {
            String msg = e == null ? "Unknown crash" : e.getClass().getSimpleName() + ": " + clean(e.getMessage());
            StringBuilder full = new StringBuilder();
            full.append("UNCAUGHT [").append(t == null ? "?" : t.getName()).append("] ").append(msg);
            if (e != null) {
                try {
                    java.io.StringWriter sw = new java.io.StringWriter();
                    java.io.PrintWriter pw = new java.io.PrintWriter(sw);
                    e.printStackTrace(pw);
                    pw.flush();
                    full.append("\n").append(sw.toString());
                } catch (Throwable ignored) {}
            }
            log(app, callId, full.toString());
            try { RemoteWebRtcLog.critical(app, callId, full.toString()); } catch (Throwable ignored) {}
            try { notifyError(app, "WebRTC CRASH", msg); } catch (Throwable ignored) {}
            if (previous != null) previous.uncaughtException(t, e);
        }
    }

    private static void createChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CH, "WebRTC Debug", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Informasi debug panggilan Transiva");
        nm.createNotificationChannel(ch);
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREF, Context.MODE_PRIVATE); }
    private static String now() { return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()); }
    private static String lastLines(String s, int count) {
        if (s == null || s.isEmpty()) return "";
        String[] a=s.split("\n"); int start=Math.max(0,a.length-count); StringBuilder b=new StringBuilder();
        for(int i=start;i<a.length;i++){ if(b.length()>0)b.append('\n'); b.append(a[i]); } return b.toString();
    }
    private static String clean(String s) { return s == null ? "" : s.trim(); }
}
