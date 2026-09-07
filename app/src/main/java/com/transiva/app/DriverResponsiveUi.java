package com.transiva.app;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.TextView;

/** Central responsive-device and system-bar safety policy for driver screens. */
public final class DriverResponsiveUi {
    public enum Profile { COMPACT, SMALL, NORMAL, LARGE, TABLET }
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
        final int pl=content.getPaddingLeft(), pt=content.getPaddingTop(), pr=content.getPaddingRight(), pb=content.getPaddingBottom();
        if (Build.VERSION.SDK_INT >= 23) {
            content.setOnApplyWindowInsetsListener((v, insets) -> {
                int top=0,bottom=0,left=0,right=0;
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    left=bars.left; top=bars.top; right=bars.right; bottom=bars.bottom;
                } else {
                    left=insets.getSystemWindowInsetLeft(); top=insets.getSystemWindowInsetTop(); right=insets.getSystemWindowInsetRight(); bottom=insets.getSystemWindowInsetBottom();
                }
                v.setPadding(Math.max(pl,left), Math.max(pt,top), Math.max(pr,right), Math.max(pb,bottom));
                return insets;
            });
            content.requestApplyInsets();
        }
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