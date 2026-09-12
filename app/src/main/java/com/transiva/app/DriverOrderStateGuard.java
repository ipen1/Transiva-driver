package com.transiva.app;

/** Compatibility facade. The state machine is the single source of transition truth. */
public final class DriverOrderStateGuard {
    private DriverOrderStateGuard() {}
    public static boolean canTransition(String current, String next) {
        return DriverOrderStateMachine.canTransition(current, next);
    }
    public static String norm(String value) {
        return DriverOrderStateMachine.normalizeOperational(value);
    }
}
