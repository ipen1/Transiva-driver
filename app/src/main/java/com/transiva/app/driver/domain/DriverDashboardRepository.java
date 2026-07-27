package com.transiva.app.driver.domain;

public interface DriverDashboardRepository {

    interface DashboardCallback {
        void onSuccess(DriverDashboardState state);
        void onError(int httpCode, String code, String message);
    }

    interface ActionCallback {
        void onSuccess(String message, DriverOrder order);
        void onError(int httpCode, String code, String message);
    }

    void loadDashboard(DashboardCallback callback);
    void setOnline(boolean online, String driverType, ActionCallback callback);
    void acceptOrder(String orderId, String idempotencyKey, ActionCallback callback);
    void cancelOrder(
            String orderId,
            String source,
            String currentStatus,
            String reason,
            ActionCallback callback
    );
    void destroy();
}
