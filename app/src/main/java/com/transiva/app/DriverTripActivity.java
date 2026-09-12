package com.transiva.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.transiva.app.driver.data.DriverApiClient;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.MapsInitializer;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.Locale;
import android.util.Base64;
public class DriverTripActivity extends DriverTripActivityLayer2 {


    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        // Keep navigation/trip information visible while this Activity is in front.
        // Android automatically releases this flag when the Activity window is gone.
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try{
            getWindow().setStatusBarColor(Color.WHITE);
            getWindow().setNavigationBarColor(Color.WHITE);
            if(Build.VERSION.SDK_INT >= 23) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }catch(Exception e){ TransivaDiagnostics.error(this,"order","TRIP_WINDOW_SETUP_FAILED",e); }
        session = new SessionManager(this);
        api = new DriverApiClient(session);
        tripLocationController = new TripLocationController(this, new TripLocationController.Callback() {
            @Override public void onLocation(Location location) { onDriverLocationChanged(location); }
            @Override public void onPermissionRequired() {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 701);
            }
        });
        loadSession();
        tripSnapshotStore = new TripOrderSnapshotStore(this);
        loadOrder();
        buildBase();
        if(order == null){ renderEmpty(); return; }
        renderOrder();
        refreshButtons();
        startLocationWatch();

        // Warm StableRouteEngine cache before the driver taps Navigasi.
        // When a valid location already exists, native navigation can reuse it instantly.
        mainHandler.postDelayed(() -> {
            if(valid(lastDriverLat,lastDriverLng)) requestStableRoute(true);
        }, 250L);
    }
    @Override protected void onResume(){ super.onResume(); try{ if(mapView!=null) mapView.onResume(); }catch(Exception e){ TransivaDiagnostics.error(this,"order","TRIP_MAP_RESUME_FAILED",e); } if(order != null) startLocationWatch(); if(communicationController!=null) communicationController.onStart(); }
    @Override protected void onPause(){ if(communicationController!=null) communicationController.onStop(); stopLocationWatch(); try{ if(mapView!=null) mapView.onPause(); }catch(Exception e){ TransivaDiagnostics.error(this,"order","TRIP_MAP_PAUSE_FAILED",e); } super.onPause(); }
    @Override protected void onDestroy(){ if(communicationController!=null) communicationController.onStop(); stopLocationWatch(); try{ if(mapView != null) mapView.onDestroy(); }catch(Exception e){ TransivaDiagnostics.error(this,"order","TRIP_MAP_DESTROY_FAILED",e); } super.onDestroy(); }
    @Override public void onLowMemory(){ super.onLowMemory(); try{ if(mapView!=null) mapView.onLowMemory(); }catch(Exception ignored){ TransivaDiagnostics.error(this,"order","NON_FATAL_EXCEPTION",ignored); } }

    protected void loadSession(){
        try{
            SessionManager s = new SessionManager(this);
            driverUsername = first(s.getUsername(), s.getName(), "");
            driverType = normalizeDriverType(first(s.getDriverType(), s.getRole(), ""));
        }catch(Exception e){ TransivaDiagnostics.error(this,"session","TRIP_SESSION_LOAD_FAILED",e); }
        if(driverUsername.isEmpty()) driverUsername = getSharedPreferences(PREF_NAME, MODE_PRIVATE).getString("username", "");
        driverType = normalizeDriverType(first(driverType, getSharedPreferences(PREF_NAME, MODE_PRIVATE).getString("driver_type", ""), "motor"));
    }
    protected String normalizeDriverType(String value){
        value = first(value, "motor").toLowerCase(Locale.US).trim();
        if(value.equals("car") || value.equals("mobil") || value.equals("driver_car") || value.equals("transcar") || value.equals("angkot") || value.equals("taxi")) return "car";
        if(value.equals("bike") || value.equals("motorcycle") || value.equals("moto") || value.equals("driver_motor") || value.equals("transbike")) return "motor";
        return value.contains("car") || value.contains("mobil") ? "car" : "motor";
    }

    protected String resolveDriverTypeFromOrder(){
        if(order == null) return normalizeDriverType(driverType);
        String value = first(
                order.optString("driver_type"),
                order.optString("vehicle_type"),
                order.optString("price_mode"),
                order.optString("mode"),
                order.optString("service_mode"),
                order.optString("order_mode"),
                order.optString("active_driver_type"),
                driverType,
                "motor"
        );
        return normalizeDriverType(value);
    }

    protected String vehicleEmoji(){
        return "car".equals(resolveDriverTypeFromOrder()) ? "🚘" : "🏍️";
    }

    protected String vehicleLabel(){
        return "car".equals(resolveDriverTypeFromOrder()) ? "Mobil / Car" : "Motor / Bike";
    }

    protected void loadOrder(){
        try{
            String raw = first(getIntent().getStringExtra("order_json"), getIntent().getStringExtra("active_order_json"), pref("driver_active_order_json"), pref("active_order_json"), pref("activeOrder"));
            if(raw.trim().startsWith("{")) order = new JSONObject(raw);
        }catch(Exception ignored){ TransivaDiagnostics.error(this,"order","NON_FATAL_EXCEPTION",ignored); }
        if(order == null){
            String id = first(getIntent().getStringExtra("order_id"), pref("driver_active_order_id"));
            if(!id.isEmpty()){
                order = new JSONObject();
                try{
                    order.put("id", id); order.put("order_id", id); order.put("status", first(pref("driver_active_order_status"), "taken"));
                    order.put("pickup_address", pref("driver_active_pickup_address")); order.put("delivery_address", pref("driver_active_delivery_address"));
                    order.put("pickup_lat", pref("driver_active_pickup_lat")); order.put("pickup_lng", pref("driver_active_pickup_lng"));
                    order.put("delivery_lat", pref("driver_active_delivery_lat")); order.put("delivery_lng", pref("driver_active_delivery_lng"));
                    order.put("price", pref("driver_active_price"));
                }catch(Exception ignored){ TransivaDiagnostics.error(this,"order","NON_FATAL_EXCEPTION",ignored); }
            }
        }
        if(order != null){
            orderKind = first(getIntent().getStringExtra("order_kind"), order.optString("order_kind"), order.optString("source"), order.optString("source_table"), order.optString("type"), pref("driver_active_order_kind"), "orders").toLowerCase(Locale.US);
            orderKind = orderKind.contains("pickup") ? "pickup" : "order";
            driverType = resolveDriverTypeFromOrder();
            saveActiveOrder();
        }
    }
    protected void buildBase(){
        FrameLayout page = new FrameLayout(this); page.setBackgroundColor(Color.parseColor("#F3F8FF"));
        ScrollView scroll = new ScrollView(this); page.addView(scroll, new FrameLayout.LayoutParams(-1,-1));
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(18), dp(22), dp(18), dp(26));
        scroll.addView(root, new ScrollView.LayoutParams(-1,-2));
        progressBar = new ProgressBar(this); progressBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(dp(48), dp(48)); pp.gravity = Gravity.CENTER; page.addView(progressBar, pp);
        setContentView(page);
        DriverAppSettings.apply(this);
    }
    protected void renderEmpty(){
        root.removeAllViews(); top("Driver Trip", "Status perjalanan order native");
        LinearLayout c = card(); c.setPadding(dp(18), dp(16), dp(18), dp(16));
        c.addView(text("Order tidak ditemukan.", 16, "#64748B", false));
        Button back = outline("Kembali ke Dashboard"); back.setOnClickListener(v -> finish()); c.addView(back, btnLp(14)); add(c,0,dp(8),0,0);
    }
    protected void renderOrder(){
        root.removeAllViews(); top("Driver Trip", "Status perjalanan order native");
        addHeaderCard();
        // Aksi utama ditempatkan langsung setelah ringkasan agar selalu terlihat tanpa harus scroll ke bawah.
        addActions();
        addLocationCard("📍 Lokasi Penjemputan", pickupAddress(), true);
        addLocationCard("🏁 Lokasi Delivery", deliveryAddress(), false);
        addMapCard(); addFoodOrNoteCard();
    }
    protected void top(String title, String sub){
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0,0,0,dp(14));
        TextView back = text("‹", 38, "#0B3A78", true); back.setGravity(Gravity.CENTER); back.setBackground(round("#FFFFFF", dp(22))); back.setOnClickListener(v -> finish());
        row.addView(back, new LinearLayout.LayoutParams(dp(58), dp(58)));
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(14),0,0,0);
        col.addView(text(title, 27, "#0B3A78", true)); col.addView(text(sub, 14, "#64748B", false)); row.addView(col, new LinearLayout.LayoutParams(0,-2,1));
        TextView online = text("• Online", 13, "#059669", true); online.setGravity(Gravity.CENTER); online.setPadding(dp(12), dp(8), dp(12), dp(8)); online.setBackground(round("#DCFCE7", dp(22))); row.addView(online);
        root.addView(row);
    }
    protected void addHeaderCard(){
        LinearLayout h = card(); h.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL); h.addView(top);
        LinearLayout left = new LinearLayout(this); left.setOrientation(LinearLayout.VERTICAL); top.addView(left, new LinearLayout.LayoutParams(0,-2,1));
        left.addView(text(cleanServiceLabel() + " • " + vehicleLabel(), 14, "#64748B", true)); TextView id = text("#" + orderId(), 24, "#0B3A78", true); id.setMaxLines(2); left.addView(id);
        statusBadge = text(statusLabel(status()), 12, "#FFFFFF", true); statusBadge.setGravity(Gravity.CENTER); statusBadge.setPadding(dp(12), dp(7), dp(12), dp(7)); statusBadge.setBackground(gradient("#086BFF", "#2EA2FF", dp(18))); top.addView(statusBadge);
        LinearLayout stats = new LinearLayout(this); stats.setOrientation(LinearLayout.HORIZONTAL); stats.setGravity(Gravity.CENTER); LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1,-2); sp.setMargins(0, dp(14),0,0); h.addView(stats, sp);
        mini(stats, "💰", "Total Bayar", rupiah(optDouble("price", "fare", "total"))); mini(stats, vehicleEmoji(), "Jarak", one(optDouble("distance_km")) + " KM"); mini(stats, "⏱️", "Estimasi", zero(optDouble("duration_minutes")) + " menit");
        double orderVoucher = optDouble("voucher_discount"); if(orderVoucher > 0){ TextView subsidy=text("🏷 Customer memakai voucher " + rupiah(orderVoucher) + " • biaya ditanggung Transiva • pendapatan Anda tetap dihitung dari ongkir normal.",12,"#047857",true); subsidy.setPadding(dp(12),dp(10),dp(12),dp(10)); h.addView(subsidy); }
        distanceInfo = text("📡 Mengukur jarak driver...", 13, "#64748B", false); distanceInfo.setPadding(0, dp(10),0,0); h.addView(distanceInfo);
        distanceHint = text("", 13, "#059669", true); distanceHint.setPadding(dp(12), dp(9), dp(12), dp(9)); distanceHint.setBackground(stroke("#ECFDF5", "#86EFAC", dp(14), 1)); LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1,-2); hp.setMargins(0, dp(8),0,0); h.addView(distanceHint, hp);
        TextView pay = text(isNonCash() ? "💳 NON-TUNAI • TransPay" : "💵 TUNAI • Tagih customer", 13, isNonCash() ? "#1D4ED8" : "#B45309", true); pay.setGravity(Gravity.CENTER); pay.setPadding(dp(12),dp(9),dp(12),dp(9)); pay.setBackground(stroke(isNonCash()?"#EFF6FF":"#FFFBEB", isNonCash()?"#93C5FD":"#FCD34D", dp(14),1)); LinearLayout.LayoutParams payLp=new LinearLayout.LayoutParams(-1,-2); payLp.setMargins(0,dp(8),0,0); h.addView(pay,payLp);
        add(h,0,dp(8),0,dp(12));
    }
    protected void mini(LinearLayout parent, String icon, String label, String value){
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setGravity(Gravity.CENTER); box.setPadding(dp(3),0,dp(3),0);
        TextView i = text(icon, 20, "#0B3A78", false); i.setGravity(Gravity.CENTER); box.addView(i);
        TextView l = text(label, 11, "#64748B", false); l.setGravity(Gravity.CENTER); box.addView(l);
        TextView v = text(value, 13, "#111827", true); v.setGravity(Gravity.CENTER); box.addView(v);
        parent.addView(box, new LinearLayout.LayoutParams(0,-2,1));
    }
    protected void addLocationCard(String title, String body, boolean pickup){
        // Satu baris tanpa tombol navigasi samping. Tombol navigasi utama tetap ada di kartu Aksi Perjalanan.
        LinearLayout c = card(); c.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); c.addView(row);
        TextView label = text(title + ":", 15, "#0B3A78", true);
        label.setSingleLine(true);
        row.addView(label, new LinearLayout.LayoutParams(-2, -2));
        TextView address = text(first(body, "-"), 15, "#111827", false);
        address.setSingleLine(true);
        address.setEllipsize(android.text.TextUtils.TruncateAt.END);
        address.setPadding(dp(6), 0, 0, 0);
        row.addView(address, new LinearLayout.LayoutParams(0, -2, 1));
        add(c,0,0,0,dp(12));
    }
    protected void addMapCard(){
        LinearLayout c = card(); c.setPadding(dp(12), dp(12), dp(12), dp(12));
        c.addView(text("🗺️ Peta Perjalanan", 16, "#0B3A78", true));
        c.addView(text("Google Maps SDK • posisi driver, pickup, delivery, dan jalur perjalanan.", 12, "#64748B", false));
        mapView = new MapView(this);
        mapView.onCreate(null);
        try { MapsInitializer.initialize(getApplicationContext(), MapsInitializer.Renderer.LATEST, null); } catch (Throwable ignored) { TransivaDiagnostics.error(this,"order","NON_FATAL_EXCEPTION",ignored); }
        mapView.getMapAsync(map -> {
            googleMap = map; mapReady = true;
            try {
                googleMap.getUiSettings().setCompassEnabled(true);
                googleMap.getUiSettings().setZoomControlsEnabled(false);
                googleMap.getUiSettings().setMapToolbarEnabled(false);
                googleMap.setBuildingsEnabled(true);
            } catch (Throwable ignored) { TransivaDiagnostics.error(this,"order","NON_FATAL_EXCEPTION",ignored); }
            initNativeTripMarkers();
            applyPendingRoute();
            updateMap();
            if(pendingPickupRoutePoints.isEmpty()) requestStableRoute(true);
        });
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, dp(250)); mp.setMargins(0,dp(8),0,0);
        c.addView(mapView, mp); add(c,0,0,0,dp(12));
    }

    protected void initNativeTripMarkers(){
        if(googleMap==null) return;
        double pLat=coord("pickup_lat","user_lat"), pLng=coord("pickup_lng","user_lng");
        double dLat=coord("delivery_lat","destination_lat"), dLng=coord("delivery_lng","destination_lng");
        if(valid(pLat,pLng) && pickupMarker==null) pickupMarker=googleMap.addMarker(new MarkerOptions().position(new LatLng(pLat,pLng)).title("Pickup").icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));
        if(valid(dLat,dLng) && deliveryMarker==null) deliveryMarker=googleMap.addMarker(new MarkerOptions().position(new LatLng(dLat,dLng)).title("Delivery").icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)));
        fitNativeOverview();
    }

    protected java.util.List<LatLng> parseRoutePoints(String json){
        java.util.ArrayList<LatLng> out=new java.util.ArrayList<>();
        try{ JSONArray a=new JSONArray(first(json,"[]")); for(int i=0;i<a.length();i++){ JSONArray q=a.optJSONArray(i); if(q!=null && q.length()>=2){ double lat=q.optDouble(0),lng=q.optDouble(1); if(valid(lat,lng)) out.add(new LatLng(lat,lng)); } } }catch(Exception ignored){ TransivaDiagnostics.error(this,"order","NON_FATAL_EXCEPTION",ignored); }
        return out;
    }

    protected void fitNativeOverview(){
        if(googleMap==null) return;
        try{
            LatLngBounds.Builder b=new LatLngBounds.Builder(); int n=0;
            if(pickupMarker!=null){b.include(pickupMarker.getPosition());n++;}
            if(deliveryMarker!=null){b.include(deliveryMarker.getPosition());n++;}
            if(driverMarker!=null){b.include(driverMarker.getPosition());n++;}
            for(LatLng p:parseRoutePoints(pendingPickupRoutePoints)){b.include(p);n++;}
            for(LatLng p:parseRoutePoints(pendingDeliveryRoutePoints)){b.include(p);n++;}
            if(n>=2) mapView.post(() -> { try{ googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), dp(42))); }catch(Exception ignored){ TransivaDiagnostics.error(this,"order","NON_FATAL_EXCEPTION",ignored); } });
            else if(n==1){ LatLng x=driverMarker!=null?driverMarker.getPosition():(pickupMarker!=null?pickupMarker.getPosition():deliveryMarker.getPosition()); googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(x,15f)); }
        }catch(Exception ignored){ TransivaDiagnostics.error(this,"order","NON_FATAL_EXCEPTION",ignored); }
    }

    protected String routeTargetMode(){
        String st = status();
        if(st.equals("arrived_pickup") || st.equals("on_delivery") || st.equals("arrived_delivery")) return "delivery";
        return "pickup";
    }

    protected double bearing(double lat1, double lng1, double lat2, double lng2){
        double dLng = Math.toRadians(lng2 - lng1);
        lat1 = Math.toRadians(lat1);
        lat2 = Math.toRadians(lat2);
        double y = Math.sin(dLng) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLng);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    protected void addFoodOrNoteCard(){
        JSONObject food = parseFoodNote();
        if(food == null){ addPlainNoteCard(); return; }
        LinearLayout c = card(); c.setPadding(dp(16), dp(13), dp(16), dp(13));
        c.addView(text("🍔 Detail Order Makanan", 18, "#0B3A78", true));
        rowText(c, "🏪 Resto", first(food.optString("restaurant_name"), pickupAddress(), "Resto"));
        rowText(c, "🧾 Total Makanan", rupiah(food.optDouble("food_total", 0)));
        rowText(c, vehicleEmoji() + " Ongkir", rupiah(food.optDouble("delivery_fee", optDouble("price"))));
        double voucherDiscount = food.optDouble("voucher_discount", optDouble("voucher_discount"));
        if(voucherDiscount > 0){ rowText(c, "🏷 Voucher Customer", "- " + rupiah(voucherDiscount)); rowText(c, "🛡 Ditanggung", "Transiva • pendapatan driver tidak berkurang"); }
        rowText(c, "💳 Pembayaran", first(food.optString("payment_label"), food.optString("payment_method"), "-"));
        rowText(c, "💰 Total Bayar", rupiah(food.optDouble("total", optDouble("price", "total"))));
        TextView menuTitle = text("📦 Menu Pesanan", 16, "#0B3A78", true); menuTitle.setPadding(0, dp(12),0,dp(6)); c.addView(menuTitle);
        JSONArray items = food.optJSONArray("items");
        if(items == null || items.length() == 0){ c.addView(text("-", 14, "#64748B", false)); }
        else{
            for(int i=0;i<items.length();i++){
                JSONObject it = items.optJSONObject(i); if(it == null) continue;
                String name = first(it.optString("name"), it.optString("food_name"), it.optString("menu_name"), "Menu");
                int qty = it.optInt("qty", it.optInt("quantity", 1));
                double subtotal = it.optDouble("subtotal", it.optDouble("total", it.optDouble("price",0) * qty));
                LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(0, dp(7),0,dp(7));
                LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL);
                l.addView(text(name, 15, "#111827", true));
                l.addView(text(qty + "x pesanan", 12, "#64748B", false));
                String optionText = foodItemOptions(it);
                if(!optionText.isEmpty()) l.addView(text("• " + optionText, 12, "#475569", false));
                String itemNote = first(it.optString("note"), it.optString("customer_note"), "");
                if(!itemNote.isEmpty()) l.addView(text("Catatan: " + itemNote, 12, "#B45309", false));
                r.addView(l, new LinearLayout.LayoutParams(0,-2,1));
                r.addView(text(rupiah(subtotal), 14, "#111827", true)); c.addView(r);
            }
        }
        String merchantStatus = first(food.optString("merchant_status"), "");
        int cookMinutes = food.optInt("cook_minutes", 0);
        String readyAt = first(food.optString("estimated_ready_at"), "");
        if(!merchantStatus.isEmpty()){
            String kitchen = "Status merchant: " + merchantStatusLabel(merchantStatus);
            if(cookMinutes > 0) kitchen += " • estimasi " + cookMinutes + " menit";
            rowText(c, "🍳 Dapur", kitchen);
        }
        if(!readyAt.isEmpty()) rowText(c, "⏱ Siap sekitar", readyAt);
        String customerNote = first(food.optString("customer_note"), food.optString("note_customer"), food.optString("text"), "");
        if(customerNote.isEmpty()) customerNote = customerNote();
        if(!customerNote.isEmpty()) rowText(c, "📝 Catatan customer", customerNote);
        add(c,0,0,0,dp(12));
    }

    protected String merchantStatusLabel(String raw){
        String s = first(raw, "").toLowerCase(Locale.US).trim();
        if(s.equals("merchant_accepted") || s.equals("accepted")) return "Pesanan diterima";
        if(s.equals("preparing") || s.equals("processing")) return "Sedang disiapkan";
        if(s.equals("ready")) return "Pesanan siap diambil";
        if(s.equals("merchant_rejected") || s.equals("rejected")) return "Ditolak merchant";
        return raw;
    }

    protected String foodItemOptions(JSONObject item){
        if(item == null) return "";
        String direct = first(item.optString("options_text"), item.optString("selected_options_text"), "");
        if(!direct.isEmpty()) return direct;
        JSONArray a = item.optJSONArray("selected_options");
        if(a == null) a = item.optJSONArray("options");
        if(a == null || a.length() == 0) return "";
        StringBuilder b = new StringBuilder();
        for(int i=0;i<a.length();i++){
            Object value = a.opt(i);
            String label = "";
            if(value instanceof JSONObject){
                JSONObject o = (JSONObject)value;
                label = first(o.optString("name"), o.optString("label"), o.optString("option_name"), o.optString("value"), "");
            }else if(value != null && value != JSONObject.NULL){
                label = String.valueOf(value).trim();
            }
            if(label.isEmpty()) continue;
            if(b.length() > 0) b.append(", ");
            b.append(label);
        }
        return b.toString();
    }
}
