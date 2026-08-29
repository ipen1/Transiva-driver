package com.transiva.app;

import java.util.Locale;

/** Single source of truth for driver-side cancellation eligibility/reasons. */
public final class DriverOrderCancellationPolicy {
    private DriverOrderCancellationPolicy() {}

    public static String normalize(String status) {
        if (status == null) return "";
        return status.trim().toLowerCase(Locale.US).replace('-', '_').replace(' ', '_');
    }

    public static boolean canCancel(String status) {
        String value = normalize(status);
        return value.equals("taken")
                || value.equals("driver_accepted")
                || value.equals("accepted")
                || value.equals("arrived_pickup");
    }

    public static String[] reasons() {
        return new String[]{
                "Kendaraan bermasalah",
                "Kondisi darurat",
                "Tidak dapat menemukan titik penjemputan",
                "Customer tidak dapat dihubungi",
                "Order tidak sesuai",
                "Alasan lainnya"
        };
    }
}
