package com.transiva.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import com.transiva.app.driver.domain.DriverOrder;

/** Owns dashboard cancellation dialogs and validation so the Activity only handles presentation flow. */
public final class DashboardCancellationController {
    public interface Listener {
        void onCancelConfirmed(DriverOrder order, String reason);
        void onMessage(String message);
    }

    private final Activity activity;
    private final Listener listener;

    public DashboardCancellationController(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    public void show(DriverOrder order) {
        if (order == null || !DriverOrderCancellationPolicy.canCancel(order.status)) {
            if (listener != null) listener.onMessage("Order tidak dapat dibatalkan pada status " + normalize(order == null ? "" : order.status));
            return;
        }
        final String[] reasons = DriverOrderCancellationPolicy.reasons();
        AlertDialog dialog = PremiumDialogs.builder(activity)
                .setTitle("Batalkan order #" + order.id)
                .setSingleChoiceItems(reasons, -1, null)
                .setNegativeButton("Kembali", null)
                .setPositiveButton("Lanjutkan", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            Button next = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            next.setOnClickListener(v -> {
                int selected = dialog.getListView().getCheckedItemPosition();
                if (selected < 0) {
                    Toast.makeText(activity, "Silakan pilih alasan pembatalan.", Toast.LENGTH_SHORT).show();
                    return;
                }
                dialog.dismiss();
                if (selected == reasons.length - 1) showCustom(order);
                else confirm(order, reasons[selected]);
            });
        });
        dialog.show();
    }

    private void showCustom(DriverOrder order) {
        final EditText input = new EditText(activity);
        input.setHint("Tuliskan alasan pembatalan");
        input.setSingleLine(false); input.setMinLines(3); input.setMaxLines(5);
        int p = dp(16); input.setPadding(p, dp(12), p, dp(12));
        AlertDialog dialog = PremiumDialogs.builder(activity)
                .setTitle("Alasan lainnya")
                .setMessage("Jelaskan alasan pembatalan order.")
                .setView(input)
                .setNegativeButton("Kembali", null)
                .setPositiveButton("Lanjutkan", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String reason = input.getText().toString().trim();
            if (reason.length() < 5) { input.setError("Alasan minimal 5 karakter"); input.requestFocus(); return; }
            dialog.dismiss(); confirm(order, reason);
        }));
        dialog.show();
    }

    private void confirm(DriverOrder order, String reason) {
        PremiumDialogs.builder(activity)
                .setTitle("Konfirmasi pembatalan")
                .setMessage("Order akan dilepas dan ditawarkan kepada driver lain.\n\nAlasan: " + reason)
                .setNegativeButton("Tidak", null)
                .setPositiveButton("Ya, Batalkan", (d, w) -> { if (listener != null) listener.onCancelConfirmed(order, reason); })
                .show();
    }

    private int dp(int v) { return (int)(v * activity.getResources().getDisplayMetrics().density + .5f); }
    private static String normalize(String s) { return s == null ? "" : s.trim().toLowerCase(java.util.Locale.US).replace('-', '_').replace(' ', '_'); }
}
