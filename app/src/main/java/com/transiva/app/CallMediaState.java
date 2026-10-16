package com.transiva.app;

/** Media transport is the source of truth; HTTP success alone never means connected. */
final class CallMediaState {
    private CallMediaState() { }
    static boolean isConnected(String ice, String transport) {
        // Either layer can detect an outage first. Do not trust a stale connected
        // value from the other layer while one explicitly reports an outage.
        if (unavailable(ice) || unavailable(transport)) return false;
        return "CONNECTED".equals(ice) || "COMPLETED".equals(ice)
                || "CONNECTED".equals(transport);
    }
    private static boolean unavailable(String state) {
        return "DISCONNECTED".equals(state) || "FAILED".equals(state) || "CLOSED".equals(state);
    }
}
