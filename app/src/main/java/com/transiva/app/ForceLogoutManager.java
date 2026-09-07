package com.transiva.app;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.google.firebase.messaging.FirebaseMessaging;

public final class ForceLogoutManager {
    private ForceLogoutManager() {}

    public static void execute(Context context, String reason) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        String cleanReason = reason == null || reason.trim().isEmpty() ? "SESSION_REVOKED" : reason.trim();

        DriverChatNotificationPoller.stop();
        DriverServiceController.stopAll(app);
        NativeSessionGuard.clearAndStop(app, cleanReason);
        TransivaSession.logout(app, cleanReason);

        // Force logout karena session handover/reset harus benar-benar memutus FCM
        // perangkat lama. Server sudah lebih dulu menghapus association FCM-nya.
        try {
            app.getSharedPreferences("transiva_fcm", Context.MODE_PRIVATE)
                    .edit().remove("fcm_token").remove("fcm_token_saved_at").apply();
            new SessionManager(app).put("fcm_token", "");
            FirebaseMessaging.getInstance().deleteToken();
        } catch (Throwable ignored) {
            // Session server tetap sudah revoked walaupun Firebase lokal gagal dibersihkan.
        }

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
                || c.equals("SESSION_REPLACED");
    }
}
