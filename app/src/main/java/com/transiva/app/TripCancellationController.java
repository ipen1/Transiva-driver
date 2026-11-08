package com.transiva.app;

import android.app.AlertDialog;
import android.widget.Button;
import android.widget.EditText;

import com.transiva.app.driver.data.DriverApiClient;

import org.json.JSONObject;

/** Owns the complete Trip cancellation UX/network flow. */
public final class TripCancellationController {
    private final DriverTripActivityLayer1 host;
    private final JSONObject order;
    private final SessionManager session;
    private final Button cancelButton;

    public TripCancellationController(DriverTripActivityLayer1 host, JSONObject order,
                                      SessionManager session, Button cancelButton) {
        this.host = host;
        this.order = order;
        this.session = session;
        this.cancelButton = cancelButton;
    }

    public void show() {
        if (order == null || !DriverOrderCancellationPolicy.canCancel(host.tripStatus())) {
            host.tripInfo("Pembatalan", "Order tidak dapat dibatalkan pada status " + host.tripStatusLabel(host.tripStatus()) + ".");
            return;
        }
        final String[] reasons = DriverOrderCancellationPolicy.reasons();
        AlertDialog dialog = PremiumDialogs.builder(host)
                .setTitle("Batalkan order #" + host.tripOrderId())
                .setSingleChoiceItems(reasons, -1, null)
                .setNegativeButton("Kembali", null)
                .setPositiveButton("Lanjutkan", null)
                .create();
        dialog.setOnShowListener(ignored -> { PremiumDialogs.applyPremiumStyle(dialog); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int selected = dialog.getListView().getCheckedItemPosition();
            if (selected < 0) { host.tripInfo("Pembatalan", "Silakan pilih alasan pembatalan."); return; }
            dialog.dismiss();
            if (selected == reasons.length - 1) showCustomReason();
            else confirm(reasons[selected]);
        }); });
        dialog.show();
    }

    private void showCustomReason() {
        EditText input = new EditText(host);
        input.setHint("Tuliskan alasan pembatalan");
        input.setSingleLine(false);
        input.setMinLines(3);
        input.setMaxLines(5);
        input.setPadding(host.tripDp(16), host.tripDp(12), host.tripDp(16), host.tripDp(12));
        AlertDialog dialog = PremiumDialogs.builder(host)
                .setTitle("Alasan lainnya")
                .setMessage("Jelaskan alasan pembatalan order.")
                .setView(input)
                .setNegativeButton("Kembali", null)
                .setPositiveButton("Lanjutkan", null)
                .create();
        dialog.setOnShowListener(ignored -> { PremiumDialogs.applyPremiumStyle(dialog); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String reason = input.getText().toString().trim();
            if (reason.length() < 5) { input.setError("Alasan minimal 5 karakter"); input.requestFocus(); return; }
            dialog.dismiss();
            confirm(reason);
        }); });
        dialog.show();
    }

    private void confirm(String reason) {
        PremiumDialogs.builder(host)
                .setTitle("Konfirmasi pembatalan")
                .setMessage("Order akan dilepas dan ditawarkan kepada driver lain.\n\nAlasan: " + reason)
                .setNegativeButton("Tidak", null)
                .setPositiveButton("Ya, Batalkan", (d, w) -> perform(reason))
                .show();
    }

    private void perform(String reason) {
        final JSONObject order = host.order;
        if (order == null || !host.tripStateReady() || !DriverOrderCancellationPolicy.canCancel(host.tripStatus())) {
            host.tripInfo("Sinkronisasi perjalanan", "Tunggu status terbaru sebelum membatalkan pesanan.");
            return;
        }
        final String id = host.tripInternalId();
        final String source = host.tripIsPickupOrder() ? "pickup_orders" : "orders";
        final String version = order.optString("assignment_version");
        final String gate = "cancel:" + source + ":" + id;
        if (!DriverRequestGate.enter(gate)) return;
        DriverApiClient client = new DriverApiClient(session, true);
        host.tripMutationStarted();
        host.tripCancelRunning = true;
        host.tripSetLoading(true);
        if (cancelButton != null) cancelButton.setEnabled(false);
        if (!DriverNetworkExecutor.executeOrderAction(() -> {
            String message = null;
            String error = null;
            try {
                message = DriverOrderActions.cancel(client, session, id, source, version, reason);
            } catch (Exception failed) {
                error = failed.getMessage();
            }
            final String receiptMessage = message;
            final String failureMessage = error;
            host.runOnUiThread(() -> {
                DriverRequestGate.leave(gate);
                try {
                    client.applyIfCurrent(() -> {
                        if (host.isFinishing() || host.isDestroyed()) return;
                        host.tripCancelRunning = false;
                        host.tripSetLoading(false);
                        if (host.order != order) return;
                        host.tripNeedsSync();
                        if (receiptMessage == null) {
                            host.tripInfo("Pembatalan Pesanan", failureMessage == null
                                    ? "Status pembatalan belum terkonfirmasi." : failureMessage);
                            return;
                        }
                        DriverMessageUnreadRepository.clearOrder(host, host.tripOrderId());
                        try { order.put("status", "cancelled"); }
                        catch (org.json.JSONException ignored) {}
                        NavigationGuidanceService.stopFor(host, order);
                        host.saveActiveOrder();
                        host.tripNeedsSync();
                        PremiumDialogs.builder(host)
                                .setTitle("Order Dibatalkan")
                                .setMessage(receiptMessage)
                                .setCancelable(false)
                                .setPositiveButton("Kembali", (dialog, button) -> host.finish())
                                .show();
                    });
                } finally {
                    client.shutdown();
                }
            });
        })) {
            DriverRequestGate.leave(gate);
            client.shutdown();
            host.tripCancelRunning = false;
            host.tripSetLoading(false);
            host.tripNeedsSync();
            host.tripInfo("Pembatalan Pesanan", "Antrean aksi penuh. Coba lagi sebentar.");
        }
    }
}
