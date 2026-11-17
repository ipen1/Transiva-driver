package com.transiva.app;

import android.graphics.Color;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

final class PremiumRideTripLayout {
    private final DriverTripActivity host;
    final FrameLayout premiumMap;
    final LinearLayout itinerary, actionDock;
    final TextView pickupLine, deliveryLine;

    PremiumRideTripLayout(DriverTripActivity host) {
        this.host = host;
        premiumMap = new FrameLayout(host);
        premiumMap.setBackgroundColor(DriverThemeTokens.background(host));

        LinearLayout overlay = new LinearLayout(host);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setElevation(dp(7));
        LinearLayout toolbar = new LinearLayout(host);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setBackgroundColor(DriverThemeTokens.surface(host));
        TextView back = text("‹", 31, "#0B3A78", true);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("Kembali");
        back.setOnClickListener(v -> host.finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        toolbar.addView(text("Driver Trip", 20, "#0B3A78", true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView online = text("• Online", 12, "#059669", true);
        online.setGravity(Gravity.CENTER);
        online.setPadding(dp(9), dp(5), dp(9), dp(5));
        online.setBackground(round(DriverThemeTokens.isDark(host) ? "#173C35" : "#DCFCE7", dp(18)));
        if (DriverThemeTokens.isDark(host)) online.setTextColor(Color.parseColor("#86EFAC"));
        toolbar.addView(online);
        TextView more = text("⋮", 24, "#0B3A78", true);
        more.setGravity(Gravity.CENTER);
        more.setContentDescription("Detail dan opsi perjalanan");
        more.setOnClickListener(v -> host.showPremiumDetails());
        toolbar.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
        overlay.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));

        itinerary = host.card();
        itinerary.setPadding(dp(10), dp(6), dp(10), dp(6));
        itinerary.setBackground(round("#FFFFFF", dp(20)));
        pickupLine = itineraryLine("Jemput", "#C66200", host::pickupAddress);
        deliveryLine = itineraryLine("Pengantaran", "#059669", host::deliveryAddress);
        LinearLayout.LayoutParams itineraryParams = new LinearLayout.LayoutParams(-1, -2);
        itineraryParams.setMargins(dp(10), dp(7), dp(10), 0);
        overlay.addView(itinerary, itineraryParams);
        premiumMap.addView(overlay, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        actionDock = host.card();
        actionDock.setElevation(dp(7));
        actionDock.setClickable(true);
        actionDock.setBackground(round("#FFFFFF", dp(22)));
        actionDock.setPadding(dp(9), dp(8), dp(9), dp(6));
        FrameLayout.LayoutParams bottom = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        bottom.setMargins(dp(10), 0, dp(10), dp(8));
        premiumMap.addView(actionDock, bottom);

        overlay.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> host.updatePremiumMapInsets());
        actionDock.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> host.updatePremiumMapInsets());
    }

    private TextView itineraryLine(String label, String color, java.util.function.Supplier<String> address) {
        LinearLayout row = new LinearLayout(host);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView pin = text("●", 18, color, true);
        pin.setGravity(Gravity.CENTER);
        row.addView(pin, new LinearLayout.LayoutParams(dp(34), dp(36)));
        LinearLayout copy = new LinearLayout(host);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(0, dp(2), 0, dp(2));
        copy.addView(text(label, 11, "#64748B", false));
        TextView place = text("", 14, "#0B3A78", true);
        place.setMaxLines(2);
        place.setEllipsize(android.text.TextUtils.TruncateAt.END);
        copy.addView(place);
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        row.setOnClickListener(v -> host.info(label, address.get()));
        itinerary.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return place;
    }

    private TextView text(String value, int size, String color, boolean bold) {
        return host.text(value, size, color, bold);
    }

    private android.graphics.drawable.GradientDrawable round(String color, int radius) {
        return host.round(color, radius);
    }

    private int dp(int value) { return host.dp(value); }
}
