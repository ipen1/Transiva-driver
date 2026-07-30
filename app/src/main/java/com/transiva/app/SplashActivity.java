package com.transiva.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.FrameLayout;
import android.widget.ImageView;

public class SplashActivity extends Activity {
    private boolean routed;
    private static final int SPLASH_DELAY = 1200;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout layout = new FrameLayout(this);
        layout.setBackgroundColor(Color.parseColor("#020617"));
        ImageView splash = new ImageView(this);
        int res = getDrawableId("splash_screen");
        if (res == 0) res = getDrawableId("transiva_logo");
        if (res == 0) res = getApplicationInfo().icon;
        splash.setImageResource(res);
        splash.setScaleType(ImageView.ScaleType.CENTER_CROP);
        layout.addView(splash, new FrameLayout.LayoutParams(-1, -1));
        setContentView(layout);
        new Handler(Looper.getMainLooper()).postDelayed(this::routeNext, SPLASH_DELAY);
    }

    private void routeNext() {
        if (routed) return;
        routed = true;
        SessionManager session = new SessionManager(this);
        Intent intent;
        if (!session.isLoggedIn() || !"driver".equalsIgnoreCase(session.getRole())) {
            if (session.isLoggedIn()) session.forceLogout("driver_app_role_rejected");
            intent = new Intent(this, LoginActivity.class);
        } else {
            intent = new Intent(this, PinActivity.class);
            intent.putExtra("native_role", "driver");
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private int getDrawableId(String name) {
        try { return getResources().getIdentifier(name, "drawable", getPackageName()); }
        catch (Exception ignored) { return 0; }
    }
}
