package com.transiva.app;

import android.text.TextUtils;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;

final class DriverOfferCardUi {
    private final DriverDashboardActivityLayer2 host;

    DriverOfferCardUi(DriverDashboardActivityLayer2 host) { this.host = host; }

    LinearLayout card() {
        LinearLayout card = column();
        card.setPadding(dp(7), dp(4), dp(7), dp(7));
        return card;
    }

    TextView header(LinearLayout card, String service, String id, boolean hasDeadline) {
        LinearLayout row = new LinearLayout(host);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout title = column();
        TextView type = host.text(service, 19, "#0B3A78", true);
        type.setMaxLines(2);
        title.addView(type);
        title.addView(host.text("#" + id, 11, "#64748B", false));
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView timer = host.text("Menghitung…", 12, "#B45309", true);
        timer.setGravity(Gravity.CENTER);
        timer.setPadding(dp(9), dp(4), dp(9), dp(4));
        if (hasDeadline) row.addView(timer);
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
        separator(card);
        return timer;
    }

    void rider(LinearLayout card, String identity, String reputation) {
        TextView rider = host.text(identity + (reputation.isEmpty() ? "" : "  •  " + reputation), 12, "#0B3A78", true);
        rider.setMaxLines(2);
        rider.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(rider);
        separator(card);
    }

    void point(LinearLayout card, String label, String address, String pinColor) {
        LinearLayout row = new LinearLayout(host);
        row.setGravity(Gravity.TOP);
        TextView pin = host.text("●", 16, pinColor, true);
        pin.setGravity(Gravity.CENTER);
        row.addView(pin, new LinearLayout.LayoutParams(dp(26), dp(27)));
        LinearLayout content = column();
        content.addView(host.text(label, 11, pinColor, true));
        TextView place = host.text(address == null || address.trim().isEmpty() ? "—" : address, 13, "#0F172A", false);
        content.addView(place);
        row.addView(content, new LinearLayout.LayoutParams(0, -2, 1));
        host.add(card, row, 0, dp(2), 0, dp(3));
    }

    void fare(LinearLayout card, long amount, String payment, JSONObject commission) {
        LinearLayout row = new LinearLayout(host);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout left = column();
        left.addView(host.text(host.rupiah(amount), 21, "#0B3A78", true));
        left.addView(host.text(payment, 12, "#64748B", true));
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        if (commission != null) {
            LinearLayout net = column();
            net.addView(host.text("Bersih", 11, "#64748B", false));
            net.addView(host.text(host.rupiah(commission.optLong("driver_net")), 17, "#0B3A78", true));
            row.addView(net);
        }
        host.add(card, row, 0, dp(5), 0, dp(1));
        String fee = commission == null ? "" : "Potongan " + host.rupiah(commission.optLong("app_fee")) + " · ";
        card.addView(host.text(fee + "100% biaya tunggu untuk Anda", 11, "#64748B", false));
    }

    private void separator(LinearLayout card) {
        android.view.View divider = new android.view.View(host);
        divider.setBackgroundColor(DriverThemeTokens.divider(host));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1));
        params.topMargin = dp(4);
        params.bottomMargin = dp(4);
        card.addView(divider, params);
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(host);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private int dp(int value) { return host.dp(value); }
}
