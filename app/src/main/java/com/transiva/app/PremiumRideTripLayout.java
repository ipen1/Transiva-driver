package com.transiva.app;

import android.graphics.Color;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class PremiumRideTripLayout {
    private final DriverTripActivity host;
    final FrameLayout premiumMap;
    final LinearLayout itinerary, actionDock;
    final ScrollView headerPanel, dockPanel;
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
        TextView heading = text(DeliveryTripPresentation.service(host.order).isEmpty() ? "Driver Trip" : "Driver Trip · "+DeliveryTripPresentation.service(host.order), DeliveryTripPresentation.service(host.order).isEmpty() ? 20 : 17, "#0B3A78", true);
        heading.setMaxLines(2);
        toolbar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
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
        pickupLine = itineraryLine(host.isFoodOrder() ? "Jemput • Resto" : DeliveryTripPresentation.shop(host.order)?"Jemput • Toko":"Jemput", "#C66200", host::pickupAddress,true);
        deliveryLine = itineraryLine(host.isFoodOrder() ? "Antar • Pelanggan" : "Pengantaran", "#059669", host::deliveryAddress,false);
        LinearLayout.LayoutParams itineraryParams = new LinearLayout.LayoutParams(-1, -2);
        itineraryParams.setMargins(dp(10), dp(7), dp(10), 0);
        overlay.addView(itinerary, itineraryParams);
        headerPanel = DriverResponsiveUi.mapPanelScroll(host, .42f);
        headerPanel.setElevation(dp(7));
        headerPanel.addView(overlay, new ScrollView.LayoutParams(-1, -2));
        premiumMap.addView(headerPanel, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        actionDock = host.card();
        actionDock.setElevation(dp(7));
        actionDock.setClickable(true);
        actionDock.setBackground(round("#FFFFFF", dp(22)));
        actionDock.setPadding(dp(7), dp(6), dp(7), dp(5));
        FrameLayout.LayoutParams bottom = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        bottom.setMargins(dp(10), 0, dp(10), dp(8));
        dockPanel = DriverResponsiveUi.mapPanelScroll(host, .45f);
        dockPanel.setElevation(dp(7));
        dockPanel.addView(actionDock, new ScrollView.LayoutParams(-1, -2));
        premiumMap.addView(dockPanel, bottom);

        headerPanel.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> host.updatePremiumMapInsets());
        dockPanel.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> host.updatePremiumMapInsets());
    }

    private TextView itineraryLine(String label, String color, java.util.function.Supplier<String> address, boolean pickup) {
        LinearLayout row = new LinearLayout(host);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView pin = text("●", 18, color, true);
        pin.setGravity(Gravity.CENTER);
        if (host.isFoodOrder() || DeliveryTripPresentation.special(host.order)) {
            android.widget.ImageView icon = new android.widget.ImageView(host);
            icon.setImageBitmap(FoodTripMarker.bitmap(host, color.equals("#C66200"),DeliveryTripPresentation.kind(host.order)));
            icon.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            row.addView(icon, new LinearLayout.LayoutParams(dp(30),dp(34)));
        } else row.addView(pin, new LinearLayout.LayoutParams(dp(34), dp(36)));
        LinearLayout copy = new LinearLayout(host);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(0, dp(2), 0, dp(2));
        copy.addView(text(label, 11, "#64748B", false));
        TextView place = text("", 14, "#0B3A78", true);
        place.setMaxLines(host.isFoodOrder() || DeliveryTripPresentation.special(host.order) ? 1 : 2);
        place.setEllipsize(android.text.TextUtils.TruncateAt.END);
        copy.addView(place);
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        row.setOnClickListener(v -> host.openTripLocation(label, address.get(),pickup));
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
