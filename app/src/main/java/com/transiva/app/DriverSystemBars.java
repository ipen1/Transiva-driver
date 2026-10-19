package com.transiva.app;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.Window;
import androidx.core.graphics.Insets;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import java.util.WeakHashMap;

/** Draw system-bar protection as app content, independent of deprecated Window colors. */
public final class DriverSystemBars {
    private static final WeakHashMap<Window, Protection> PROTECTIONS = new WeakHashMap<>();
    private DriverSystemBars() { }

    public static void install(Activity activity) {
        Window window = activity.getWindow();
        if (PROTECTIONS.containsKey(window)) return;
        WindowCompat.enableEdgeToEdge(window);
        View decor = window.getDecorView();
        Protection protection = new Protection();
        PROTECTIONS.put(window, protection);
        decor.getOverlay().add(protection);
        protection.setBounds(0, 0, decor.getWidth(), decor.getHeight());
        decor.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                protection.setBounds(0, 0, r - l, b - t));
        updateIcons(window, protection);
    }

    public static void setStatusBarColor(Activity activity, int color) {
        install(activity);
        Protection p = PROTECTIONS.get(activity.getWindow());
        p.statusColor = color;
        updateIcons(activity.getWindow(), p);
        p.invalidateSelf();
    }

    public static void setNavigationBarColor(Activity activity, int color) {
        install(activity);
        Protection p = PROTECTIONS.get(activity.getWindow());
        p.navigationColor = Build.VERSION.SDK_INT < 26 && light(color) ? 0xFF071426 : color;
        updateIcons(activity.getWindow(), p);
        p.invalidateSelf();
    }

    static void updateInsets(Activity activity, Insets bars, boolean pip) {
        install(activity);
        Protection p = PROTECTIONS.get(activity.getWindow());
        p.bars = pip ? Insets.NONE : bars;
        p.invalidateSelf();
    }

    private static void updateIcons(Window window, Protection p) {
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, window.getDecorView());
        controller.setAppearanceLightStatusBars(light(p.statusColor));
        controller.setAppearanceLightNavigationBars(light(p.navigationColor));
    }

    private static boolean light(int color) {
        return .2126 * Color.red(color) + .7152 * Color.green(color) + .0722 * Color.blue(color) > 160;
    }

    private static final class Protection extends Drawable {
        private final Paint paint = new Paint();
        private Insets bars = Insets.NONE;
        private int statusColor = 0xFF071426, navigationColor = 0xFF071426;
        @Override public void draw(Canvas canvas) {
            int w = getBounds().width(), h = getBounds().height();
            paint.setColor(statusColor);
            canvas.drawRect(0, 0, w, bars.top, paint);
            paint.setColor(navigationColor);
            canvas.drawRect(0, h - bars.bottom, w, h, paint);
            canvas.drawRect(0, bars.top, bars.left, h - bars.bottom, paint);
            canvas.drawRect(w - bars.right, bars.top, w, h - bars.bottom, paint);
        }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter filter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
