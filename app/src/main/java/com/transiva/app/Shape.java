package com.transiva.app;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

public final class Shape {
    private Shape() {}

    public static GradientDrawable round(String color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor(color));
        d.setCornerRadius(radius);
        return d;
    }

    public static GradientDrawable roundStroke(String fill, String stroke, int radius, int strokeDp) {
        GradientDrawable d = round(fill, radius);
        d.setStroke(strokeDp, Color.parseColor(stroke));
        return d;
    }

    public static GradientDrawable gradient(String start, String end, int radius) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.parseColor(start), Color.parseColor(end)}
        );
        d.setCornerRadius(radius);
        return d;
    }
}
