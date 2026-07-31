package com.transiva.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

public class SplashActivity extends Activity {
    private boolean routed;
    private boolean checking;
    private static final int SPLASH_DELAY = 900;
    private TextView statusText;

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

        statusText = new TextView(this);
        statusText.setText("Memeriksa keamanan lokasi...");
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(14f);
        statusText.setGravity(android.view.Gravity.CENTER);
        statusText.setPadding(24, 14, 24, 14);
        statusText.setBackgroundColor(0x99000000);
        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(-1, -2);
        statusLp.gravity = android.view.Gravity.BOTTOM;
        statusLp.setMargins(28, 0, 28, 36);
        layout.addView(statusText, statusLp);
        setContentView(layout);

        new Handler(Looper.getMainLooper()).postDelayed(this::runSecurityCheck, SPLASH_DELAY);
    }

    @Override protected void onResume() {
        super.onResume();
        if (!routed && !checking && statusText != null) {
            new Handler(Looper.getMainLooper()).postDelayed(this::runSecurityCheck, 250L);
        }
    }

    private void runSecurityCheck() {
        if (routed || checking || isFinishing() || isDestroyed()) return;
        checking = true;
        statusText.setText("Memeriksa keamanan lokasi...");
        MockLocationGuard.checkAsync(this, new MockLocationGuard.Callback() {
            @Override public void onSafe() {
                checking = false;
                statusText.setText("Lokasi aman. Membuka aplikasi...");
                new Handler(Looper.getMainLooper()).postDelayed(SplashActivity.this::routeNext, 250L);
            }

            @Override public void onBlocked(String reason) {
                checking = false;
                statusText.setText("Lokasi palsu terdeteksi");
                MockLocationGuard.showBlockingDialog(SplashActivity.this, reason);
            }
        });
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
