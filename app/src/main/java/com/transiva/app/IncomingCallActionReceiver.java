package com.transiva.app;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

/** Handles the Reject action without opening the UI. */
public class IncomingCallActionReceiver extends BroadcastReceiver {
    public static final String ACTION_REJECT = "com.transiva.app.action.REJECT_INCOMING_CALL";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || !ACTION_REJECT.equals(intent.getAction())) return;
        final Context app = context.getApplicationContext();
        final String callId = clean(intent.getStringExtra("call_id"));
        final int notificationId = intent.getIntExtra("notification_id",
                Math.abs(("webrtc_call|" + callId).hashCode()));

        IncomingCallAlertManager.stop(callId);
        try {
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(notificationId);
        } catch (Throwable ignored) {}

        final PendingResult pending = goAsync();
        DriverNetworkExecutor.execute(() -> {
            try {
                if (!callId.isEmpty()) {
                    SessionManager session = new SessionManager(app);
                    JSONObject p = new JSONObject();
                    p.put("action", "reject");
                    p.put("role", "driver");
                    p.put("call_id", callId);
                    WebRtcSignalApi.post(session, p);
                }
            } catch (Throwable t) {
                TransivaDiagnostics.error(app, "call", "NOTIFICATION_REJECT_FAILED", t);
            } finally {
                try { pending.finish(); } catch (Throwable ignored) {}
            }
        });
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
