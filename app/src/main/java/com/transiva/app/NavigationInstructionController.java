package com.transiva.app;

import android.os.SystemClock;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/** Owns maneuver selection and instruction text rendering. */
public final class NavigationInstructionController {
    private final TextView badge;
    private long lastUiAt;

    public NavigationInstructionController(TextView badge) { this.badge = badge; }

    public void update(JSONArray maneuvers, double progressMeters) {
        if (badge == null) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastUiAt < 250L) return;
        lastUiAt = now;
        JSONObject next = null;
        double remaining = Double.MAX_VALUE;
        JSONArray list = maneuvers == null ? new JSONArray() : maneuvers;
        for (int i = 0; i < list.length(); i++) {
            JSONObject m = list.optJSONObject(i);
            if (m == null) continue;
            double at = m.optDouble("distance_from_start", -1d);
            if (at < 0d) continue;
            double d = at - progressMeters;
            if (d >= -8d && d < remaining) { remaining = Math.max(0d, d); next = m; }
        }
        if (next == null) { badge.setText("↑ Terus ikuti rute"); return; }
        String modifier = next.optString("modifier", "").toLowerCase(Locale.US);
        String type = next.optString("type", "").toLowerCase(Locale.US);
        String road = next.optString("name", "").trim();
        String arrow = "↑", action = "Terus lurus";
        if (modifier.contains("left")) { arrow = "↰"; action = "Belok kiri"; }
        else if (modifier.contains("right")) { arrow = "↱"; action = "Belok kanan"; }
        else if (modifier.contains("uturn")) { arrow = "↶"; action = "Putar balik"; }
        else if (type.contains("arrive")) { arrow = "⚑"; action = "Tiba di pengantaran"; }
        String dist;
        if (remaining >= 1000d) dist = String.format(Locale.US, "%.1f km", remaining / 1000d);
        else if (remaining >= 100d) dist = String.format(Locale.US, "%.0f m", Math.round(remaining / 50d) * 50d);
        else dist = String.format(Locale.US, "%.0f m", Math.round(remaining / 10d) * 10d);
        String text = arrow + " " + dist + " • " + action;
        if (!road.isEmpty()) text += "\n" + road;
        badge.setText(text);
    }
}
