package com.transiva.app.driver.domain;

import java.util.Collections;
import java.util.List;

public final class DriverDashboardState {
    public final String username;
    public final String displayName;
    public final String driverType;
    public final boolean online;
    public final boolean verified;
    public final long balance;
    public final long pendingDeposit;
    public final long pendingWithdraw;
    public final long todayEarning;
    public final int todayTrips;
    public final double rating;
    public final DriverOrder activeOrder;
    public final List<DriverOrder> offers;
    public final long serverTimeMillis;

    public DriverDashboardState(
            String username,
            String displayName,
            String driverType,
            boolean online,
            boolean verified,
            long balance,
            long pendingDeposit,
            long pendingWithdraw,
            long todayEarning,
            int todayTrips,
            double rating,
            DriverOrder activeOrder,
            List<DriverOrder> offers,
            long serverTimeMillis
    ) {
        this.username = username;
        this.displayName = displayName;
        this.driverType = driverType;
        this.online = online;
        this.verified = verified;
        this.balance = balance;
        this.pendingDeposit = pendingDeposit;
        this.pendingWithdraw = pendingWithdraw;
        this.todayEarning = todayEarning;
        this.todayTrips = todayTrips;
        this.rating = rating;
        this.activeOrder = activeOrder;
        this.offers = offers == null ? Collections.emptyList() : offers;
        this.serverTimeMillis = serverTimeMillis;
    }
}
