package com.transiva.app;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.WeakHashMap;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/** Central responsive-device and system-bar safety policy for driver screens. */
public final class DriverResponsiveUi {
    public enum Profile { COMPACT, SMALL, NORMAL, LARGE, TABLET }
    private static final WeakHashMap<View, int[]> BASE_PADDING = new WeakHashMap<>();
    private DriverResponsiveUi() {}

    public static Profile profile(Context context) {
        int sw = context.getResources().getConfiguration().screenWidthDp;
        if (sw <= 0) sw = Math.round(context.getResources().getDisplayMetrics().widthPixels / context.getResources().getDisplayMetrics().density);
        if (sw <= 320) return Profile.COMPACT;
        if (sw <= 359) return Profile.SMALL;
        if (sw <= 411) return Profile.NORMAL;
        if (sw <= 599) return Profile.LARGE;
        return Profile.TABLET;
    }

    public static int dp(Context c, float dp) { return Math.round(dp * c.getResources().getDisplayMetrics().density); }
    public static float scale(Context c) {
        switch (profile(c)) {
            case COMPACT: return .86f;
            case SMALL: return .92f;
            case LARGE: return 1.06f;
            case TABLET: return 1.16f;
            default: return 1f;
        }
    }
    public static int adaptiveDp(Context c, int normalDp) { return Math.max(1, Math.round(normalDp * scale(c))); }

    public static void apply(Activity activity) {
        if (activity == null) return;
        Window w = activity.getWindow();
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        View decor = w.getDecorView();
        if (Build.VERSION.SDK_INT >= 23) {
            int flags = decor.getSystemUiVisibility();
            decor.setSystemUiVisibility(flags | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
        installInsets(activity);
        View root = decor.findViewById(android.R.id.content);
        if (root != null) root.post(() -> tuneTree(activity, root));
    }

    private static void installInsets(Activity a) {
        if (Build.VERSION.SDK_INT < 21) return;
        View content = a.findViewById(android.R.id.content);
        if (content == null) return;

        final int[] base;
        synchronized (BASE_PADDING) {
            int[] saved = BASE_PADDING.get(content);
            if (saved == null) {
                saved = new int[]{
                        content.getPaddingLeft(), content.getPaddingTop(),
                        content.getPaddingRight(), content.getPaddingBottom()};
                BASE_PADDING.put(content, saved);
            }
            base = saved;
        }
        final int pl = base[0];
        final int pt = base[1];
        final int pr = base[2];
        final int pb = base[3];

        /*
         * targetSdk 35/36 can run edge-to-edge on Android 15/16. On a number of
         * OEM builds SOFT_INPUT_ADJUST_RESIZE by itself no longer guarantees that
         * a bottom composer is moved above the IME. Treat the keyboard as a real
         * window inset, while preserving the existing system-bar/cutout safety.
         *
         * WindowInsetsCompat is intentionally used here instead of API-specific
         * WindowInsets.Type calls so the same policy also works on older devices.
         */
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());

            int safeLeft = Math.max(pl, bars.left);
            int safeTop = Math.max(pt, bars.top);
            int safeRight = Math.max(pr, bars.right);

            // IME inset includes/overlaps the nav-bar area on modern Android.
            // max(), rather than addition, prevents double bottom spacing.
            int safeBottom = Math.max(pb, Math.max(bars.bottom, ime.bottom));

            if (v.getPaddingLeft() != safeLeft
                    || v.getPaddingTop() != safeTop
                    || v.getPaddingRight() != safeRight
                    || v.getPaddingBottom() != safeBottom) {
                v.setPadding(safeLeft, safeTop, safeRight, safeBottom);
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(content);
    }

    private static void tuneTree(Context c, View v) {
        if (v instanceof TextView) {
            TextView t=(TextView)v;
            float fs=c.getResources().getConfiguration().fontScale;
            if (fs > 1.30f) t.setMaxLines(Math.max(t.getMaxLines(), 2));
        }
        if (v instanceof ViewGroup) {
            ViewGroup g=(ViewGroup)v;
            for(int i=0;i<g.getChildCount();i++) tuneTree(c,g.getChildAt(i));
        }
    }
}