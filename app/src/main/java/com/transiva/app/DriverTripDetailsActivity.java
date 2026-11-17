package com.transiva.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.location.Location;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.Locale;

/** Full-screen details. Order updates and mutation guards are inherited from Driver Trip. */
public final class DriverTripDetailsActivity extends DriverTripActivity {
    private boolean noteOpen;

    @Override protected boolean tripDetailsScreen() { return true; }
    @Override protected void addMapCard() {}

    @Override protected void buildBase() {
        Location seed = getIntent().getParcelableExtra("trip_fix");
        if (TripArrivalLocation.verified(seed) != null) tripArrivalFix = seed;

        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(DriverThemeTokens.background(this));
        LinearLayout screen = column();
        page.addView(screen, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout toolbar = row();
        toolbar.setBackgroundColor(DriverThemeTokens.surface(this));
        toolbar.setElevation(dp(2));
        TextView back = text("‹", 30, "#0B3A78", true);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("Kembali ke peta");
        back.setOnClickListener(v -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(50), dp(52)));
        toolbar.addView(text("Detail perjalanan", 21, "#0B3A78", true));
        screen.addView(toolbar);

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(true);
        root = column();
        root.setPadding(dp(13), dp(11), dp(13), dp(12));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));
        screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout footer = column();
        footer.setBackgroundColor(DriverThemeTokens.surface(this));
        footer.setPadding(dp(13), dp(7), dp(13), dp(9));
        footer.setElevation(dp(3));
        if (order != null && !NavigationTargetPolicy.terminal(order)) {
            TextView hint = text("Konfirmasi tiba dari layar peta", 12, "#64748B", false);
            hint.setGravity(Gravity.CENTER);
            footer.addView(hint);
        }
        Button showMap = primary("Kembali ke peta");
        showMap.setBackground(DriverNavigationUi.roundRect(Color.rgb(0, 95, 225), dp(14)));
        showMap.setMinHeight(dp(48));
        showMap.setOnClickListener(v -> finish());
        footer.addView(showMap, btnLp(5));
        screen.addView(footer);

        // The map owns arrival and delivery slides. Keep their state model available for shared
        // eligibility updates, with only the detail-page actions attached here.
        actionDock = column();
        progressBar = new ProgressBar(this);
        progressBar.setVisibility(View.GONE);
        progressBar.setElevation(dp(8));
        page.addView(progressBar, new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER));
        setContentView(page);
        DriverAppSettings.apply(this);
    }

    @Override protected void renderEmpty() {
        root.removeAllViews();
        root.addView(text(order == null ? "Order tidak ditemukan." : "Perjalanan telah berakhir.",
                15, "#64748B", false));
    }

    @Override protected void onDriverLocationChanged(Location fix) {
        Location verified = TripArrivalLocation.verified(fix);
        if (verified != null && (tripArrivalFix == null
                || verified.getElapsedRealtimeNanos() >= tripArrivalFix.getElapsedRealtimeNanos())) {
            tripArrivalFix = verified;
            lastDriverLat = verified.getLatitude();
            lastDriverLng = verified.getLongitude();
        }
        refreshButtons();
    }

    @Override protected void addHeaderCard() {
        LinearLayout summary = compactCard();
        LinearLayout header = row();
        LinearLayout identity = column();
        identity.addView(text(vehicleEmoji() + "  " + cleanServiceLabel(), 17, "#0B3A78", true));
        TextView number = text("#" + orderId(), 11, "#64748B", false);
        number.setSingleLine(true);
        number.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        number.setOnClickListener(v -> {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Nomor order", orderId()));
        });
        number.setContentDescription("Salin nomor order " + orderId());
        identity.addView(number);
        header.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
        statusBadge = pill(statusLabel(status()), "#F2F7FF", "#0B3A78");
        statusBadge.setMaxWidth(dp(145));
        header.addView(statusBadge);
        summary.addView(header);
        divider(summary);
        LinearLayout metrics = row();
        double km = optDouble("distance_km", "trip_distance_km");
        NumberFormat distance = NumberFormat.getNumberInstance(new Locale("id", "ID"));
        distance.setMaximumFractionDigits(2);
        metric(metrics, "Jarak pesanan", km > 0 ? distance.format(km) + " km" : "—", 17);
        double eta = optDouble("duration_minutes", "estimated_minutes");
        metric(metrics, "Estimasi awal", eta > 0 ? zero(eta) + " menit" : "—", 17);
        summary.addView(metrics);
        distanceHint = text("", 13, "#0B3A78", true);
        distanceInfo = text("", 12, "#64748B", false);
        LinearLayout location = column();
        location.setPadding(dp(10), dp(7), dp(10), dp(7));
        location.setBackground(round("#F2F7FF", dp(12)));
        location.addView(distanceHint);
        location.addView(distanceInfo);
        slot(summary, location, 8);
        add(summary, 0, 0, 0, dp(8));
    }

    @Override protected void addLocationCard(String title, String address, boolean pickup) {
        if (!pickup) return;
        LinearLayout route = compactCard();
        route.addView(text("Rute perjalanan", 17, "#0B3A78", true));
        point(route, "Titik jemput", pickupAddress(), "#D97706");
        point(route, "Tujuan", deliveryAddress(), "#059669");
        add(route, 0, 0, 0, dp(8));
        addPaymentCard();
    }

    private void addPaymentCard() {
        LinearLayout payment = compactCard();
        LinearLayout header = row();
        header.addView(text("Pembayaran", 17, "#0B3A78", true), new LinearLayout.LayoutParams(0, -2, 1));
        boolean qris = "qris".equalsIgnoreCase(order.optString("payment_method"));
        boolean nonCash = qris || isNonCash();
        header.addView(pill(qris ? "QRIS lunas" : nonCash ? "TransPay" : "Tunai",
                nonCash ? "#F2F7FF" : DriverThemeTokens.isDark(this) ? "#382B20" : "#FFF0CF",
                nonCash ? "#0B3A78" : DriverThemeTokens.isDark(this) ? "#FCD98C" : "#955100"));
        payment.addView(header);
        LinearLayout totals = row();
        double orderTotal = optDouble("price", "fare", "total");
        metric(totals, nonCash ? "Total pesanan" : "Tagih pelanggan", rupiah(orderTotal), 21);
        JSONObject fee = order.optJSONObject("commission");
        if (fee != null && fee.has("driver_net") && !fee.isNull("driver_net"))
            metric(totals, "Pendapatan bersih", rupiah(fee.optDouble("driver_net")), 19);
        slot(payment, totals, 6);
        if (nonCash) payment.addView(text("Rp0 ditagih ke pelanggan", 12, "#64748B", false));
        String feeLine = fee != null && fee.has("app_fee") && !fee.isNull("app_fee")
                ? "Potongan " + rupiah(fee.optDouble("app_fee")) : "";
        JSONObject stop = order.optJSONObject("stop_pricing");
        if (stop == null) {
            try { stop = new JSONObject(order.optString("note", "{}")).optJSONObject("stop_pricing"); }
            catch (org.json.JSONException ignored) {}
        }
        if (stop != null) feeLine += (feeLine.isEmpty() ? "" : " · ") + "100% biaya tunggu untuk Anda";
        double voucher = optDouble("total_discount", "voucher_discount");
        if (!feeLine.isEmpty() || voucher > 0) divider(payment);
        if (!feeLine.isEmpty()) payment.addView(text(feeLine, 12, "#64748B", false));
        if (voucher > 0) {
            payment.addView(text("Potongan pelanggan " + rupiah(voucher) + " · ditanggung Transiva",
                    12, "#64748B", false));
        }
        add(payment, 0, 0, 0, dp(8));
    }

    @Override protected void addPlainNoteCard() {
        LinearLayout note = compactCard();
        LinearLayout line = row();
        line.setMinimumHeight(dp(30));
        line.addView(text("▤   Catatan pelanggan", 15, "#0B3A78", true),
                new LinearLayout.LayoutParams(0, -2, 1));
        TextView arrow = text(noteOpen ? "⌄" : "›", 22, "#64748B", true);
        line.addView(arrow);
        note.addView(line);
        TextView body = text(first(customerNote(), "Tidak ada catatan pelanggan."), 13, "#64748B", false);
        body.setVisibility(noteOpen ? View.VISIBLE : View.GONE);
        note.addView(body);
        note.setOnClickListener(v -> {
            noteOpen = !noteOpen;
            arrow.setText(noteOpen ? "⌄" : "›");
            body.setVisibility(noteOpen ? View.VISIBLE : View.GONE);
        });
        add(note, 0, 0, 0, dp(5));
    }

    @Override protected void refreshButtons() {
        super.refreshButtons();
        if (order == null || distanceHint == null || distanceInfo == null) return;
        boolean pickup = "taken".equals(status());
        if (!pickup && !"on_delivery".equals(status())) return;
        if (!pickup && DriverEcosystemFeatures.from(order).nextPendingWaypoint() != null) return;
        NavigationTargetPolicy.Target target = NavigationTargetPolicy.resolve(order,
                pickup ? "pickup" : "delivery", Double.NaN, Double.NaN);
        float distance = TripArrivalLocation.distance(trustedArrivalFix(), target);
        boolean near = distance >= 0 && distance <= ARRIVE_RADIUS_METER;
        String area = pickup ? "penjemputan" : "pengantaran";
        distanceHint.setText(distance < 0 ? "Menunggu lokasi terverifikasi"
                : near ? "✓ Di area " + area : "Menuju " + area);
        distanceInfo.setText(distance < 0 ? "Posisi GPS terbaru belum tersedia"
                : (distance < 1000 ? Math.round(distance) + " m" : meter(distance))
                        + " ke " + (pickup ? "titik jemput" : "tujuan"));
        View band = (View) distanceHint.getParent();
        if (!java.util.Objects.equals(band.getTag(), near)) {
            band.setTag(near);
            band.setBackground(round(near ? (DriverThemeTokens.isDark(this) ? "#16372D" : "#DDF8E9")
                    : "#F2F7FF", dp(12)));
        }
    }

    private void point(LinearLayout parent, String label, String address, String color) {
        LinearLayout line = row();
        TextView pin = text("●", 16, color, true);
        line.addView(pin, new LinearLayout.LayoutParams(dp(25), -2));
        LinearLayout copy = column();
        copy.addView(text(label, 12, "#64748B", false));
        copy.addView(text(first(address, "—"), 14, "#0B3A78", true));
        line.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        slot(parent, line, 7);
    }

    private void metric(LinearLayout parent, String title, String value, int size) {
        LinearLayout item = column();
        item.addView(text(value, size, "#0B3A78", true));
        item.addView(text(title, 12, "#64748B", false));
        parent.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private TextView pill(String text, String fill, String color) {
        TextView chip = text(text, 11, color, true);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(8), dp(5), dp(8), dp(5));
        chip.setBackground(round(fill, dp(14)));
        return chip;
    }

    private void divider(LinearLayout parent) {
        View line = new View(this);
        line.setBackgroundColor(DriverThemeTokens.border(this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1));
        lp.setMargins(0, dp(7), 0, dp(7));
        parent.addView(line, lp);
    }

    private void slot(LinearLayout parent, View view, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(top);
        parent.addView(view, lp);
    }

    private LinearLayout compactCard() {
        LinearLayout card = card();
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackground(round("#FFFFFF", dp(17)));
        card.setElevation(dp(1));
        return card;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }
}
