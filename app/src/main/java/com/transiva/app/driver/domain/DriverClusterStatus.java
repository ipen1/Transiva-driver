package com.transiva.app.driver.domain;

public final class DriverClusterStatus {
    public final int id;
    public final String name;
    public final int activeDrivers;

    public DriverClusterStatus(int id, String name, int activeDrivers) {
        this.id = id;
        this.name = name == null ? "" : name;
        this.activeDrivers = Math.max(0, activeDrivers);
    }
}
