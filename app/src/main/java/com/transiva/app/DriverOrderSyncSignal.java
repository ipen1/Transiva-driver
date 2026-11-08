package com.transiva.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import androidx.core.content.ContextCompat;
import org.json.JSONObject;
import java.util.function.Supplier;

final class DriverOrderSyncSignal extends BroadcastReceiver {
    private final Context context;
    private final Supplier<JSONObject> order;
    private final Runnable refresh;
    private boolean registered;

    DriverOrderSyncSignal(Context context, Supplier<JSONObject> order, Runnable refresh) {
        this.context = context.getApplicationContext();
        this.order = order;
        this.refresh = refresh;
    }

    static boolean assignmentEnded(Exception error) {
        return error instanceof com.transiva.app.driver.data.DriverApiClient.ApiException
                && "ORDER_ASSIGNMENT_ENDED".equals(((com.transiva.app.driver.data.DriverApiClient.ApiException) error).code);
    }

    void active(boolean enabled) {
        if (enabled == registered) return;
        try {
            if (enabled) {
                ContextCompat.registerReceiver(context, this,
                        new IntentFilter(TransivaFirebaseService.ACTION_DRIVER_DATA_CHANGED),
                        ContextCompat.RECEIVER_NOT_EXPORTED);
            } else {
                context.unregisterReceiver(this);
            }
            registered = enabled;
        } catch (RuntimeException error) {
            registered = false;
            TransivaDiagnostics.error(context, "order", "ORDER_SIGNAL_RECEIVER_FAILED", error);
        }
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (!"driver_order_assignment_ended".equals(intent.getStringExtra("type"))) return;
        JSONObject current = order.get();
        String id = intent.getStringExtra("order_id");
        String source = intent.getStringExtra("source");
        if (current != null && id != null && !id.isEmpty()
                && NavigationTargetPolicy.source(current).equals(source)
                && (id.equals(NavigationTargetPolicy.id(current)) || id.equals(current.optString("id")))) {
            refresh.run();
        }
    }
}
