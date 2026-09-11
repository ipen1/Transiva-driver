package com.transiva.app;

import android.content.Context;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Single transport factory for every HTTP(S) connection in Transiva Driver.
 *
 * Stability 4.0 invariant:
 * - no production class may call URL.openConnection() directly;
 * - common timeouts/cache policy/client identity live here;
 * - authenticated driver sessions automatically carry bearer + device identity;
 * - individual APIs may still override request method/timeouts/content headers.
 *
 * Keeping connection creation here gives us one place for future TLS/network
 * instrumentation without rewriting Dashboard/Trip/Navigation/Chat again.
 */
public final class DriverHttpTransport {
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 15_000;
    public static final int DEFAULT_READ_TIMEOUT_MS = 20_000;

    private DriverHttpTransport() {}

    public static HttpURLConnection open(String url) throws IOException {
        return open(new URL(url));
    }

    public static HttpURLConnection open(URL url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        applyDefaults(connection);
        if (isTrustedTransivaHost(url)) {
            applyTransivaHeaders(connection);
            applySessionHeaders(connection);
        }
        return connection;
    }

    private static void applyDefaults(HttpURLConnection connection) {
        connection.setConnectTimeout(DEFAULT_CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(DEFAULT_READ_TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setDoInput(true);
        connection.setRequestProperty("Accept", "application/json");
    }

    private static void applyTransivaHeaders(HttpURLConnection connection) {
        connection.setRequestProperty("X-Transiva-Client", "Android-Native");
        connection.setRequestProperty("X-App-Scope", "driver");
    }

    static boolean isTrustedTransivaHost(URL url) {
        String host = url == null || url.getHost() == null ? "" : url.getHost().toLowerCase(java.util.Locale.US);
        return "transiva.my.id".equals(host) || host.endsWith(".transiva.my.id");
    }

    private static void applySessionHeaders(HttpURLConnection connection) {
        Context context = TransivaDriverApplication.getAppContext();
        if (context == null) return;
        try {
            SessionManager session = new SessionManager(context);
            String token = clean(session.getToken());
            if (!token.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            connection.setRequestProperty(
                    "X-Device-UUID",
                    DeviceIdentityManager.getInstallationUuid(context)
            );
        } catch (Throwable ignored) {
            // Login/bootstrap calls must remain usable before a session exists.
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
