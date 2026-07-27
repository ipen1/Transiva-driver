package com.transiva.app;

import android.location.Location;
import android.os.Build;
import android.os.SystemClock;

/**
 * Lightweight location filter for driver navigation/trip.
 * Keeps database updates responsive while preventing stale provider fixes from
 * pulling the vehicle back to an old point.
 */
public final class SmoothLocationEngine {
    public static final class Fix {
        public final Location location;
        public final boolean render;
        public final boolean upload;
        public final float movedFromRendered;

        private Fix(Location location, boolean render, boolean upload, float movedFromRendered) {
            this.location = location;
            this.render = render;
            this.upload = upload;
            this.movedFromRendered = movedFromRendered;
        }
    }

    private Location accepted;
    private Location rendered;
    private Location uploaded;
    private long lastUploadAt;
    private final long uploadIntervalMs;

    public SmoothLocationEngine(long uploadIntervalMs) {
        this.uploadIntervalMs = Math.max(1500L, uploadIntervalMs);
    }

    public synchronized Fix offer(Location incoming) {
        if (!isUsable(incoming)) return null;
        Location next = new Location(incoming);
        long now = System.currentTimeMillis();

        if (accepted != null) {
            if (isOlder(next, accepted, 1200L)) return null;

            float jump = accepted.distanceTo(next);
            long dt = Math.max(250L, fixTime(next) - fixTime(accepted));
            float impliedMps = jump / (dt / 1000f);

            // Reject only obvious stale/teleport fixes. A mock-location test can
            // still move slowly because there is no hard minimum-distance gate.
            if (jump > 250f && dt < 3000L && impliedMps > 70f) return null;

            float oldAcc = accuracy(accepted);
            float newAcc = accuracy(next);
            if (jump > 80f && newAcc > Math.max(80f, oldAcc * 3f) && dt < 8000L) return null;

            // Weak-provider hysteresis: do not let a fresh but much less accurate
            // network fix pull a stable GPS trace sideways. A later consistent fix
            // is still accepted, so navigation recovers naturally in covered areas.
            if (dt < 6000L && newAcc > Math.max(45f, oldAcc * 2.2f)) {
                float allowedJump = Math.max(18f, oldAcc * 1.6f);
                if (jump > allowedJump) return null;
            }
            if (dt < 2500L && newAcc >= 35f && jump > Math.max(28f, newAcc * 0.9f)) return null;
        }

        accepted = next;
        float moved = rendered == null ? Float.MAX_VALUE : rendered.distanceTo(next);
        float threshold = renderThreshold(next);
        boolean render = rendered == null || moved >= threshold;
        if (render) rendered = new Location(next);

        boolean upload = uploaded == null || now - lastUploadAt >= uploadIntervalMs;
        if (!upload && uploaded != null && uploaded.distanceTo(next) >= 10f) upload = true;
        if (upload) {
            uploaded = new Location(next);
            lastUploadAt = now;
        }
        return new Fix(new Location(next), render, upload, moved);
    }

    public synchronized Location latest() {
        return accepted == null ? null : new Location(accepted);
    }

    public synchronized void markUploaded(Location location) {
        if (location == null) return;
        uploaded = new Location(location);
        lastUploadAt = System.currentTimeMillis();
    }

    private static float renderThreshold(Location l) {
        // Visual marker must stay alive even at walking / slow mock-GPS speed.
        // The JS layer performs interpolation, so Java only suppresses tiny GPS jitter.
        float acc = accuracy(l);
        float speed = l.hasSpeed() ? Math.max(0f, l.getSpeed()) : 0f;
        if (speed < 0.8f) return acc <= 15f ? 1.5f : 2.5f;
        if (speed < 5f) return 1.8f;
        if (speed < 15f) return 2.5f;
        return 3.5f;
    }

    private static boolean isUsable(Location l) {
        if (l == null) return false;
        double lat = l.getLatitude(), lng = l.getLongitude();
        if (!Double.isFinite(lat) || !Double.isFinite(lng) || lat == 0d || lng == 0d) return false;
        if (Math.abs(lat) > 90d || Math.abs(lng) > 180d) return false;
        if (accuracy(l) > 250f) return false;
        if (Build.VERSION.SDK_INT >= 17 && l.getElapsedRealtimeNanos() > 0) {
            long ageMs = (SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos()) / 1_000_000L;
            if (ageMs > 30_000L) return false;
        }
        return true;
    }

    private static boolean isOlder(Location a, Location b, long toleranceMs) {
        return fixTime(a) + toleranceMs < fixTime(b);
    }

    private static long fixTime(Location l) {
        if (Build.VERSION.SDK_INT >= 17 && l.getElapsedRealtimeNanos() > 0) {
            return l.getElapsedRealtimeNanos() / 1_000_000L;
        }
        return l.getTime() > 0 ? l.getTime() : System.currentTimeMillis();
    }

    private static float accuracy(Location l) {
        return l != null && l.hasAccuracy() ? Math.max(1f, l.getAccuracy()) : 50f;
    }
}
