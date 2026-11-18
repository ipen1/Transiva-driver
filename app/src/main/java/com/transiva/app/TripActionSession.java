package com.transiva.app;

import android.content.Context;
import android.location.Location;
import android.os.SystemClock;
import org.json.JSONObject;
import java.util.LinkedHashMap;

/** Process-local handoff of verified order state, raw GPS, and action ownership. */
final class TripActionSession {
    private static String owner = "";
    private static final LinkedHashMap<String, TripActionSession> SESSIONS = new LinkedHashMap<>();
    private JSONObject snapshot;
    private Location fix;
    private long syncedAt = -1;
    private long version;
    private boolean busy;
    private long busyAt;
    private final java.util.Set<Runnable> listeners = new java.util.HashSet<>();

    static synchronized TripActionSession get(Context context, JSONObject order) {
        String login = NavigationSessionStore.identity(context);
        if (!owner.equals(login)) { SESSIONS.clear(); owner = login; }
        String id = order == null ? "" : order.optString("id");
        for (TripActionSession cached : SESSIONS.values()) {
            if (NavigationTargetPolicy.same(cached.snapshot, order)) return cached;
        }
        String key = NavigationTargetPolicy.source(order) + "|" + (id.isEmpty() ? NavigationTargetPolicy.id(order) : id);
        TripActionSession session = SESSIONS.get(key);
        if (session == null) {
            session = new TripActionSession();
            session.snapshot = copy(order);
            SESSIONS.put(key, session);
        }
        return session;
    }

    synchronized void listen(Runnable listener, boolean enabled) {
        if (enabled) listeners.add(listener); else listeners.remove(listener);
    }

    private void changed() {
        for (Runnable listener : new java.util.ArrayList<>(listeners)) listener.run();
    }

    synchronized long version() { return version; }
    synchronized JSONObject order() { return copy(snapshot); }
    synchronized long syncedAt() { return syncedAt; }
    synchronized boolean fresh() { return syncedAt >= 0 && SystemClock.elapsedRealtime() - syncedAt <= 15000; }

    synchronized void synced(JSONObject order) {
        snapshot = merge(snapshot, order);
        version++; // A later accepted poll also invalidates older concurrent responses.
        syncedAt = SystemClock.elapsedRealtime();
        changed();
    }

    synchronized void fix(Location raw) {
        Location verified = TripArrivalLocation.verified(raw);
        if (verified != null && (fix == null || verified.getElapsedRealtimeNanos() >= fix.getElapsedRealtimeNanos())) {
            fix = verified;
            changed();
        }
    }

    synchronized Location fix() { return TripArrivalLocation.verified(fix); }
    synchronized boolean busy() {
        if (busy && SystemClock.elapsedRealtime() - busyAt > 60000) { busy = false; syncedAt = -1; version++; }
        return busy;
    }
    synchronized boolean begin() {
        if (busy() || !fresh()) return false;
        busy = true; busyAt = SystemClock.elapsedRealtime(); version++;
        changed();
        return true;
    }
    synchronized void completed(JSONObject order) {
        snapshot = copy(order); busy = false; syncedAt = SystemClock.elapsedRealtime(); version++;
        changed();
    }
    synchronized void failed() { busy = false; syncedAt = -1; version++; changed(); }

    static JSONObject copy(JSONObject value) {
        try { return value == null ? new JSONObject() : new JSONObject(value.toString()); }
        catch (org.json.JSONException invalid) { return new JSONObject(); }
    }
    static JSONObject merge(JSONObject previous, JSONObject next) {
        JSONObject out = copy(previous);
        if (next == null) return out;
        try {
            java.util.Iterator<String> keys = next.keys();
            while (keys.hasNext()) { String key = keys.next(); out.put(key, next.opt(key)); }
        } catch (org.json.JSONException ignored) {}
        return out;
    }
}
