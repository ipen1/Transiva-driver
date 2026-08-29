package com.transiva.app;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.os.Build;
import android.util.Rational;

/** Small, OEM-safe Picture-in-Picture policy separated from navigation engine. */
public final class NavigationPipController {
    private final Activity activity;
    private final NavigationCompatibilityProfile profile;

    public NavigationPipController(Activity activity, NavigationCompatibilityProfile profile) {
        this.activity = activity;
        this.profile = profile;
    }

    public boolean allowed() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && (profile == null || profile.allowPip);
    }

    public void configure() {
        if (!allowed()) return;
        try { activity.setPictureInPictureParams(params()); }
        catch (Throwable t) { TransivaDiagnostics.error(activity, "navigation", "PIP_CONFIG_FAILED", t); }
    }

    public void enter() {
        if (!allowed() || activity.isFinishing()) return;
        try {
            if (activity.isInPictureInPictureMode()) return;
            activity.enterPictureInPictureMode(params());
        } catch (Throwable t) { TransivaDiagnostics.error(activity, "navigation", "PIP_ENTER_FAILED", t); }
    }

    public boolean isActive() {
        return allowed() && activity.isInPictureInPictureMode();
    }

    private PictureInPictureParams params() {
        PictureInPictureParams.Builder b = new PictureInPictureParams.Builder().setAspectRatio(new Rational(3, 4));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setAutoEnterEnabled(false);
            b.setSeamlessResizeEnabled(true);
        }
        return b.build();
    }
}
