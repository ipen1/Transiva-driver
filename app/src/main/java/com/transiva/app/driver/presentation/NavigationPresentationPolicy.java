package com.transiva.app.driver.presentation;

public final class NavigationPresentationPolicy {
    private NavigationPresentationPolicy() {}
    public static boolean shouldFollowCamera(boolean userGesture, boolean routeActive){ return routeActive && !userGesture; }
    public static boolean canEnterPip(boolean routeActive, boolean finishing){ return routeActive && !finishing; }
}
