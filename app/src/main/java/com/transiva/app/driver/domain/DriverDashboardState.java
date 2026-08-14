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
    public final int onlineMinutes;
    public final double todayDistanceKm;
    public final int queueRank;
    public final int queueTotal;
    public final String queueLabel;
    public final String assistantTitle;
    public final String assistantMessage;
    public final String hotspotName;
    public final String hotspotLevel;
    public final int hotspotScore;
    public final int currentClusterId;
    public final String currentClusterName;
    public final List<DriverClusterStatus> clusters;
    public final DriverOrder activeOrder;
    public final List<DriverOrder> activeOrders;
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
            int onlineMinutes,
            double todayDistanceKm,
            int queueRank,
            int queueTotal,
            String queueLabel,
            String assistantTitle,
            String assistantMessage,
            String hotspotName,
            String hotspotLevel,
            int hotspotScore,
            int currentClusterId,
            String currentClusterName,
            List<DriverClusterStatus> clusters,
            DriverOrder activeOrder,
            List<DriverOrder> activeOrders,
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
        this.onlineMinutes = onlineMinutes;
        this.todayDistanceKm = todayDistanceKm;
        this.queueRank = queueRank;
        this.queueTotal = queueTotal;
        this.queueLabel = queueLabel == null ? "" : queueLabel;
        this.assistantTitle = assistantTitle == null ? "" : assistantTitle;
        this.assistantMessage = assistantMessage == null ? "" : assistantMessage;
        this.hotspotName = hotspotName == null ? "" : hotspotName;
        this.hotspotLevel = hotspotLevel == null ? "" : hotspotLevel;
        this.hotspotScore = hotspotScore;
        this.currentClusterId = currentClusterId;
        this.currentClusterName = currentClusterName == null ? "" : currentClusterName;
        this.clusters = clusters == null ? Collections.emptyList() : clusters;
        this.activeOrder = activeOrder;
        this.activeOrders = activeOrders == null ? Collections.emptyList() : activeOrders;
        this.offers = offers == null ? Collections.emptyList() : offers;
        this.serverTimeMillis = serverTimeMillis;
    }
}
