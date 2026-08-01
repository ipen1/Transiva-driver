package com.transiva.app.driver.presentation;

import android.os.Handler;
import android.os.Looper;

import com.transiva.app.DriverRequestGate;
import com.transiva.app.driver.domain.DriverDashboardRepository;
import com.transiva.app.driver.domain.DriverDashboardState;
import com.transiva.app.driver.domain.DriverOrder;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DriverDashboardPresenter {

    private final DriverDashboardRepository repository;
    private DriverDashboardContract.View view;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean loading = new AtomicBoolean(false);
    private final AtomicBoolean actionRunning = new AtomicBoolean(false);

    public DriverDashboardPresenter(
            DriverDashboardRepository repository,
            DriverDashboardContract.View view
    ) {
        this.repository = repository;
        this.view = view;
    }

    public void load(boolean showLoading) {
        if (!loading.compareAndSet(false, true)) return;
        if (showLoading && view != null) view.showLoading(true);

        repository.loadDashboard(new DriverDashboardRepository.DashboardCallback() {
            @Override public void onSuccess(DriverDashboardState state) {
                main.post(() -> {
                    loading.set(false);
                    if (view == null) return;
                    view.showLoading(false);
                    view.showDashboard(state);
                });
            }

            @Override public void onError(int httpCode, String code, String message) {
                main.post(() -> {
                    loading.set(false);
                    if (view == null) return;
                    view.showLoading(false);
                    if (isSessionError(httpCode, code)) {
                        view.showSessionExpired();
                    } else {
                        view.showMessage(message);
                    }
                });
            }
        });
    }

    public void setOnline(boolean online, String driverType) {
        if (!actionRunning.compareAndSet(false, true)) return;
        if (view != null) view.showActionLoading("status", true);

        repository.setOnline(online, driverType,
                new DriverDashboardRepository.ActionCallback() {
                    @Override public void onSuccess(String message, DriverOrder order) {
                        finishAction("status", message, false, order);
                    }

                    @Override public void onError(int httpCode, String code, String message) {
                        failAction("status", httpCode, code, message);
                    }
                });
    }

    public void acceptOrder(String orderId, String source) {
        String gateKey = "accept:" + orderId;
        if (!DriverRequestGate.enter(gateKey)) return;
        if (!actionRunning.compareAndSet(false, true)) { DriverRequestGate.leave(gateKey); return; }
        String action = gateKey;
        if (view != null) view.showActionLoading(action, true);

        repository.acceptOrder(
                orderId,
                source,
                UUID.randomUUID().toString(),
                new DriverDashboardRepository.ActionCallback() {
                    @Override public void onSuccess(String message, DriverOrder order) {
                        DriverRequestGate.leave(gateKey);
                        finishAction(action, message, true, order);
                    }

                    @Override public void onError(int httpCode, String code, String message) {
                        DriverRequestGate.leave(gateKey);
                        failAction(action, httpCode, code, message);
                    }
                }
        );
    }

    public void cancelOrder(
            String orderId,
            String source,
            String currentStatus,
            String reason
    ) {
        String gateKey = "cancel:" + orderId;
        if (!DriverRequestGate.enter(gateKey)) return;
        if (!actionRunning.compareAndSet(false, true)) { DriverRequestGate.leave(gateKey); return; }
        String action = gateKey;
        if (view != null) view.showActionLoading(action, true);

        repository.cancelOrder(
                orderId,
                source,
                currentStatus,
                reason,
                new DriverDashboardRepository.ActionCallback() {
                    @Override public void onSuccess(String message, DriverOrder order) {
                        DriverRequestGate.leave(gateKey);
                        finishAction(action, message, false, null);
                    }

                    @Override public void onError(int httpCode, String code, String message) {
                        DriverRequestGate.leave(gateKey);
                        failAction(action, httpCode, code, message);
                    }
                }
        );
    }

    private void finishAction(
            String action,
            String message,
            boolean openTrip,
            DriverOrder order
    ) {
        main.post(() -> {
            actionRunning.set(false);
            if (view == null) return;
            view.showActionLoading(action, false);
            view.showMessage(message);
            if (openTrip && order != null) view.openTrip(order);
            else load(false);
        });
    }

    private void failAction(
            String action,
            int httpCode,
            String code,
            String message
    ) {
        main.post(() -> {
            actionRunning.set(false);
            if (view == null) return;
            view.showActionLoading(action, false);
            if (isSessionError(httpCode, code)) {
                view.showSessionExpired();
            } else {
                view.showMessage(message);
                load(false);
            }
        });
    }

    private boolean isSessionError(int httpCode, String code) {
        return httpCode == 401
                || httpCode == 403
                || "UNAUTHORIZED".equalsIgnoreCase(code)
                || "SESSION_EXPIRED".equalsIgnoreCase(code);
    }

    public void destroy() {
        view = null;
        main.removeCallbacksAndMessages(null);
        repository.destroy();
    }
}
