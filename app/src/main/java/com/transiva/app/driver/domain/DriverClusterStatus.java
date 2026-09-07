package com.transiva.app.driver.domain;

public final class DriverClusterStatus {
    public final int id;
    public final int regionId;
    public final String regionName;
    public final String name;
    public final int activeDrivers;

    public DriverClusterStatus(int id, int regionId, String regionName, String name, int activeDrivers) {
        this.id = id;
        this.regionId = regionId;
        this.regionName = regionName == null ? "" : regionName;
        this.name = name == null ? "" : name;
        this.activeDrivers = Math.max(0, activeDrivers);
    }
}
