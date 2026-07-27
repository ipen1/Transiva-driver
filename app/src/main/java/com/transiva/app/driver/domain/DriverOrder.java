package com.transiva.app.driver.domain;

import org.json.JSONObject;

public final class DriverOrder {
    public final String id;
    public final String source;
    public final String serviceName;
    public final String status;
    public final String pickupAddress;
    public final String destinationAddress;
    public final long driverEarning;
    public final String pickupDistanceText;
    public final int remainingSeconds;
    public final JSONObject raw;

    public DriverOrder(
            String id,
            String source,
            String serviceName,
            String status,
            String pickupAddress,
            String destinationAddress,
            long driverEarning,
            String pickupDistanceText,
            int remainingSeconds,
            JSONObject raw
    ) {
        this.id = id;
        this.source = source;
        this.serviceName = serviceName;
        this.status = status;
        this.pickupAddress = pickupAddress;
        this.destinationAddress = destinationAddress;
        this.driverEarning = driverEarning;
        this.pickupDistanceText = pickupDistanceText;
        this.remainingSeconds = remainingSeconds;
        this.raw = raw;
    }
}
