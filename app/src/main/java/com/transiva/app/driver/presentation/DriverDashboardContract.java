package com.transiva.app.driver.presentation;

import com.transiva.app.driver.domain.DriverDashboardState;
import com.transiva.app.driver.domain.DriverOrder;

public interface DriverDashboardContract {

    interface View {
        void showLoading(boolean visible);
        void showDashboard(DriverDashboardState state);
        void showActionLoading(String action, boolean visible);
        void showMessage(String message);
        void showSessionExpired();
        void openTrip(DriverOrder order);
    }
}
