package com.transiva.app;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

public final class ForceLogoutManager {
    private ForceLogoutManager() {}

    public static void execute(Context context, String reason) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        String cleanReason = reason == null || reason.trim().isEmpty() ? "SESSION_REVOKED" : reason.trim();

        NativeSessionGuard.clearAndStop(app, cleanReason);
        TransivaSession.logout(app, cleanReason);

        new Handler(Looper.getMainLooper()).post(() -> {
            Intent intent = new Intent(app, LoginActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intent.putExtra("logout_reason", cleanReason);
            app.startActivity(intent);
        });
    }

    public static boolean isForceLogoutCode(String code) {
        if (code == null) return false;
        String c = code.trim().toUpperCase();
        return c.equals("SESSION_REVOKED")
                || c.equals("DEVICE_RESET")
                || c.equals("DEVICE_BANNED")
                || c.equals("DEVICE_MISMATCH")
                || c.equals("SESSION_EXPIRED")
                || c.equals("TOKEN_REVOKED")
                || c.equals("UNAUTHORIZED");
    }
}
