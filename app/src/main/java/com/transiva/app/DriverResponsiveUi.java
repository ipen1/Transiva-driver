package com.transiva.app;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.view.View;
import android.view.WindowManager;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import java.util.WeakHashMap;

/** One owner of system bars, cutouts and IME insets for every driver Activity. */
public final class DriverResponsiveUi {
    public enum Profile { COMPACT, SMALL, NORMAL, LARGE, TABLET }
    private static final WeakHashMap<View, int[]> BASE_PADDING = new WeakHashMap<>();
    private DriverResponsiveUi() { }

    public static int availableWidth(Context context) {
        if (context instanceof Activity) {
            View content = ((Activity) context).findViewById(android.R.id.content);
            if (content != null && content.getWidth() > 0)
                return Math.max(1, content.getWidth() - content.getPaddingLeft() - content.getPaddingRight());
        }
        return Math.max(1, Math.round(context.getResources().getConfiguration().screenWidthDp
                * context.getResources().getDisplayMetrics().density));
    }

    public static Profile profile(Context context) {
        int sw = Math.round(availableWidth(context) / context.getResources().getDisplayMetrics().density);
        if (sw <= 320) return Profile.COMPACT;
        if (sw <= 359) return Profile.SMALL;
        if (sw <= 411) return Profile.NORMAL;
        if (sw <= 599) return Profile.LARGE;
        return Profile.TABLET;
    }
    public static int dp(Context c, float value) { return Math.round(value * c.getResources().getDisplayMetrics().density); }
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
        DriverSystemBars.install(activity);
        activity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        View content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        content.setBackgroundColor(DriverThemeTokens.background(activity));
        if (!BASE_PADDING.containsKey(content)) {
            int[] base = {content.getPaddingLeft(), content.getPaddingTop(), content.getPaddingRight(), content.getPaddingBottom()};
            BASE_PADDING.put(content, base);
            ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
                boolean pip = Build.VERSION.SDK_INT >= 26 && activity.isInPictureInPictureMode();
                Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
                Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
                int maxWidth = fullWidth(activity) ? 0 : dp(activity, 840);
                int[] p = DriverWindowGeometry.padding(base, bars.left, bars.top, bars.right, bars.bottom,
                        ime.bottom, pip, v.getWidth(), maxWidth);
                if (v.getPaddingLeft() != p[0] || v.getPaddingTop() != p[1]
                        || v.getPaddingRight() != p[2] || v.getPaddingBottom() != p[3])
                    v.setPadding(p[0], p[1], p[2], p[3]);
                DriverSystemBars.updateInsets(activity, insets.getInsets(WindowInsetsCompat.Type.systemBars()), pip);
                // Descendants are already in the safe content rectangle. Never apply bars/IME twice.
                return new WindowInsetsCompat.Builder(insets)
                        .setInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
                                | WindowInsetsCompat.Type.ime(), Insets.NONE)
                        .setDisplayCutout(null).build();
            });
            content.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (r - l != or - ol || b - t != ob - ot) ViewCompat.requestApplyInsets(v);
                if (r - l != or - ol) resizeBubbles(activity, v);
            });
        }
        ViewCompat.requestApplyInsets(content);
    }

    private static void resizeBubbles(Activity activity, View view) {
        if (view instanceof android.widget.TextView && "transiva_chat_bubble".equals(view.getTag()))
            ((android.widget.TextView)view).setMaxWidth(Math.min(dp(activity,520), Math.round(availableWidth(activity)*.75f)));
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group=(android.view.ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++) resizeBubbles(activity,group.getChildAt(i));
        }
    }

    private static boolean fullWidth(Activity activity) {
        return activity instanceof TransivaCallUiActivity || activity instanceof DriverNavigationActivity
                || activity instanceof DriverLocationActivity || activity instanceof ChatCameraActivity;
    }
}
