package com.transiva.app;

import android.app.AlertDialog;
import android.widget.Button;
import android.widget.EditText;

import com.transiva.app.driver.data.DriverApiClient;

import org.json.JSONObject;

/** Owns the complete Trip cancellation UX/network flow. */
public final class TripCancellationController {
    private final DriverTripActivity host;
    private final JSONObject order;
    private final SessionManager session;
    private final Button cancelButton;

    public TripCancellationController(DriverTripActivity host, JSONObject order,
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
        final String gate = "cancel:" + host.tripOrderId();
        if (!DriverRequestGate.enter(gate)) return;
        host.tripSetLoading(true);
        if (cancelButton != null) cancelButton.setEnabled(false);
        DriverNetworkExecutor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("order_id", host.tripOrderId());
                body.put("source", host.tripSource());
                body.put("current_status", host.tripStatus());
                body.put("reason", reason);
                DriverApiClient.Result result = new DriverApiClient(session).post("driver_cancel_order_native.php", body);
                String message = result.body.optString("message", "Order berhasil dibatalkan.").trim();
                if (message.isEmpty()) message = "Order berhasil dibatalkan.";
                final String finalMessage = message;
                host.runOnUiThread(() -> {
                    DriverRequestGate.leave(gate);
                    host.tripSetLoading(false);
                    DriverMessageUnreadRepository.clearOrder(host, host.tripOrderId());
                    host.tripClearActiveOrder();
                    PremiumDialogs.builder(host)
                            .setTitle("Order Dibatalkan")
                            .setMessage(finalMessage)
                            .setCancelable(false)
                            .setPositiveButton("Kembali ke Beranda", (x, y) -> host.finish())
                            .show();
                });
            } catch (Exception e) {
                TransivaDiagnostics.error(host, "order", "TRIP_CANCEL_ORDER_FAILED", e);
                host.runOnUiThread(() -> {
                    DriverRequestGate.leave(gate);
                    host.tripSetLoading(false);
                    if (cancelButton != null) cancelButton.setEnabled(true);
                    host.tripInfo("Gagal Membatalkan", "Koneksi/server bermasalah. Order tidak diubah.");
                });
            }
        });
    }
}
