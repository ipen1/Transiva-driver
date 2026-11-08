package com.transiva.app;

import android.content.SharedPreferences;
import com.transiva.app.driver.data.DriverApiClient;
import com.transiva.app.driver.data.DriverDashboardMapper;
import com.transiva.app.driver.domain.DriverOrder;
import org.json.JSONObject;
import java.io.IOException;
import java.util.UUID;

public final class DriverOrderActions {
    private DriverOrderActions() {}

    public static DriverOrder accept(DriverApiClient api, String id, String source, String token)
            throws Exception {
        JSONObject identity = new JSONObject().put("order_id", id).put("source", source);
        JSONObject request = new JSONObject(identity.toString()).put("action_token", token);
        DriverApiClient.ApiException conflict = null;
        try {
            JSONObject response = api.post("driver_accept_order_native.php", request).body;
            DriverOrder order = DriverDashboardMapper.mapOrder(response.optJSONObject("order"));
            if (order != null && !response.optBoolean("refresh_required")) return order;
        } catch (DriverApiClient.ApiException error) {
            if (!uncertain(error) && error.status != 409) throw error;
            if (error.status == 409) conflict = error;
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            if (attempt > 0) Thread.sleep(350);
            try {
                JSONObject state = api.post("driver_order_signal.php", identity).body;
                DriverOrder order = DriverDashboardMapper.mapOrder(state.optJSONObject("order"));
                if (order != null) return order;
            } catch (DriverApiClient.ApiException error) {
                if (!uncertain(error)) throw error;
            }
        }
        if (conflict != null) throw conflict;
        throw new IOException("Status penerimaan belum terkonfirmasi. Periksa pesanan aktif.");
    }

    public static String cancel(DriverApiClient api, SessionManager session, String id,
                                String source, String assignmentVersion, String reason) throws Exception {
        SharedPreferences pending = session.getContext().getSharedPreferences("driver_order_operations", 0);
        String key = session.getId() + "|" + session.getUserId() + "|" + source + "|" + id + "|" + assignmentVersion;
        String previous = pending.getString(key, "");
        int split = previous.indexOf('|');
        String operation = split < 0 ? UUID.randomUUID().toString() : previous.substring(0, split);
        String originalReason = split < 0 ? reason : previous.substring(split + 1);
        pending.edit().putString(key, operation + "|" + originalReason).apply();
        JSONObject request = new JSONObject().put("order_id", id).put("source", source)
                .put("assignment_version", assignmentVersion)
                .put("reason", originalReason).put("operation_id", operation);
        try {
            JSONObject result = api.post("driver_cancel_order_native.php", request).body;
            requireCancellationReceipt(result);
            pending.edit().remove(key).apply();
            return result.optString("message");
        } catch (DriverApiClient.ApiException error) {
            if (!uncertain(error) && error.status != 409) {
                pending.edit().remove(key).apply();
                throw error;
            }
            if (error.status == 409 && !"CANCEL_RESULT_UNKNOWN".equals(error.code)
                    && !"CANCEL_BUSY".equals(error.code)) {
                pending.edit().remove(key).apply();
                throw error;
            }
        }
        request.put("check_only", true);
        for (int attempt = 0; attempt < 2; attempt++) {
            if (attempt > 0) Thread.sleep(350);
            try {
                JSONObject result = api.post("driver_cancel_order_native.php", request).body;
                requireCancellationReceipt(result);
                pending.edit().remove(key).apply();
                return result.optString("message");
            } catch (DriverApiClient.ApiException error) {
                if (!uncertain(error) && !"CANCEL_RESULT_UNKNOWN".equals(error.code)) throw error;
            }
        }
        throw new IOException("Status pembatalan belum terkonfirmasi. Periksa pesanan aktif.");
    }

    private static void requireCancellationReceipt(JSONObject result) throws IOException {
        if (!"ORDER_CANCELLED".equals(result.optString("code"))) {
            throw new IOException("Bukti pembatalan belum terkonfirmasi.");
        }
    }

    private static boolean uncertain(DriverApiClient.ApiException error) {
        return error.status == 0 || error.status >= 500 || "INVALID_JSON".equals(error.code);
    }
}
