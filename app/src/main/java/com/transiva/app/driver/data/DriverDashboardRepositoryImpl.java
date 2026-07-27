package com.transiva.app.driver.data;

import com.transiva.app.SessionManager;
import com.transiva.app.driver.domain.DriverDashboardRepository;
import com.transiva.app.driver.domain.DriverDashboardState;
import com.transiva.app.driver.domain.DriverOrder;

import org.json.JSONObject;

public final class DriverDashboardRepositoryImpl implements DriverDashboardRepository {

    private final DriverApiClient api;
    private final SessionManager session;

    public DriverDashboardRepositoryImpl(SessionManager session) {
        this.session = session;
        api = new DriverApiClient(session);
    }

    @Override
    public void loadDashboard(DashboardCallback callback) {
        api.executor().execute(() -> {
            try {
                DriverApiClient.Result result = api.get(
                        "driver_dashboard_native.php?v=" + System.currentTimeMillis()
                );
                JSONObject driver = result.body.optJSONObject("driver");
                if (driver != null) session.updateDriverRuntime(driver);
                DriverDashboardState state = DriverDashboardMapper.map(result.body);
                callback.onSuccess(state);
            } catch (DriverApiClient.ApiException error) {
                callback.onError(error.status, error.code, error.getMessage());
            }
        });
    }

    @Override
    public void setOnline(boolean online, String driverType, ActionCallback callback) {
        api.executor().execute(() -> {
            try {
                JSONObject body = new JSONObject();
                // driver_type tidak dikirim lagi. Backend mengambil tipe driver
                // yang authoritative dari driver_profiles agar session lama tidak
                // dapat menimpa bike/car saat toggle online.
                body.put("is_online", online ? 1 : 0);

                DriverApiClient.Result result =
                        api.post("driver_set_status_native.php", body);

                // Jangan anggap sukses hanya karena HTTP 200. Status dan user_id
                // yang dikembalikan server wajib sesuai akun login saat ini.
                // Backend tidak boleh membuat / memilih driver_profile milik akun lain.
                boolean serverOnline = result.body.optInt("is_online", -1) == 1;
                int serverUserId = result.body.optInt("user_id", 0);
                int sessionUserId = 0;
                try {
                    String raw = session.getId();
                    if (raw == null || raw.trim().isEmpty()) raw = session.getUserId();
                    sessionUserId = Integer.parseInt(raw == null ? "0" : raw.trim());
                } catch (Exception ignored) { }

                if (sessionUserId > 0 && serverUserId > 0 && serverUserId != sessionUserId) {
                    callback.onError(
                            409,
                            "DRIVER_PROFILE_USER_MISMATCH",
                            "Profil driver server tidak sesuai dengan akun yang sedang login."
                    );
                    return;
                }

                if (!result.body.has("is_online") || serverOnline != online) {
                    callback.onError(
                            result.status,
                            "STATUS_NOT_PERSISTED",
                            "Status driver belum tersimpan di server."
                    );
                    return;
                }

                session.updateDriverRuntime(result.body);
                session.put("driver_server_online", serverOnline ? "1" : "0");

                callback.onSuccess(
                        result.body.optString("message",
                                online ? "Driver online" : "Driver offline"),
                        null
                );
            } catch (Exception error) {
                handle(error, callback, "Payload status tidak valid.");
            }
        });
    }

    @Override
    public void acceptOrder(
            String orderId,
            String idempotencyKey,
            ActionCallback callback
    ) {
        api.executor().execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("order_id", orderId);
                body.put("idempotency_key", idempotencyKey);

                DriverApiClient.Result result =
                        api.post("driver_accept_order_native.php", body);

                DriverOrder order = DriverDashboardMapper.mapOrder(
                        result.body.optJSONObject("order")
                );

                callback.onSuccess(
                        result.body.optString("message", "Order berhasil diambil."),
                        order
                );
            } catch (Exception error) {
                handle(error, callback, "Payload order tidak valid.");
            }
        });
    }

    @Override
    public void cancelOrder(
            String orderId,
            String source,
            String currentStatus,
            String reason,
            ActionCallback callback
    ) {
        api.executor().execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("order_id", orderId);
                body.put("source", source);
                body.put("current_status", currentStatus);
                body.put("reason", reason);

                DriverApiClient.Result result =
                        api.post("driver_cancel_order_native.php", body);

                callback.onSuccess(
                        result.body.optString(
                                "message",
                                "Order berhasil dibatalkan."
                        ),
                        null
                );
            } catch (Exception error) {
                handle(error, callback, "Data pembatalan order tidak valid.");
            }
        });
    }

    private void handle(
            Exception error,
            ActionCallback callback,
            String fallback
    ) {
        if (error instanceof DriverApiClient.ApiException) {
            DriverApiClient.ApiException apiError =
                    (DriverApiClient.ApiException) error;
            callback.onError(
                    apiError.status,
                    apiError.code,
                    apiError.getMessage()
            );
        } else {
            callback.onError(0, "PAYLOAD_ERROR", fallback);
        }
    }

    @Override
    public void destroy() {
        api.shutdown();
    }
}
