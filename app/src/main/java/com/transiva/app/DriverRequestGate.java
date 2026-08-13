package com.transiva.app;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Gate idempotensi sisi aplikasi. Backend tetap harus menjamin transaksi atomik. */
public final class DriverRequestGate {
    private static final ConcurrentHashMap<String, AtomicBoolean> GATES = new ConcurrentHashMap<>();
    private DriverRequestGate() {}

    public static boolean enter(String key) {
        String safe = key == null ? "" : key;
        return GATES.computeIfAbsent(safe, k -> new AtomicBoolean(false))
                .compareAndSet(false, true);
    }

    public static void leave(String key) {
        String safe = key == null ? "" : key;
        AtomicBoolean gate = GATES.get(safe);
        if (gate != null) {
            gate.set(false);
            GATES.remove(safe, gate); // jangan biarkan key order lama menumpuk selamanya
        }
    }

    public static int activeGateCount() { return GATES.size(); }
}
