package com.transiva.app.driver.presentation;

public final class DashboardPresentationPolicy {
    private DashboardPresentationPolicy() {}
    public static String densityBand(int drivers){ if(drivers<=2)return "low"; if(drivers<=6)return "normal"; if(drivers<=15)return "busy"; return "very_busy"; }
    public static boolean showOnlineControls(boolean verified, boolean suspended){ return verified && !suspended; }
}
