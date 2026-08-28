package com.transiva.app;

import android.app.Activity;
import android.os.Bundle;
import android.widget.FrameLayout;
import org.maplibre.android.MapLibre;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapLibreMapOptions;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;

/** Owns MapLibre startup, dual-style failover and lifecycle. */
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
    private boolean started,resumed,created,destroyed,secondaryTried;

    public NavigationMapController(Activity activity, FrameLayout root, NavigationRuntimeConfig config,
                                   NavigationCompatibilityProfile profile, Listener listener){
        this.activity=activity; this.root=root; this.config=config; this.profile=profile; this.listener=listener;
    }
    public void create(Bundle state){
        if(created||destroyed)return; created=true;
        try{
            MapLibre.getInstance(activity.getApplicationContext());
            MapLibreMapOptions o=MapLibreMapOptions.createFromAttributes(activity)
                    .compassEnabled(false).attributionEnabled(false).logoEnabled(false)
                    .rotateGesturesEnabled(false).tiltGesturesEnabled(false)
                    .scrollGesturesEnabled(false).zoomGesturesEnabled(true);
            mapView=new MapView(activity,o); root.addView(mapView,0,new FrameLayout.LayoutParams(-1,-1));
            mapView.onCreate(state);
            if(started) mapView.onStart();
            if(resumed) mapView.onResume();
            mapView.getMapAsync(m->{
                map=m; listener.onMapReady(m);
                try{
                    m.getUiSettings().setCompassEnabled(false); m.getUiSettings().setRotateGesturesEnabled(false);
                    m.getUiSettings().setTiltGesturesEnabled(false); m.getUiSettings().setZoomGesturesEnabled(true);
                    m.getUiSettings().setAttributionEnabled(false); m.getUiSettings().setLogoEnabled(false);
                    loadStyle(config.primaryStyle,false);
                }catch(Throwable t){trySecondary("NAV_STYLE_START_FAILED",t);}
            });
        }catch(Throwable t){listener.onFailure("NAV_MAP_INIT_FAILED",t);}
    }
    private void loadStyle(String uri, boolean secondary){
        if(map==null)return;
        try{map.setStyle(new Style.Builder().fromUri(uri),s->listener.onStyleReady(map,s,secondary));}
        catch(Throwable t){if(!secondary)trySecondary("NAV_PRIMARY_STYLE_FAILED",t);else listener.onFailure("NAV_SECONDARY_STYLE_FAILED",t);}
    }
    public void trySecondary(String stage, Throwable primaryError){
        if(secondaryTried){listener.onFailure(stage,primaryError);return;}
        secondaryTried=true;
        NavigationDiagnostics.error(activity,stage,primaryError);
        NavigationDiagnostics.event(activity,"NAV_STYLE_FAILOVER_SECONDARY",null);
        loadStyle(config.secondaryStyle,true);
    }
    public void onStart(){started=true;try{if(mapView!=null)mapView.onStart();}catch(Throwable t){listener.onFailure("NAV_MAP_ONSTART_FAILED",t);}}
    public void onResume(){resumed=true;try{if(mapView!=null)mapView.onResume();}catch(Throwable t){listener.onFailure("NAV_MAP_ONRESUME_FAILED",t);}}
    public void onPause(){resumed=false;try{if(mapView!=null)mapView.onPause();}catch(Throwable t){listener.onFailure("NAV_MAP_ONPAUSE_FAILED",t);}}
    public void onStop(){started=false;try{if(mapView!=null)mapView.onStop();}catch(Throwable t){listener.onFailure("NAV_MAP_ONSTOP_FAILED",t);}}
    public void onSaveInstanceState(Bundle b){try{if(mapView!=null)mapView.onSaveInstanceState(b);}catch(Throwable t){listener.onFailure("NAV_MAP_SAVE_STATE_FAILED",t);}}
    public void onLowMemory(){try{if(mapView!=null)mapView.onLowMemory();}catch(Throwable t){listener.onFailure("NAV_MAP_LOW_MEMORY_FAILED",t);}}
    public void onDestroy(){destroyed=true;try{if(mapView!=null)mapView.onDestroy();}catch(Throwable t){listener.onFailure("NAV_MAP_ONDESTROY_FAILED",t);}}
    public MapView view(){return mapView;}
}
