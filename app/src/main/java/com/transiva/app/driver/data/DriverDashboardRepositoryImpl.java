package com.transiva.app.driver.data;

import com.transiva.app.SessionManager;
import com.transiva.app.DriverNetworkExecutor;
import com.transiva.app.DriverOrderActions;
import com.transiva.app.driver.domain.DriverDashboardRepository;
import com.transiva.app.driver.domain.DriverDashboardState;
import com.transiva.app.driver.domain.DriverOrder;

import org.json.JSONObject;

public final class DriverDashboardRepositoryImpl implements DriverDashboardRepository {

    private final DriverApiClient api;
    private final DriverApiClient orderApi;
    private final SessionManager session;

    public DriverDashboardRepositoryImpl(SessionManager session) {
        this.session = session;
        api = new DriverApiClient(session);
        orderApi = new DriverApiClient(session, true);
    }

    @Override
    public void loadDashboard(boolean freshOrders, DashboardCallback callback) {
        if (!DriverNetworkExecutor.execute(() -> {
            try {
                DriverApiClient.Result result = freshOrders
                        ? api.getOnce("driver_dashboard_native.php?fresh_orders=1")
                        : api.get("driver_dashboard_native.php");
                JSONObject driver = result.body.optJSONObject("driver");
                if (driver != null) api.applyIfCurrent(()->session.updateDriverRuntime(driver));
                DriverDashboardState state = DriverDashboardMapper.map(result.body);
                callback.onSuccess(state);
            } catch (DriverApiClient.ApiException error) {
                callback.onError(error.status, error.code, error.getMessage());
            } catch (RuntimeException error) {
                callback.onError(0, "PAYLOAD_ERROR", "Data Beranda belum dapat ditampilkan.");
            }
        })) callback.onError(0, "QUEUE_BUSY", "Antrean jaringan penuh. Coba lagi sebentar.");
    }

    @Override
    public void setOnline(boolean online, String driverType, ActionCallback callback) {
        if (!DriverNetworkExecutor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                // driver_type tidak dikirim lagi. Backend mengambil tipe driver
                // yang authoritative dari driver_profiles agar session lama tidak
                // dapat menimpa bike/car saat toggle online.
                body.put("is_online", online ? 1 : 0);

                DriverApiClient.Result result =
                        api.postIdempotent("driver_set_status_native.php", body);

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

                if(!api.applyIfCurrent(()->{
                    session.updateDriverRuntime(result.body);
                    session.put("driver_server_online",serverOnline?"1":"0");
                })) {callback.onError(0,"SESSION_CHANGED","Sesi berubah.");return;}

                callback.onSuccess(
                        result.body.optString("message",
                                online ? "Driver online" : "Driver offline"),
                        null
                );
            } catch (Exception error) {
                handle(error, callback, "Payload status tidak valid.");
            }
        })) callback.onError(0, "QUEUE_BUSY", "Antrean jaringan penuh. Coba lagi sebentar.");
    }

    @Override
    public void acceptOrder(String orderId, String source, String offerToken, ActionCallback callback) {
        if (!DriverNetworkExecutor.executeOrderAction(() -> {
            try {
                DriverOrder order = DriverOrderActions.accept(orderApi, orderId, source, offerToken);
                callback.onSuccess("Pesanan berhasil diterima.", order);
            } catch (Exception error) {
                handle(error, callback, "Status penerimaan belum terkonfirmasi. Periksa pesanan aktif.");
            }
        })) callback.onError(0, "QUEUE_BUSY", "Antrean aksi penuh. Coba lagi sebentar.");
    }

    @Override
    public void cancelOrder(String orderId, String source, String assignmentVersion,
                            String reason, ActionCallback callback) {
        if (!DriverNetworkExecutor.executeOrderAction(() -> {
            try {
                String message = DriverOrderActions.cancel(orderApi, session, orderId, source,
                        assignmentVersion, reason);
                callback.onSuccess(message, null);
            } catch (Exception error) {
                handle(error, callback, "Status pembatalan belum terkonfirmasi. Periksa pesanan aktif.");
            }
        })) callback.onError(0, "QUEUE_BUSY", "Antrean aksi penuh. Coba lagi sebentar.");
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
        orderApi.shutdown();
    }
}
