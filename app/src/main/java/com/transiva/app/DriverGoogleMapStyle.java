package com.transiva.app;

import android.content.Context;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.MapStyleOptions;

final class DriverGoogleMapStyle {
    private DriverGoogleMapStyle() { }

    static void apply(Context context, GoogleMap map) {
        if (map == null) return;
        map.setMapStyle(DriverThemeTokens.isDark(context)
                ? MapStyleOptions.loadRawResourceStyle(context, R.raw.google_map_dark)
                : null);
    }
}
