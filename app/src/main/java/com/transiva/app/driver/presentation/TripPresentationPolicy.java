package com.transiva.app.driver.presentation;

public final class TripPresentationPolicy {
    private TripPresentationPolicy() {}
    public static boolean canContactCustomer(String status){ return status!=null && !status.isEmpty() && !"finished".equals(status) && !"cancelled".equals(status); }
    public static boolean terminal(String status){ return "finished".equals(status)||"cancelled".equals(status)||"rejected".equals(status); }
}
