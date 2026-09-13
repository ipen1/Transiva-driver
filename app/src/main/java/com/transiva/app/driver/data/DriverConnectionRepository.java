package com.transiva.app.driver.data;

import com.transiva.app.DriverHttpTransport;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Stability 4.2 raw-connection compatibility repository.
 *
 * All legacy/native modules that still require streaming, multipart, image, route,
 * or special signaling semantics obtain their connection through this repository.
 * This leaves DriverHttpTransport as an implementation detail of the data layer.
 * New JSON endpoints should prefer DriverApiClient/DriverNetworkRepository instead.
 */
public final class DriverConnectionRepository {
    private DriverConnectionRepository() { }

    public static HttpURLConnection open(String url) throws IOException {
        return DriverHttpTransport.open(url);
    }

    public static HttpURLConnection open(URL url) throws IOException {
        return DriverHttpTransport.open(url);
    }
}
