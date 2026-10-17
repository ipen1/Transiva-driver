package com.transiva.app;

import java.net.URI;

/** Pure rules shared by HTTP/image clients and regression tests. */
public final class NetworkSafetyRules {
    private NetworkSafetyRules() { }

    public static boolean maySendCredentials(String value) {
        try {
            URI uri = new URI(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && "transiva.my.id".equalsIgnoreCase(uri.getHost())
                    && uri.getRawUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443);
        } catch (Exception ignored) { return false; }
    }

    public static boolean retryableStatus(int code) {
        // 429 requires a user/server-directed wait, never an immediate retry storm.
        return code == 408 || code == 500 || code == 502 || code == 503 || code == 504;
    }

    public static boolean mayUseAccountCache(String currentOwner, String storedOwner,
                                             long savedAt, long now, long maxAge) {
        if (currentOwner == null || currentOwner.trim().isEmpty() || !currentOwner.equals(storedOwner)) return false;
        long age = now - savedAt;
        return savedAt > 0L && age >= 0L && age <= maxAge;
    }

    public static int bitmapSample(int width, int height, int maxEdge, long maxPixels) {
        if (width <= 0 || height <= 0 || maxEdge <= 0 || maxPixels <= 0) return 1;
        int sample = 1;
        while (sample < (1 << 30)) {
            long w = ((long) width + sample - 1) / sample;
            long h = ((long) height + sample - 1) / sample;
            if (w <= maxEdge && h <= maxEdge && w * h <= maxPixels) break;
            sample *= 2;
        }
        return sample;
    }
}
