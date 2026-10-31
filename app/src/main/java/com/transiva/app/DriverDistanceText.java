package com.transiva.app;
import java.util.Locale;
/** Numeric server values are kilometers; never render bare decimals or revenue per km. */
public final class DriverDistanceText {
    private DriverDistanceText() {}
    public static String kilometers(double km) {
        if(Double.isNaN(km)||Double.isInfinite(km)||km<0)return "Belum tersedia";
        if(km==0)return "0 m";
        if(km<1)return Math.max(1,Math.round(km*1000))+" m";
        return String.format(new Locale("id","ID"),"%.1f km",km);
    }
}
