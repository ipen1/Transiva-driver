package com.transiva.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * Process-level owner for the audible/vibration alert while an incoming call is
 * waiting in a heads-up notification. It does not start activities or bypass
 * Android background-launch restrictions.
 */
public final class IncomingCallAlertManager {
    private static final Object LOCK = new Object();
    private static Ringtone ringtone;
    private static Vibrator vibrator;
    private static String activeCallId = "";

    private IncomingCallAlertManager() {}

    public static void start(Context context, String callId) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        final String id = clean(callId);
        synchronized (LOCK) {
            if (!id.isEmpty() && id.equals(activeCallId) && ringtone != null && ringtone.isPlaying()) {
                return;
            }
            stopLocked();
            activeCallId = id;
            try {
                Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
                ringtone = RingtoneManager.getRingtone(app, uri);
                if (ringtone != null) {
                    if (Build.VERSION.SDK_INT >= 21) {
                        ringtone.setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build());
                    }
                    if (Build.VERSION.SDK_INT >= 28) ringtone.setLooping(true);
                    ringtone.play();
                }
            } catch (Throwable ignored) {
                ringtone = null;
            }

            if (DriverAppSettings.isVibrationEnabled(app)) {
                try {
                    vibrator = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
                    if (vibrator != null && vibrator.hasVibrator()) {
                        long[] pattern = new long[]{0L, 650L, 350L, 650L, 350L, 900L};
                        if (Build.VERSION.SDK_INT >= 26) {
                            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
                        } else {
                            @SuppressWarnings("deprecation")
                            long[] legacy = pattern;
                            vibrator.vibrate(legacy, 0);
                        }
                    }
                } catch (Throwable ignored) {
                    vibrator = null;
                }
            }
        }
    }

    public static void stop(String callId) {
        synchronized (LOCK) {
            String id = clean(callId);
            if (!id.isEmpty() && !activeCallId.isEmpty() && !id.equals(activeCallId)) return;
            stopLocked();
        }
    }

    public static void stopAll() {
        synchronized (LOCK) {
            stopLocked();
        }
    }

    private static void stopLocked() {
        try {
            if (ringtone != null && ringtone.isPlaying()) ringtone.stop();
        } catch (Throwable ignored) {}
        ringtone = null;
        try {
            if (vibrator != null) vibrator.cancel();
        } catch (Throwable ignored) {}
        vibrator = null;
        activeCallId = "";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
