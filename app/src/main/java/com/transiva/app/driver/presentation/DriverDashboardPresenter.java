package com.transiva.app.driver.presentation;

import android.os.Handler;
import android.os.Looper;

import com.transiva.app.DriverRequestGate;
import com.transiva.app.driver.domain.DriverDashboardRepository;
import com.transiva.app.driver.domain.DriverDashboardState;
import com.transiva.app.driver.domain.DriverOrder;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class DriverDashboardPresenter {

    private final DriverDashboardRepository repository;
    private final com.transiva.app.DriverSessionOwner owner;
    private DriverDashboardContract.View view;
    private boolean freshOrdersPending;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean loading = new AtomicBoolean(false);
    private final AtomicBoolean actionRunning = new AtomicBoolean(false);
    // Membatalkan hasil refresh dashboard lama yang selesai setelah aksi mutasi status dimulai.
    private final AtomicLong stateEpoch = new AtomicLong(0L);

    public DriverDashboardPresenter(
            DriverDashboardRepository repository,
            DriverDashboardContract.View view
    ) {
        this.repository = repository;
        owner=view instanceof android.content.Context?com.transiva.app.DriverSessionOwner.capture((android.content.Context)view):null;
        this.view = view;
    }

    public void refreshOrders() {
        freshOrdersPending = true;
        load(false);
    }

    public void load(boolean showLoading) {
        if(owner!=null&&!owner.current())return;
        if (actionRunning.get() || !loading.compareAndSet(false, true)) return;
        final boolean freshOrders = freshOrdersPending;
        freshOrdersPending = false;
        final long requestEpoch = stateEpoch.get();
        if (showLoading && view != null) view.showLoading(true);

        repository.loadDashboard(freshOrders, new DriverDashboardRepository.DashboardCallback() {
            @Override public void onSuccess(DriverDashboardState state) {
                postIfCurrent(() -> {
                    // Refresh yang dimulai sebelum set ONLINE/OFFLINE tidak boleh
                    // mengembalikan switch ke state lama ataupun mengubah loading request baru.
                    if (requestEpoch != stateEpoch.get()) return;
                    loading.set(false);
                    if (view == null) return;
                    view.showLoading(false);
                    view.showDashboard(state);
                    if (freshOrdersPending) load(false);
                });
            }

            @Override public void onError(int httpCode, String code, String message) {
                postIfCurrent(() -> {
                    if (requestEpoch != stateEpoch.get()) return;
                    loading.set(false);
                    if (view == null) return;
                    view.showLoading(false);
                    if (isSessionError(httpCode, code)) {
                        view.showSessionExpired();
                    } else {
                        if (showLoading) view.showMessage(message);
                        if (freshOrdersPending) load(false);
                    }
                });
            }
        });
    }

    public void setOnline(boolean online, String driverType) {
        if(owner!=null&&!owner.current())return;
        if (!actionRunning.compareAndSet(false, true)) return;
        // Invalidasi dashboard request yang mungkin sedang berjalan agar response lama
        // tidak menimpa hasil toggle yang baru. Reset gate load karena callback lama
        // akan diabaikan berdasarkan epoch dan tidak boleh menahan refresh terbaru.
        stateEpoch.incrementAndGet();
        loading.set(false);
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

    public boolean acceptOrder(String orderId, String source, String offerToken) {
        if (owner != null && !owner.current()) return false;
        String gateKey = "accept:" + source + ":" + orderId;
        if (!DriverRequestGate.enter(gateKey)) return false;
        if (!actionRunning.compareAndSet(false, true)) { DriverRequestGate.leave(gateKey); return false; }
        stateEpoch.incrementAndGet();
        loading.set(false);
        String action = gateKey;
        if (view != null) view.showActionLoading(action, true);

        repository.acceptOrder(
                orderId,
                source,
                offerToken,
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
        return true;
    }

    public void cancelOrder(
            String orderId,
            String source,
            String assignmentVersion,
            String reason
    ) {
        if (owner != null && !owner.current()) return;
        String gateKey = "cancel:" + source + ":" + orderId;
        if (!DriverRequestGate.enter(gateKey)) return;
        if (!actionRunning.compareAndSet(false, true)) { DriverRequestGate.leave(gateKey); return; }
        stateEpoch.incrementAndGet();
        loading.set(false);
        String action = gateKey;
        if (view != null) view.showActionLoading(action, true);

        repository.cancelOrder(
                orderId,
                source,
                assignmentVersion,
                reason,
                new DriverDashboardRepository.ActionCallback() {
                    @Override public void onSuccess(String message, DriverOrder order) {
                        DriverRequestGate.leave(gateKey);
                        postIfCurrent(()->{if(view!=null)view.onOrderCancelled(orderId, source);});
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
        postIfCurrent(() -> {
            actionRunning.set(false);
            if (view == null) return;
            view.showActionLoading(action, false);
            view.showMessage(message);
            if (openTrip && order != null) {
                freshOrdersPending = true;
                view.openTrip(order);
            }
            else refreshOrders();
        });
    }

    private void failAction(
            String action,
            int httpCode,
            String code,
            String message
    ) {
        postIfCurrent(() -> {
            actionRunning.set(false);
            if (view == null) return;
            view.showActionLoading(action, false);
            if (isSessionError(httpCode, code)) {
                view.showSessionExpired();
            } else {
                view.showMessage(message);
                refreshOrders();
            }
        });
    }

    private boolean isSessionError(int httpCode, String code) {
        String cleanCode = code == null ? "" : code.trim();
        // Jangan menghapus sesi hanya berdasarkan HTTP 401/403 generik.
        // Logout paksa hanya untuk kode final yang benar-benar menyatakan
        // token/sesi/perangkat sudah tidak berlaku.
        return "SESSION_REVOKED".equalsIgnoreCase(cleanCode)
                || "SESSION_EXPIRED".equalsIgnoreCase(cleanCode)
                || "TOKEN_REVOKED".equalsIgnoreCase(cleanCode)
                || "DEVICE_MISMATCH".equalsIgnoreCase(cleanCode)
                || "DEVICE_RESET".equalsIgnoreCase(cleanCode)
                || "DEVICE_BANNED".equalsIgnoreCase(cleanCode);
    }

    private void postIfCurrent(Runnable task){main.post(()->{if(owner==null)task.run();else owner.runIfCurrent(task);});}
    public void destroy() {
        if(owner!=null)owner.close();
        view = null;
        main.removeCallbacksAndMessages(null);
        repository.destroy();
    }
}
