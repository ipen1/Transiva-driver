package com.transiva.app;

import android.app.Activity;
import android.os.Bundle;
import android.widget.FrameLayout;
import org.maplibre.android.MapLibre;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapLibreMapOptions;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;

/**
 * Owns MapLibre startup, style failover and lifecycle.
 *
 * P0 OEM stability rules:
 * - every lifecycle transition is idempotent;
 * - delayed MapLibre callbacks are ignored after destroy;
 * - renderer/style failures are reported but never finish the host Activity;
 * - create/start/resume ordering is safe even when map initialization is deferred.
 */
public final class NavigationMapController {
    public interface Listener {
        void onMapReady(MapLibreMap map);
        void onStyleReady(MapLibreMap map, Style style, boolean secondary);
        void onFailure(String stage, Throwable error);
    }

    private final Activity activity;
    private final FrameLayout root;
    private final NavigationRuntimeConfig config;
    private final NavigationCompatibilityProfile profile;
    private final Listener listener;

    private MapView mapView;
    private MapLibreMap map;
    private boolean hostStarted;
    private boolean hostResumed;
    private boolean viewStarted;
    private boolean viewResumed;
    private boolean created;
    private boolean destroyed;
    private boolean secondaryTried;

    public NavigationMapController(Activity activity, FrameLayout root, NavigationRuntimeConfig config,
                                   NavigationCompatibilityProfile profile, Listener listener) {
        this.activity = activity;
        this.root = root;
        this.config = config;
        this.profile = profile;
        this.listener = listener;
    }

    public void create(Bundle state) {
        if (created || destroyed) return;
        created = true;
        try {
            MapLibre.getInstance(activity.getApplicationContext());
            MapLibreMapOptions options = MapLibreMapOptions.createFromAttributes(activity)
                    .compassEnabled(false)
                    .attributionEnabled(false)
                    .logoEnabled(false)
                    .rotateGesturesEnabled(false)
                    .tiltGesturesEnabled(false)
                    .scrollGesturesEnabled(false)
                    .zoomGesturesEnabled(true);

            mapView = new MapView(activity, options);
            root.addView(mapView, 0, new FrameLayout.LayoutParams(-1, -1));
            mapView.onCreate(state);
            syncLifecycle();

            mapView.getMapAsync(m -> {
                if (destroyed || activity.isFinishing()) return;
                map = m;
                safeMapReady(m);
                try {
                    m.getUiSettings().setCompassEnabled(false);
                    m.getUiSettings().setRotateGesturesEnabled(false);
                    m.getUiSettings().setTiltGesturesEnabled(false);
                    m.getUiSettings().setZoomGesturesEnabled(true);
                    m.getUiSettings().setAttributionEnabled(false);
                    m.getUiSettings().setLogoEnabled(false);
                    loadStyle(config.primaryStyle, false);
                } catch (Throwable t) {
                    trySecondary("NAV_STYLE_START_FAILED", t);
                }
            });
        } catch (Throwable t) {
            safeFailure("NAV_MAP_INIT_FAILED", t);
        }
    }

    private void safeMapReady(MapLibreMap m) {
        if (destroyed || activity.isFinishing()) return;
        try { listener.onMapReady(m); }
        catch (Throwable t) { NavigationDiagnostics.error(activity, "NAV_MAP_READY_CALLBACK_FAILED", t); }
    }

    private void loadStyle(String uri, boolean secondary) {
        if (map == null || destroyed || activity.isFinishing()) return;
        try {
            map.setStyle(new Style.Builder().fromUri(uri), style -> {
                if (destroyed || activity.isFinishing()) return;
                try { listener.onStyleReady(map, style, secondary); }
                catch (Throwable t) { safeFailure("NAV_STYLE_READY_CALLBACK_FAILED", t); }
            });
        } catch (Throwable t) {
            if (!secondary) trySecondary("NAV_PRIMARY_STYLE_FAILED", t);
            else safeFailure("NAV_SECONDARY_STYLE_FAILED", t);
        }
    }

    public void trySecondary(String stage, Throwable primaryError) {
        if (destroyed || activity.isFinishing()) return;
        if (secondaryTried) {
            safeFailure(stage, primaryError);
            return;
        }
        secondaryTried = true;
        NavigationDiagnostics.error(activity, stage, primaryError);
        NavigationDiagnostics.event(activity, "NAV_STYLE_FAILOVER_SECONDARY", null);
        loadStyle(config.secondaryStyle, true);
    }

    public void onStart() {
        if (destroyed || hostStarted) return;
        hostStarted = true;
        syncLifecycle();
    }

    public void onResume() {
        if (destroyed || hostResumed) return;
        hostResumed = true;
        syncLifecycle();
    }

    public void onPause() {
        if (destroyed || !hostResumed) return;
        hostResumed = false;
        if (mapView != null && viewResumed) {
            try { mapView.onPause(); }
            catch (Throwable t) { safeFailure("NAV_MAP_ONPAUSE_FAILED", t); }
            viewResumed = false;
        }
    }

    public void onStop() {
        if (destroyed || !hostStarted) return;
        if (hostResumed) onPause();
        hostStarted = false;
        if (mapView != null && viewStarted) {
            try { mapView.onStop(); }
            catch (Throwable t) { safeFailure("NAV_MAP_ONSTOP_FAILED", t); }
            viewStarted = false;
        }
    }

    private void syncLifecycle() {
        if (destroyed || mapView == null) return;
        if (hostStarted && !viewStarted) {
            try { mapView.onStart(); viewStarted = true; }
            catch (Throwable t) { safeFailure("NAV_MAP_ONSTART_FAILED", t); }
        }
        if (hostResumed && hostStarted && !viewResumed) {
            try { mapView.onResume(); viewResumed = true; }
            catch (Throwable t) { safeFailure("NAV_MAP_ONRESUME_FAILED", t); }
        }
    }

    public void onSaveInstanceState(Bundle state) {
        if (destroyed) return;
        try { if (mapView != null) mapView.onSaveInstanceState(state); }
        catch (Throwable t) { safeFailure("NAV_MAP_SAVE_STATE_FAILED", t); }
    }

    public void onLowMemory() {
        if (destroyed) return;
        try { if (mapView != null) mapView.onLowMemory(); }
        catch (Throwable t) { safeFailure("NAV_MAP_LOW_MEMORY_FAILED", t); }
    }

    public void onDestroy() {
        if (destroyed) return;
        destroyed = true;
        try {
            if (mapView != null) {
                if (viewResumed) { try { mapView.onPause(); } catch (Throwable ignored) {} viewResumed = false; }
                if (viewStarted) { try { mapView.onStop(); } catch (Throwable ignored) {} viewStarted = false; }
                mapView.onDestroy();
            }
        } catch (Throwable t) {
            NavigationDiagnostics.error(activity, "NAV_MAP_ONDESTROY_FAILED", t);
        } finally {
            map = null;
            mapView = null;
        }
    }

    private void safeFailure(String stage, Throwable error) {
        if (destroyed) return;
        try { listener.onFailure(stage, error); }
        catch (Throwable callbackError) { NavigationDiagnostics.error(activity, stage + "_REPORT_FAILED", callbackError); }
    }

    public MapView view() { return mapView; }
}
