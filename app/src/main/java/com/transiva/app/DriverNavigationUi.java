package com.transiva.app;

import android.graphics.drawable.GradientDrawable;

public final class DriverNavigationUi {
    private DriverNavigationUi() {}
    public static GradientDrawable roundRect(int color, float radiusPx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radiusPx);
        return drawable;
    }
}
