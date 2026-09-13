package com.transiva.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Keeps last known valid profile/document URLs so transient empty API fields do not blank the UI. */
public final class DriverProfileMediaCache {
    private static final String PREF = "driver_profile_media_cache_v1";
    private DriverProfileMediaCache() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static String userKey(SessionManager session) {
        if (session == null) return "driver";
        String id = clean(session.getId());
        if (id.isEmpty()) id = clean(session.getUsername());
        return id.isEmpty() ? "driver" : id.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    public static String resolve(Context c, SessionManager session, String field, String incoming) {
        String key = userKey(session) + ":" + field;
        String value = clean(incoming);
        if (!value.isEmpty()) {
            prefs(c).edit().putString(key, value).apply();
            return value;
        }
        return clean(prefs(c).getString(key, ""));
    }

    public static void clearFor(Context c, SessionManager session) {
        String prefix = userKey(session) + ":";
        SharedPreferences p = prefs(c);
        SharedPreferences.Editor e = p.edit();
        for (String key : p.getAll().keySet()) if (key.startsWith(prefix)) e.remove(key);
        e.apply();
    }

    private static String clean(String s) { return s == null ? "" : s.trim(); }
}
