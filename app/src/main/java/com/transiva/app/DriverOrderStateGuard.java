package com.transiva.app;

import java.util.Locale;

/** P0: guard defensif. Server tetap source of truth. */
public final class DriverOrderStateGuard {
    private DriverOrderStateGuard() {}
    public static boolean canTransition(String current, String next) {
        String c = norm(current), n = norm(next);
        if (c.equals(n)) return true;
        if (c.isEmpty()) return n.equals("driver_accepted");
        switch (c) {
            case "accepted": case "taken": case "driver_accepted": return n.equals("arrived_pickup");
            case "arrived_pickup": return n.equals("on_delivery");
            case "on_delivery": return n.equals("arrived_delivery");
            case "arrived_delivery": return n.equals("finished") || n.equals("completed");
            default: return false;
        }
    }
    public static String norm(String s) {
        String v=s==null?"":s.trim().toLowerCase(Locale.US);
        if(v.equals("accepted")||v.equals("taken")) return "driver_accepted";
        if(v.equals("finish")||v.equals("done")||v.equals("completed")) return "finished";
        return v;
    }
}
