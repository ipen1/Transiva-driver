package com.transiva.app;

/** Single source of truth for Transiva Driver API endpoints. */
public final class DriverApiConfig {
    public static final String BASE_URL = "https://transiva.my.id/server/";
    private DriverApiConfig() {}
    public static String endpoint(String path) {
        String p = path == null ? "" : path.trim();
        while (p.startsWith("/")) p = p.substring(1);
        return BASE_URL + p;
    }
}
