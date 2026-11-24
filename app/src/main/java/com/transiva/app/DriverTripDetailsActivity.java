package com.transiva.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
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
        toolbar.setElevation(dp(1));
        TextView back = text("‹", 30, "#0B3A78", true);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("Kembali ke peta");
        back.setOnClickListener(v -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        toolbar.addView(text("Detail perjalanan", 19, "#0B3A78", true),
                new LinearLayout.LayoutParams(0,-2,1));
        screen.addView(toolbar);

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        root = column();
        root.setPadding(dp(12), dp(8), dp(12), dp(6));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));
        screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout footer = column();
        footer.setBackgroundColor(DriverThemeTokens.surface(this));
        footer.setPadding(dp(12), dp(6), dp(12), dp(7));
        footer.setElevation(dp(3));
        Button showMap = primary("Kembali ke peta");
        showMap.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44ffffff),
                gradient("#074BB5", "#086BFF", dp(12)), null));
        showMap.setMinHeight(dp(48));
        showMap.setPadding(dp(12),dp(6),dp(12),dp(6));
        showMap.setOnClickListener(v -> finish());
        footer.addView(showMap, new LinearLayout.LayoutParams(-1,-2));
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
        summary.setBackground(gradient("#102C5A","#0758C8",dp(15)));
        LinearLayout header = row();
        LinearLayout identity = column();
        identity.setGravity(Gravity.CENTER_VERTICAL);
        identity.setMinimumHeight(dp(48));
        String service=cleanServiceLabel();
        if ("Transbike".equalsIgnoreCase(service)) service="TransRide";
        else if ("TransPickup".equalsIgnoreCase(service)) service="TransSend";
        identity.addView(onBlue(service,17,true));
        TextView number = text("#" + orderId(), 11, "#D5E8FF", false);
        number.setSingleLine(true);
        number.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        identity.setOnClickListener(v -> {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Nomor order", orderId()));
        });
        identity.setContentDescription(service + ". Salin nomor order " + orderId());
        identity.addView(number);
        android.widget.ImageView serviceIcon=new android.widget.ImageView(this);
        serviceIcon.setImageResource(isPickupOrder()?R.drawable.ic_service_pickup:
                isFoodOrder()?R.drawable.ic_service_food:
                "car".equalsIgnoreCase(order.optString("driver_type"))?R.drawable.ic_service_car:R.drawable.ic_service_ride);
        serviceIcon.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        serviceIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams iconSpace=new LinearLayout.LayoutParams(dp(31),dp(31));
        iconSpace.setMarginEnd(dp(9));
        header.addView(serviceIcon,iconSpace);
        header.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
        statusBadge = pill(statusLabel(status()), "#1FFFFFFF", "#FFFFFF");
        statusBadge.setMaxWidth(dp(116));
        LinearLayout.LayoutParams badgeParams=new LinearLayout.LayoutParams(-2,-2);
        badgeParams.setMarginStart(dp(8));
        header.addView(statusBadge,badgeParams);
        summary.addView(header);
        divider(summary,0x559ac5ff);
        LinearLayout metrics = row();
        metrics.setGravity(Gravity.TOP);
        double km = optDouble("distance_km", "trip_distance_km");
        NumberFormat distance = NumberFormat.getNumberInstance(new Locale("id", "ID"));
        distance.setMaximumFractionDigits(2);
        metric(metrics, "Jarak pesanan", km > 0 ? distance.format(km) + " km" : "—", true);
        double eta = optDouble("duration_minutes", "estimated_minutes");
        metricDivider(metrics,0x559ac5ff);
        metric(metrics, "Estimasi awal", eta > 0 ? zero(eta) + " menit" : "—", true);
        slot(summary,metrics,3);
        distanceHint = text("", 13, "#0B3A78", true);
        distanceInfo = text("", 12, "#64748B", false);
        LinearLayout location = column();
        location.setPadding(dp(9), dp(5), dp(9), dp(5));
        location.setBackground(surfaceTint());
        location.addView(distanceHint);
        location.addView(distanceInfo);
        slot(summary, location, 7);
        add(summary, 0, 0, 0, dp(6));
    }

    @Override protected void addLocationCard(String title, String address, boolean pickup) {
        if (!pickup) return;
        LinearLayout route = compactCard();
        route.addView(text("Rute perjalanan", 15, "#0B3A78", true));
        point(route, "Titik jemput", pickupAddress(), "#059669",false);
        org.json.JSONArray stops=DriverEcosystemFeatures.from(order).waypoints;
        for (int index=0; index<stops.length(); index++) {
            JSONObject stop=stops.optJSONObject(index);
            if (stop == null) continue;
            point(route,"Singgah "+stop.optInt("sequence",index+1),
                    first(stop.optString("address"),stop.optString("note")), "#D97706",false);
        }
        point(route, "Tujuan", deliveryAddress(), "#0B7CFF",true);
        add(route, 0, 0, 0, dp(6));
        addPaymentCard();
    }

    private void addPaymentCard() {
        LinearLayout payment = compactCard();
        LinearLayout header = row();
        header.addView(text("Pembayaran", 15, "#0B3A78", true), new LinearLayout.LayoutParams(0, -2, 1));
        boolean qris = "qris".equalsIgnoreCase(order.optString("payment_method"));
        boolean nonCash = qris || isNonCash();
        header.addView(pill(qris ? "QRIS lunas" : nonCash ? "TransPay" : "Tunai",
                nonCash ? "#F2F7FF" : DriverThemeTokens.isDark(this) ? "#382B20" : "#FFF0CF",
                nonCash ? "#0B3A78" : DriverThemeTokens.isDark(this) ? "#FCD98C" : "#955100"));
        payment.addView(header);
        LinearLayout totals = row();
        totals.setGravity(Gravity.TOP);
        double orderTotal = optDouble("price", "fare", "total");
        metric(totals, nonCash ? "Total pesanan" : "Tagih pelanggan", rupiah(orderTotal), false);
        JSONObject fee = order.optJSONObject("commission");
        if (fee != null && fee.has("driver_net") && !fee.isNull("driver_net")) {
            metricDivider(totals,DriverThemeTokens.border(this));
            metric(totals, "Pendapatan bersih", rupiah(fee.optDouble("driver_net")), false);
        }
        slot(payment, totals, 6);
        if (nonCash) payment.addView(text("Rp 0 ditagih ke pelanggan", 11, "#64748B", false));
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
        add(payment, 0, 0, 0, dp(6));
    }

    @Override protected void addPlainNoteCard() {
        LinearLayout note = compactCard();
        LinearLayout line = row();
        line.setMinimumHeight(dp(32));
        line.addView(text("▤   Catatan pelanggan", 15, "#0B3A78", true),
                new LinearLayout.LayoutParams(0, -2, 1));
        TextView arrow = text(noteOpen ? "⌄" : "›", 22, "#64748B", true);
        line.addView(arrow);
        note.addView(line);
        note.setMinimumHeight(dp(48));
        note.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22086BFF),cardShape(),null));
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
        boolean delivery="on_delivery".equals(status());
        View band=(View)distanceHint.getParent();
        distanceInfo.setVisibility(View.GONE);
        if (!pickup && !delivery) {
            String prompt=extensionNeedsArrival()?"Menuju tujuan tambahan"
                    :"arrived_pickup".equals(status()) && FoodTripPresentation.waitsForMerchant(order)
                        ?"Menunggu konfirmasi restoran"
                        :"arrived_delivery".equals(status()) && order.optInt("customer_received")!=1
                            ?"Menunggu konfirmasi pelanggan":"";
            band.setVisibility(prompt.isEmpty()?View.GONE:View.VISIBLE);
            if (!prompt.isEmpty()) {
                distanceHint.setText(prompt);
                distanceHint.setTextColor(DriverThemeTokens.textPrimary(this));
            }
            if (band.getTag()!=null) {
                band.setTag(null);
                band.setBackground(surfaceTint());
            }
            return;
        }
        band.setVisibility(View.VISIBLE);
        JSONObject nextStop=delivery?DriverEcosystemFeatures.from(order).nextPendingWaypoint():null;
        NavigationTargetPolicy.Target target = NavigationTargetPolicy.resolve(order,
                pickup ? "pickup" : "delivery", Double.NaN, Double.NaN);
        float distance = TripArrivalLocation.distance(trustedArrivalFix(), target);
        boolean near = distance >= 0 && distance <= (nextStop!=null ? 200 : ARRIVE_RADIUS_METER);
        String targetLabel=pickup?"titik jemput":nextStop==null?"tujuan":"singgah "+nextStop.optInt("sequence",1);
        String label=near?"Di area "+targetLabel:"Menuju "+targetLabel;
        String detail=distance < 0 ? "Menunggu GPS" : (distance < 1000 ? Math.round(distance)+" m" : meter(distance));
        distanceHint.setText((near?"✓  ":"")+label+"  ·  "+detail);
        distanceHint.setTextColor(near?(DriverThemeTokens.isDark(this)?0xff9de4bf:0xff116138)
                :DriverThemeTokens.textPrimary(this));
        if (!java.util.Objects.equals(band.getTag(),near)) {
            band.setTag(near);
            band.setBackground(near?round(DriverThemeTokens.isDark(this)?"#16372D":"#DDF8E9",dp(10)):surfaceTint());
        }
    }

    @Override protected String statusLabel(String state) {
        switch (state) {
            case "taken": return "Menuju jemput";
            case "arrived_pickup": return "Tiba di jemput";
            case "on_delivery": return "Menuju tujuan";
            case "arrived_delivery": return "Tiba di tujuan";
            default: return super.statusLabel(state);
        }
    }

    private void point(LinearLayout parent,String label,String address,String color,boolean last) {
        LinearLayout line=row();
        line.setGravity(Gravity.TOP);
        View track=new View(this) {
            private final android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(android.graphics.Canvas canvas) {
                float x=getWidth()/2f;
                if(!last) {
                    paint.setColor(DriverThemeTokens.border(getContext()));
                    paint.setStrokeWidth(dp(1));
                    for(int y=dp(13);y<getHeight();y+=dp(5)) canvas.drawLine(x,y,x,Math.min(y+dp(3),getHeight()),paint);
                }
                paint.setColor(DriverThemeTokens.color(getContext(),color));
                canvas.drawCircle(x,dp(8),dp(4),paint);
            }
        };
        track.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        line.addView(track,new LinearLayout.LayoutParams(dp(18),-1));
        LinearLayout copy=column();
        copy.setPadding(dp(3),0,0,last?0:dp(6));
        copy.addView(text(label,11,"#64748B",false));
        copy.addView(text(first(address,"—"),13,"#0B3A78",true));
        line.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
        slot(parent,line,parent.getChildCount()==1?6:0);
    }

    private void metricDivider(LinearLayout row,int color) {
        View line=new View(this);
        line.setBackgroundColor(color);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(dp(1),-1);
        params.setMarginEnd(dp(11));
        row.addView(line,params);
    }

    private void metric(LinearLayout parent, String title, String value, boolean onBlue) {
        LinearLayout item=column();
        item.setPadding(0,0,dp(6),0);
        item.addView(onBlue?onBlue(value,16,true):text(value,17,"#0B3A78",true));
        item.addView(text(title,11,onBlue?"#D5E8FF":"#64748B",false));
        parent.addView(item,new LinearLayout.LayoutParams(0,-2,1));
    }

    private TextView onBlue(String value,int size,boolean bold) {
        TextView label=text(value,size,"#FFFFFF",bold);
        label.setTextColor(android.graphics.Color.WHITE);
        return label;
    }

    private TextView pill(String text, String fill, String color) {
        TextView chip = "#FFFFFF".equals(color)?onBlue(text,11,true):text(text,11,color,true);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(8), dp(5), dp(8), dp(5));
        chip.setBackground(round(fill, dp(14)));
        return chip;
    }

    private void divider(LinearLayout parent) {
        divider(parent,DriverThemeTokens.border(this));
    }

    private void divider(LinearLayout parent,int color) {
        View line = new View(this);
        line.setBackgroundColor(color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1));
        lp.setMargins(0, dp(5), 0, dp(5));
        parent.addView(line, lp);
    }

    private void slot(LinearLayout parent, View view, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(top);
        parent.addView(view, lp);
    }

    private LinearLayout compactCard() {
        LinearLayout card=column();
        card.setPadding(dp(11),dp(8),dp(11),dp(8));
        card.setBackground(cardShape());
        return card;
    }

    private GradientDrawable cardShape() {
        return stroke("#FFFFFF","#D9E2EE",dp(14),1);
    }

    private GradientDrawable surfaceTint() {
        return DriverNavigationUi.roundRect(DriverThemeTokens.surfaceAlt(this),dp(10));
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
