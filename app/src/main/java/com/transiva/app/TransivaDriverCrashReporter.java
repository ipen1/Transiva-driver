package com.transiva.app;

import android.content.Context;
import android.os.Build;
import android.content.pm.ApplicationInfo;
import com.google.firebase.crashlytics.FirebaseCrashlytics;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLException;

/** Pelaporan crash/non-fatal tanpa token, lokasi, nomor telepon, isi chat, atau payload API. */
public final class TransivaDriverCrashReporter {
    private static final ConcurrentHashMap<String, Long> LAST = new ConcurrentHashMap<>();
    private TransivaDriverCrashReporter() {}
    public static void initialize(Context context) {
        try {
            FirebaseCrashlytics c = FirebaseCrashlytics.getInstance();
            boolean debugBuild = (context.getApplicationInfo().flags
                    & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            c.setCrashlyticsCollectionEnabled(!debugBuild);
            c.setCustomKey("app_role", "driver");
            c.setCustomKey("android_sdk", Build.VERSION.SDK_INT);
            c.setCustomKey("device_brand", safe(Build.BRAND));
            c.setCustomKey("device_model", safe(Build.MODEL));
        } catch (Throwable ignored) {}
    }
    public static void screen(String name) { key("last_screen", sanitize(name)); }
    public static void message(String message) {
        try { FirebaseCrashlytics.getInstance().log(sanitize(message)); } catch (Throwable ignored) {}
    }
    public static void key(String name, String value) {
        try { FirebaseCrashlytics.getInstance().setCustomKey(sanitize(name), sanitize(value)); } catch (Throwable ignored) {}
    }
    public static void nonFatal(String operation, Throwable error) {
        if (error == null) return;
        String category = category(error);
        String signature = sanitize(operation) + ":" + category;
        long now = System.currentTimeMillis();
        Long previous = LAST.get(signature);
        if (previous != null && now - previous < 300_000L) return;
        LAST.put(signature, now);
        try {
            FirebaseCrashlytics c = FirebaseCrashlytics.getInstance();
            c.setCustomKey("nonfatal_operation", sanitize(operation));
            c.setCustomKey("nonfatal_category", category);
            c.recordException(new RuntimeException("Driver non-fatal: " + signature, error));
        } catch (Throwable ignored) {}
    }
    public static void http(String endpoint, int status) {
        if (status != 429 && status < 500) return;
        nonFatal("http_" + sanitizeEndpoint(endpoint) + "_" + status,
                new IllegalStateException("HTTP " + status));
    }
    private static String category(Throwable e) {
        if (e instanceof SocketTimeoutException) return "timeout";
        if (e instanceof UnknownHostException) return "dns_or_offline";
        if (e instanceof SSLException) return "ssl";
        return e.getClass().getSimpleName().toLowerCase(Locale.US);
    }
    private static String sanitizeEndpoint(String s) {
        String v=safe(s); int q=v.indexOf('?'); if(q>=0)v=v.substring(0,q); int slash=v.lastIndexOf('/');
        return sanitize(slash>=0?v.substring(slash+1):v);
    }
    private static String sanitize(String s) { return safe(s).replaceAll("[^A-Za-z0-9_.:-]", "_").substring(0, Math.min(80, safe(s).replaceAll("[^A-Za-z0-9_.:-]", "_").length())); }
    private static String safe(String s){ return s==null?"":s; }
}
