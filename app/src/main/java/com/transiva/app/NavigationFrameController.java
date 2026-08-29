package com.transiva.app;

import android.os.Handler;
import android.os.SystemClock;
import org.maplibre.android.maps.MapView;

/**
 * Owns the navigation visual loop and enforces a real FPS cap even when MapView
 * posts callbacks every VSYNC. This keeps LOW mode from accidentally rendering at 60/90/120 Hz.
 */
public final class NavigationFrameController {
    public interface Callback { void onVisualFrame(); }

    private final Handler main;
    private final long frameMs;
    private final Callback callback;
    private MapView mapView;
    private boolean running;
    private long lastFrameAt;

    public NavigationFrameController(Handler main, long frameMs, Callback callback) {
        this.main = main;
        this.frameMs = Math.max(16L, frameMs);
        this.callback = callback;
    }

    public void attachMapView(MapView view) { this.mapView = view; }

    public void start() {
        if (running) return;
        running = true;
        lastFrameAt = 0L;
        main.post(tick);
    }

    public void stop() {
        running = false;
        main.removeCallbacks(tick);
        if (mapView != null) mapView.removeCallbacks(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = SystemClock.elapsedRealtime();
            if (lastFrameAt == 0L || now - lastFrameAt >= frameMs) {
                lastFrameAt = now;
                if (callback != null) callback.onVisualFrame();
            }
            if (!running) return;
            MapView mv = mapView;
            if (mv != null) mv.postOnAnimation(this);
            else main.postDelayed(this, frameMs);
        }
    };
}
