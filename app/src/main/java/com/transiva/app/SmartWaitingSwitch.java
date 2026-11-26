package com.transiva.app;

import android.app.Activity;
import android.location.Location;
import android.view.View;
import android.widget.Toast;
import android.widget.Switch;
import com.transiva.app.driver.data.DriverApiClient;
import org.json.JSONObject;

final class SmartWaitingSwitch extends Switch {
    interface Confirmed {
        void apply(String orderId, JSONObject waiting);
    }

    private final java.util.function.Supplier<Location> location;
    private JSONObject state;
    private boolean busy;

    SmartWaitingSwitch(Activity host, java.util.function.Supplier<Location> location) {
        super(host);
        this.location = location;
        setMinHeight(Math.round(44 * host.getResources().getDisplayMetrics().density));
        setTextColor(DriverThemeTokens.textPrimary(host));
        setTextSize(13);
        setVisibility(View.GONE);
    }

    void bind(JSONObject waiting) {
        state = waiting;
        JSONObject control = waiting == null ? null : waiting.optJSONObject("control");
        if (control == null) {
            setVisibility(View.GONE);
            return;
        }
        boolean running = !control.isNull("started_at") && control.optLong("started_at") > 0
                && control.isNull("ended_at");
        JSONObject point=control.optJSONObject("point");
        Location fix=location.get();
        boolean nearby=false;
        if (point!=null && fix!=null) {
            float[] distance=new float[1];
            Location.distanceBetween(fix.getLatitude(),fix.getLongitude(),
                    point.optDouble("lat"),point.optDouble("lng"),distance);
            nearby=distance[0]<=200;
        }
        setVisibility(running || nearby ? View.VISIBLE : View.GONE);
        setChecked(running);
        boolean ready=control.optBoolean("ready");
        setEnabled(!busy && ready);
        setText(ready ? (running ? "Tunggu aktif · " : "Waktu tunggu · ") + control.optString("label")
                : "Waktu tunggu · Menunggu konfirmasi Merchant");
    }

    void request(Activity host, SessionManager session, JSONObject order, Location fix, Confirmed confirmed) {
        JSONObject control = state == null ? null : state.optJSONObject("control");
        if (busy || control == null || order == null) return;
        boolean enable = !control.isNull("ended_at");
        bind(state);
        if (enable && fix == null) {
            Toast.makeText(host, "Tunggu sampai GPS akurat, lalu aktifkan kembali.", Toast.LENGTH_LONG).show();
            return;
        }
        String id = NavigationTargetPolicy.id(order);
        long dbId = order.optLong("id");
        String source = NavigationTargetPolicy.source(order);
        long waitingId = control.optLong("id");
        DriverApiClient client = new DriverApiClient(session);
        busy = true;
        setEnabled(false);
        if (!DriverNetworkExecutor.executeOrderAction(() -> {
            JSONObject result = null;
            String error = "";
            try {
                JSONObject payload = new JSONObject().put("action", "set_waiting").put("source", source)
                        .put("order_id", id).put("order_db_id", dbId)
                        .put("session_id", waitingId).put("enabled", enable);
                if (fix != null) payload.put("driver_lat", fix.getLatitude()).put("driver_lng", fix.getLongitude());
                JSONObject response = client.post("smart_waiting.php", payload).body;
                result = response.optJSONObject("smart_waiting");
                if (!response.optBoolean("success") || result == null) {
                    throw new IllegalStateException(response.optString("message", "Waktu tunggu belum tersimpan."));
                }
            } catch (Exception failure) {
                error = CallPeerIdentity.first(failure.getMessage(), "Waktu tunggu belum tersimpan. Coba lagi.");
            }
            JSONObject verified = error.isEmpty() ? result : null;
            String message = error;
            host.runOnUiThread(() -> {
                try {
                    client.applyIfCurrent(() -> {
                        if (host.isFinishing() || host.isDestroyed()) return;
                        busy = false;
                        bind(verified == null ? state : verified);
                        if (verified != null) confirmed.apply(id, verified);
                        else Toast.makeText(host, message, Toast.LENGTH_LONG).show();
                    });
                } finally {
                    client.shutdown();
                }
            });
        })) {
            client.shutdown();
            busy = false;
            bind(state);
            Toast.makeText(host, "Coba aktifkan lagi sebentar.", Toast.LENGTH_SHORT).show();
        }
    }
}
