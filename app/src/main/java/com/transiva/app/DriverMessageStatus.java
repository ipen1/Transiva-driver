package com.transiva.app;

import java.util.Locale;

public final class DriverMessageStatus {

    private DriverMessageStatus() {
    }

    public static String normalize(String value) {
        return value == null
                ? ""
                : value.trim()
                .toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
    }

    public static boolean isEnded(String rawStatus) {
        String status = normalize(rawStatus);

        return status.equals("finished")
                || status.equals("finish")
                || status.equals("completed")
                || status.equals("canceled")
                || status.equals("cancelled")
                || status.equals("merchant_rejected");
    }

    public static boolean canSend(String rawStatus) {
        String status = normalize(rawStatus);

        return status.equals("merchant_accepted")
                || status.equals("driver_accepted")
                || status.equals("accepted")
                || status.equals("assigned")
                || status.equals("driver_assigned")
                || status.equals("taken")
                || status.equals("arrived_pickup")
                || status.equals("picked_up")
                || status.equals("on_trip")
                || status.equals("on_delivery")
                || status.equals("arrived_delivery");
    }

    public static String availabilityLabel(
            String rawStatus,
            boolean history
    ) {
        if (history || isEnded(rawStatus)) {
            return "Riwayat • hanya dapat dibaca";
        }

        if (canSend(rawStatus)) {
            return "Chat aktif";
        }

        return "Chat tersedia setelah order diterima";
    }

    public static String orderLabel(
            String rawStatus,
            String serviceType
    ) {
        String status = normalize(rawStatus);
        String type = normalize(serviceType);

        if (status.equals("pending")) {
            return type.contains("food")
                    ? "Menunggu Merchant"
                    : "Menunggu Driver";
        }

        if (status.equals("merchant_accepted")) {
            return "Merchant Menerima";
        }

        if (status.equals("driver_accepted")
                || status.equals("accepted")
                || status.equals("assigned")
                || status.equals("driver_assigned")) {
            return "Driver Menerima";
        }

        if (status.equals("taken")) {
            return type.contains("food")
                    ? "Driver Menuju Restoran"
                    : "Driver Menuju Pickup";
        }

        if (status.equals("arrived_pickup")) {
            return type.contains("food")
                    ? "Driver Tiba di Restoran"
                    : "Driver Tiba di Pickup";
        }

        if (
                status.equals("on_delivery")
                        || status.equals("on_trip")
        ) {
            return type.contains("food")
                    ? "Pesanan Sedang Diantar"
                    : "Dalam Perjalanan";
        }

        if (status.equals("arrived_delivery")) {
            return "Tiba di Tujuan";
        }

        if (
                status.equals("finished")
                        || status.equals("finish")
                        || status.equals("completed")
        ) {
            return "Selesai";
        }

        if (
                status.equals("canceled")
                        || status.equals("cancelled")
        ) {
            return "Dibatalkan";
        }

        if (status.equals("merchant_rejected")) {
            return "Ditolak Merchant";
        }

        return status.isEmpty()
                ? "Status tidak tersedia"
                : status.replace('_', ' ');
    }
}
