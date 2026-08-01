package com.transiva.app;

import android.location.Location;
import android.os.Build;
import android.os.SystemClock;

/**
 * Adaptive navigation location filter.
 * Rejects stale/teleport fixes, blends noisy fixes by accuracy and speed, and
 * keeps responsive fixes intact while the vehicle is moving.
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
        this.uploadIntervalMs = Math.max(1200L, uploadIntervalMs);
    }

    public synchronized Fix offer(Location incoming) {
        if (!isUsable(incoming)) return null;
        Location raw = new Location(incoming);
        long now = System.currentTimeMillis();

        if (accepted != null) {
            if (isOlder(raw, accepted, 1200L)) return null;

            float jump = accepted.distanceTo(raw);
            long dt = Math.max(250L, fixTime(raw) - fixTime(accepted));
            float impliedMps = jump / (dt / 1000f);
            float oldAcc = accuracy(accepted);
            float newAcc = accuracy(raw);

            if (jump > 250f && dt < 3000L && impliedMps > 70f) return null;
            if (jump > 80f && newAcc > Math.max(80f, oldAcc * 3f) && dt < 8000L) return null;
            if (dt < 6000L && newAcc > Math.max(45f, oldAcc * 2.2f)
                    && jump > Math.max(18f, oldAcc * 1.6f)) return null;
            if (dt < 2500L && newAcc >= 35f && jump > Math.max(28f, newAcc * 0.9f)) return null;

            raw = adaptiveBlend(accepted, raw, jump, dt, oldAcc, newAcc);
        }

        accepted = raw;
        float moved = rendered == null ? Float.MAX_VALUE : rendered.distanceTo(raw);
        boolean render = rendered == null || moved >= renderThreshold(raw);
        if (render) rendered = new Location(raw);

        boolean upload = uploaded == null || now - lastUploadAt >= uploadIntervalMs;
        if (!upload && uploaded != null && uploaded.distanceTo(raw) >= 10f) upload = true;
        if (upload) {
            uploaded = new Location(raw);
            lastUploadAt = now;
        }
        return new Fix(new Location(raw), render, upload, moved);
    }

    private static Location adaptiveBlend(Location previous, Location raw, float jump,
                                          long dtMs, float oldAcc, float newAcc) {
        float speed = raw.hasSpeed() ? Math.max(0f, raw.getSpeed())
                : (dtMs > 0 ? jump / (dtMs / 1000f) : 0f);

        // Good GPS at driving speed stays highly responsive. At low speed or with
        // poor accuracy, trust history more to suppress side-to-side wandering.
        double alpha;
        if (newAcc <= 8f) alpha = speed > 5f ? 0.90d : 0.72d;
        else if (newAcc <= 18f) alpha = speed > 5f ? 0.78d : 0.56d;
        else if (newAcc <= 35f) alpha = speed > 5f ? 0.62d : 0.40d;
        else alpha = speed > 5f ? 0.48d : 0.28d;

        if (jump > Math.max(12f, oldAcc + newAcc)) alpha = Math.min(0.92d, alpha + 0.12d);
        if (speed < 0.7f && jump < Math.max(5f, newAcc * 0.55f)) alpha *= 0.45d;

        Location out = new Location(raw);
        out.setLatitude(previous.getLatitude() + (raw.getLatitude() - previous.getLatitude()) * alpha);
        out.setLongitude(previous.getLongitude() + (raw.getLongitude() - previous.getLongitude()) * alpha);
        if (raw.hasBearing() && previous.hasBearing()) {
            float delta = ((raw.getBearing() - previous.getBearing() + 540f) % 360f) - 180f;
            out.setBearing((previous.getBearing() + (float) (delta * alpha) + 360f) % 360f);
        }
        return out;
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
        float acc = accuracy(l);
        float speed = l.hasSpeed() ? Math.max(0f, l.getSpeed()) : 0f;
        if (speed < 0.8f) return acc <= 15f ? 1.2f : 2.2f;
        if (speed < 5f) return 1.4f;
        if (speed < 15f) return 1.8f;
        return 2.4f;
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
