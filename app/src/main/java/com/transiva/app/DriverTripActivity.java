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

public class DriverTripActivity extends Activity {
    private static final String BASE_URL = "https://transiva.my.id/server/";
    private static final String WEB_APP_URL = "https://transiva.my.id/";
    private static final String LEAFLET_CSS = WEB_APP_URL + "js/leaflet.css";
    private static final String LEAFLET_JS  = WEB_APP_URL + "js/leaflet.js";
    private static final String PREF_NAME = "transiva";
    private static final int TIMEOUT_MS = 20000;
    private static final float ARRIVE_RADIUS_METER = 100f;
    private static final float MAP_ANIMATION_MIN_DISTANCE_METER = 5.0f;
    private static final long GPS_PRIORITY_MS = 8000L;
    private static final long MAX_LOCATION_AGE_MS = 30000L;
    private static final long OUT_OF_ORDER_TOLERANCE_MS = 1500L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private LinearLayout root;
    private ProgressBar progressBar;
    private TextView statusBadge, distanceInfo, distanceHint;
    private MapView mapView;
    private GoogleMap googleMap;
    private Marker driverMarker, pickupMarker, deliveryMarker;
    private Polyline pickupPolyline, deliveryPolyline;
    private SlideActionView arrivedPickupBtn, startDeliveryBtn, arrivedDeliveryBtn, finishBtn;
    private Button updatePriceBtn, cancelOrderBtn, customerChatBtn;
    private TripCancellationController cancellationController;
    private TripCommunicationController communicationController;
    private TripLocationController tripLocationController;
    private JSONObject order;
    private String driverUsername = "";
    private String driverType = "motor";
    private String orderKind = "order";
    private double lastDriverLat = 0, lastDriverLng = 0;
    private double renderedDriverLat = 0, renderedDriverLng = 0;
    private double prevDriverLat = 0, prevDriverLng = 0;
    private boolean updatingStatus = false;
    private boolean mapReady = false;
    private SessionManager session;
    private DriverApiClient api;
    private final SmoothLocationEngine smoothLocation = new SmoothLocationEngine(2500L);
    private volatile boolean routeRequestInFlight = false;
    private long lastRouteRequestAt = 0L;
    private double lastRouteFromLat = 0d, lastRouteFromLng = 0d;
    private String lastNativeRouteMode = "";
    private String pendingRoutePoints = "";
    private double pendingRouteKm = 0d;
    private double pendingRouteSeconds = 0d;
    // Overview map: dua segmen tetap agar kamera tidak melompat setiap GPS berubah.
    private String pendingPickupRoutePoints = "";
    private String pendingDeliveryRoutePoints = "";
    private double pendingPickupRouteKm = 0d;
    private double pendingPickupRouteSeconds = 0d;
    private double pendingDeliveryRouteKm = 0d;
    private double pendingDeliveryRouteSeconds = 0d;
    private double tripStartLat = 0d, tripStartLng = 0d;
    private boolean overviewRoutesLoaded = false;
    private boolean overviewMapApplied = false;
    private String lastOverviewRouteStatus = "";
    private double currentSpeedKmh = 0d;
    private double averageSpeedKmh = 0d;
    private double speedSampleSum = 0d;
    private long speedSampleCount = 0L;
    private Location lastSpeedLocation = null;


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

    private void loadSession(){
        try{
            SessionManager s = new SessionManager(this);
            driverUsername = first(s.getUsername(), s.getName(), "");
            driverType = normalizeDriverType(first(s.getDriverType(), s.getRole(), ""));
        }catch(Exception e){ TransivaDiagnostics.error(this,"session","TRIP_SESSION_LOAD_FAILED",e); }
        if(driverUsername.isEmpty()) driverUsername = getSharedPreferences(PREF_NAME, MODE_PRIVATE).getString("username", "");
        driverType = normalizeDriverType(first(driverType, getSharedPreferences(PREF_NAME, MODE_PRIVATE).getString("driver_type", ""), "motor"));
    }
    private String normalizeDriverType(String value){
        value = first(value, "motor").toLowerCase(Locale.US).trim();
        if(value.equals("car") || value.equals("mobil") || value.equals("driver_car") || value.equals("transcar") || value.equals("angkot") || value.equals("taxi")) return "car";
        if(value.equals("bike") || value.equals("motorcycle") || value.equals("moto") || value.equals("driver_motor") || value.equals("transbike")) return "motor";
        return value.contains("car") || value.contains("mobil") ? "car" : "motor";
    }

    private String resolveDriverTypeFromOrder(){
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

    private String vehicleEmoji(){
        return "car".equals(resolveDriverTypeFromOrder()) ? "🚘" : "🏍️";
    }

    private String vehicleLabel(){
        return "car".equals(resolveDriverTypeFromOrder()) ? "Mobil / Car" : "Motor / Bike";
    }

    private void loadOrder(){
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
    private void buildBase(){
        FrameLayout page = new FrameLayout(this); page.setBackgroundColor(Color.parseColor("#F3F8FF"));
        ScrollView scroll = new ScrollView(this); page.addView(scroll, new FrameLayout.LayoutParams(-1,-1));
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(18), dp(22), dp(18), dp(26));
        scroll.addView(root, new ScrollView.LayoutParams(-1,-2));
        progressBar = new ProgressBar(this); progressBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(dp(48), dp(48)); pp.gravity = Gravity.CENTER; page.addView(progressBar, pp);
        setContentView(page);
        DriverAppSettings.apply(this);
    }
    private void renderEmpty(){
        root.removeAllViews(); top("Driver Trip", "Status perjalanan order native");
        LinearLayout c = card(); c.setPadding(dp(18), dp(16), dp(18), dp(16));
        c.addView(text("Order tidak ditemukan.", 16, "#64748B", false));
        Button back = outline("Kembali ke Dashboard"); back.setOnClickListener(v -> finish()); c.addView(back, btnLp(14)); add(c,0,dp(8),0,0);
    }
    private void renderOrder(){
        root.removeAllViews(); top("Driver Trip", "Status perjalanan order native");
        addHeaderCard();
        // Aksi utama ditempatkan langsung setelah ringkasan agar selalu terlihat tanpa harus scroll ke bawah.
        addActions();
        addLocationCard("📍 Lokasi Penjemputan", pickupAddress(), true);
        addLocationCard("🏁 Lokasi Delivery", deliveryAddress(), false);
        addMapCard(); addFoodOrNoteCard();
    }
    private void top(String title, String sub){
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0,0,0,dp(14));
        TextView back = text("‹", 38, "#0B3A78", true); back.setGravity(Gravity.CENTER); back.setBackground(round("#FFFFFF", dp(22))); back.setOnClickListener(v -> finish());
        row.addView(back, new LinearLayout.LayoutParams(dp(58), dp(58)));
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(14),0,0,0);
        col.addView(text(title, 27, "#0B3A78", true)); col.addView(text(sub, 14, "#64748B", false)); row.addView(col, new LinearLayout.LayoutParams(0,-2,1));
        TextView online = text("• Online", 13, "#059669", true); online.setGravity(Gravity.CENTER); online.setPadding(dp(12), dp(8), dp(12), dp(8)); online.setBackground(round("#DCFCE7", dp(22))); row.addView(online);
        root.addView(row);
    }
    private void addHeaderCard(){
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
    private void mini(LinearLayout parent, String icon, String label, String value){
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setGravity(Gravity.CENTER); box.setPadding(dp(3),0,dp(3),0);
        TextView i = text(icon, 20, "#0B3A78", false); i.setGravity(Gravity.CENTER); box.addView(i);
        TextView l = text(label, 11, "#64748B", false); l.setGravity(Gravity.CENTER); box.addView(l);
        TextView v = text(value, 13, "#111827", true); v.setGravity(Gravity.CENTER); box.addView(v);
        parent.addView(box, new LinearLayout.LayoutParams(0,-2,1));
    }
    private void addLocationCard(String title, String body, boolean pickup){
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
    private void addMapCard(){
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

    private void initNativeTripMarkers(){
        if(googleMap==null) return;
        double pLat=coord("pickup_lat","user_lat"), pLng=coord("pickup_lng","user_lng");
        double dLat=coord("delivery_lat","destination_lat"), dLng=coord("delivery_lng","destination_lng");
        if(valid(pLat,pLng) && pickupMarker==null) pickupMarker=googleMap.addMarker(new MarkerOptions().position(new LatLng(pLat,pLng)).title("Pickup").icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)));
        if(valid(dLat,dLng) && deliveryMarker==null) deliveryMarker=googleMap.addMarker(new MarkerOptions().position(new LatLng(dLat,dLng)).title("Delivery").icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)));
        fitNativeOverview();
    }

    private java.util.List<LatLng> parseRoutePoints(String json){
        java.util.ArrayList<LatLng> out=new java.util.ArrayList<>();
        try{ JSONArray a=new JSONArray(first(json,"[]")); for(int i=0;i<a.length();i++){ JSONArray q=a.optJSONArray(i); if(q!=null && q.length()>=2){ double lat=q.optDouble(0),lng=q.optDouble(1); if(valid(lat,lng)) out.add(new LatLng(lat,lng)); } } }catch(Exception ignored){ TransivaDiagnostics.error(this,"order","NON_FATAL_EXCEPTION",ignored); }
        return out;
    }

    private void fitNativeOverview(){
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

    private String routeTargetMode(){
        String st = status();
        if(st.equals("arrived_pickup") || st.equals("on_delivery") || st.equals("arrived_delivery")) return "delivery";
        return "pickup";
    }

    private double bearing(double lat1, double lng1, double lat2, double lng2){
        double dLng = Math.toRadians(lng2 - lng1);
        lat1 = Math.toRadians(lat1);
        lat2 = Math.toRadians(lat2);
        double y = Math.sin(dLng) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLng);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    private void addFoodOrNoteCard(){
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

    private String merchantStatusLabel(String raw){
        String s = first(raw, "").toLowerCase(Locale.US).trim();
        if(s.equals("merchant_accepted") || s.equals("accepted")) return "Pesanan diterima";
        if(s.equals("preparing") || s.equals("processing")) return "Sedang disiapkan";
        if(s.equals("ready")) return "Pesanan siap diambil";
        if(s.equals("merchant_rejected") || s.equals("rejected")) return "Ditolak merchant";
        return raw;
    }

    private String foodItemOptions(JSONObject item){
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
    private JSONObject parseFoodNote(){
        try{ JSONObject d = new JSONObject(first(order.optString("note"), "{}")); return "food".equalsIgnoreCase(d.optString("type")) ? d : null; }catch(Exception e){ return null; }
    }
    private String customerNote(){
        String direct = first(
                order.optString("customer_note"),
                order.optString("note_customer"),
                order.optString("order_note"),
                order.optString("special_instructions"),
                order.optString("instructions"),
                ""
        );
        if(!direct.isEmpty()) return direct;

        String raw = first(order.optString("note"), order.optString("item_note"), order.optString("description"), "");
        if(raw.isEmpty() || "-".equals(raw)) return "";
        if(raw.startsWith("{")){
            try{
                JSONObject d = new JSONObject(raw);
                return first(
                        d.optString("customer_note"),
                        d.optString("note_customer"),
                        d.optString("text"),
                        d.optString("note"),
                        d.optString("special_instructions"),
                        d.optString("instructions"),
                        d.optString("message"),
                        d.optString("remark"),
                        ""
                );
            }catch(Exception ignored){ return ""; }
        }
        if(raw.startsWith("[")) return "";
        return raw;
    }

    private void addPlainNoteCard(){
        String note = customerNote();
        LinearLayout c = card();
        c.setPadding(dp(16), dp(13), dp(16), dp(13));
        c.addView(text("📝 Catatan Customer", 16, "#0B3A78", true));
        TextView n = text(note.isEmpty() ? "Tidak ada catatan customer." : note,
                14, note.isEmpty() ? "#64748B" : "#111827", false);
        n.setPadding(0, dp(6),0,0);
        c.addView(n);
        add(c,0,0,0,dp(12));
    }
    private void rowText(LinearLayout p, String l, String v){
        LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(0, dp(7),0,dp(7));
        r.addView(text(l, 14, "#64748B", false), new LinearLayout.LayoutParams(0,-2,1)); r.addView(text(v, 14, "#111827", true)); p.addView(r);
    }
    private void addActions(){
        LinearLayout c = card(); c.setPadding(dp(16), dp(16), dp(16), dp(16));
        c.addView(text("Perjalanan", 17, "#0F172A", true));
        TextView guide = text("Geser ke kanan untuk mengubah status. Ini mencegah status berubah karena salah sentuh.", 12, "#64748B", false);
        guide.setPadding(0, dp(4), 0, dp(4)); c.addView(guide);

        arrivedPickupBtn = slideAction("📍 Geser • Tiba di Penjemputan", () -> updateStatus("arrived_pickup")); c.addView(arrivedPickupBtn, slideLp(8));
        startDeliveryBtn = slideAction(orderKind.equals("pickup") ? "📦 Geser • Paket Diambil, Mulai Antar" : vehicleEmoji() + " Geser • Mulai Perjalanan", () -> updateStatus("on_delivery")); c.addView(startDeliveryBtn, slideLp(8));
        arrivedDeliveryBtn = slideAction("🏁 Geser • Tiba di Pengantaran", () -> updateStatus("arrived_delivery")); c.addView(arrivedDeliveryBtn, slideLp(8));
        finishBtn = slideAction("✅ Geser • Selesaikan Order", () -> { if (isPickupOrder()) showPickupOtpDialog(); else updateStatus("finished"); }); c.addView(finishBtn, slideLp(8));
        updatePriceBtn = outline("💰 Update Total"); updatePriceBtn.setOnClickListener(v -> showUpdatePriceDialog()); c.addView(updatePriceBtn, btnLp(8));
        cancelOrderBtn = dangerOutlineButton("Batalkan Order");
        cancellationController = new TripCancellationController(this, order, session, cancelOrderBtn);
        cancelOrderBtn.setOnClickListener(v -> cancellationController.show());
        c.addView(cancelOrderBtn, btnLp(8));

        if (communicationController != null) communicationController.onStop();
        LinearLayout quick = new LinearLayout(this); quick.setOrientation(LinearLayout.HORIZONTAL);
        customerChatBtn = primary("💬 Chat");
        communicationController = new TripCommunicationController(this, order, driverUsername, customerChatBtn);
        communicationController.onStart();
        customerChatBtn.setOnClickListener(v -> communicationController.openCustomerChat());
        quick.addView(customerChatBtn, new LinearLayout.LayoutParams(0, dp(50), 1));
        Button nav = outline("➤ Navigasi"); nav.setOnClickListener(v -> communicationController.openNavigation(!isDeliveryPhase(status()), lastDriverLat, lastDriverLng));
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, dp(50), 1); np.setMargins(dp(8),0,0,0); quick.addView(nav, np);
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(-1,-2); qp.setMargins(0,dp(10),0,0); c.addView(quick, qp);
        if (isFoodOrder()) {
            Button merchantChat = outline("🏪 Chat Merchant");
            merchantChat.setOnClickListener(v -> communicationController.openMerchantChat());
            c.addView(merchantChat, btnLp(8));
        }
        add(c,0,0,0,dp(12));
    }
    private LinearLayout.LayoutParams slideLp(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(72));
        p.setMargins(0, dp(top), 0, 0);
        return p;
    }

    private SlideActionView slideAction(String label, Runnable action) {
        return new SlideActionView(label, action);
    }

    /** Premium thick progress track for the trip status slider. */
    private Drawable premiumSliderTrack() {
        GradientDrawable base = round("#DCEBFA", dp(18));
        base.setSize(dp(180), dp(26));
        base.setStroke(dp(1), Color.parseColor("#BBD8F5"));

        GradientDrawable fill = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.parseColor("#0878F9"), Color.parseColor("#29A8FF")}
        );
        fill.setCornerRadius(dp(18));
        fill.setSize(dp(180), dp(26));
        ClipDrawable clip = new ClipDrawable(fill, Gravity.LEFT, ClipDrawable.HORIZONTAL);

        LayerDrawable layers = new LayerDrawable(new Drawable[]{base, clip});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        return layers;
    }

    private Drawable premiumSliderThumb() {
        GradientDrawable thumb = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.parseColor("#FFFFFF"), Color.parseColor("#EAF5FF")}
        );
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setSize(dp(42), dp(42));
        thumb.setStroke(dp(3), Color.parseColor("#0878F9"));
        return thumb;
    }

    private final class SlideActionView extends LinearLayout {
        private final TextView labelView;
        private final SeekBar slider;
        private final Runnable action;
        private final String idleLabel;
        private boolean fired = false;

        SlideActionView(String label, Runnable action) {
            super(DriverTripActivity.this);
            this.action = action;
            this.idleLabel = label;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(14), dp(7), dp(10), dp(7));
            setBackground(stroke("#F7FBFF", "#B9D9FA", dp(20), 1));
            setElevation(dp(2));

            labelView = text(label, 13, "#0B3A78", true);
            labelView.setGravity(Gravity.CENTER_VERTICAL);
            addView(labelView, new LinearLayout.LayoutParams(0, -1, 1));

            slider = new SeekBar(DriverTripActivity.this);
            slider.setMax(100);
            slider.setProgress(0);
            slider.setProgressDrawable(premiumSliderTrack());
            slider.setThumb(premiumSliderThumb());
            if (Build.VERSION.SDK_INT >= 21) slider.setSplitTrack(false);
            slider.setThumbOffset(0);
            slider.setPadding(dp(2), 0, dp(2), 0);
            slider.setMinimumHeight(dp(48));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(158), dp(50));
            slider.setLayoutParams(sp);
            addView(slider);

            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser || fired) return;
                    if (progress > 8) {
                        labelView.setText(progress >= 92 ? "Lepas untuk konfirmasi ✓" : "Geser sampai penuh  ›››");
                    } else {
                        labelView.setText(idleLabel);
                    }
                }

                @Override public void onStartTrackingTouch(SeekBar seekBar) {
                    if (!isEnabled() || updatingStatus) return;
                    seekBar.animate().scaleX(1.025f).scaleY(1.08f).setDuration(120L).start();
                    labelView.animate().alpha(0.72f).setDuration(110L).withEndAction(() ->
                            labelView.animate().alpha(1f).setDuration(140L).start()).start();
                }

                @Override public void onStopTrackingTouch(SeekBar seekBar) {
                    seekBar.animate().scaleX(1f).scaleY(1f).setDuration(150L).start();
                    if (!isEnabled() || updatingStatus) {
                        animateBack(seekBar);
                        return;
                    }
                    if (seekBar.getProgress() >= 92 && !fired) {
                        fired = true;
                        seekBar.setProgress(100);
                        labelView.setText("✓ Memproses status...");
                        if (action != null) action.run();
                        mainHandler.postDelayed(() -> {
                            fired = false;
                            labelView.setText(idleLabel);
                            if (slider != null) animateBack(slider);
                        }, 950L);
                    } else {
                        labelView.setText("Geser sampai ujung untuk konfirmasi");
                        animateBack(seekBar);
                        mainHandler.postDelayed(() -> {
                            if (!fired && labelView != null) labelView.setText(idleLabel);
                        }, 320L);
                    }
                }
            });
        }

        private void animateBack(SeekBar seekBar) {
            try {
                ObjectAnimator back = ObjectAnimator.ofInt(seekBar, "progress", seekBar.getProgress(), 0);
                back.setDuration(240L);
                back.start();
            } catch (Exception ignored) {
                seekBar.setProgress(0);
            }
        }

        @Override public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            if (slider != null) slider.setEnabled(enabled);
            if (labelView != null) {
                labelView.setAlpha(enabled ? 1f : 0.42f);
                if (!fired) labelView.setText(idleLabel);
            }
            setAlpha(enabled ? 1f : 0.66f);
        }
    }

    private void startLocationWatch(){ if(order!=null && tripLocationController!=null) tripLocationController.start(); }
    private void stopLocationWatch(){ if(tripLocationController!=null) tripLocationController.stop(); }



    private void onDriverLocationChanged(Location l){
        SmoothLocationEngine.Fix fix = smoothLocation.offer(l);
        if(fix == null) return;

        Location accepted = fix.location;
        double newLat = accepted.getLatitude();
        double newLng = accepted.getLongitude();
        if(!valid(tripStartLat, tripStartLng)){
            tripStartLat = newLat;
            tripStartLng = newLng;
        }
        lastDriverLat = newLat;
        lastDriverLng = newLng;
        updateSpeedMetrics(accepted);
        pushSpeedToMap();

        // Route calculation starts as soon as we have a valid location.
        // It no longer waits for the WebView/Leaflet page to finish loading.
        requestStableRoute(false);

        if(fix.render){
            updateMap();
            renderedDriverLat = newLat;
            renderedDriverLng = newLng;
        }
        refreshButtons();
    }

    private float distanceBetween(double aLat, double aLng, double bLat, double bLng){
        try{
            float[] r = new float[1];
            Location.distanceBetween(aLat, aLng, bLat, bLng, r);
            return r[0];
        }catch(Exception e){ return 999f; }
    }

    private void updateMap(){
        try{
            if(googleMap == null || !mapReady || !valid(lastDriverLat, lastDriverLng)) return;
            double deg = valid(prevDriverLat, prevDriverLng) ? bearing(prevDriverLat, prevDriverLng, lastDriverLat, lastDriverLng) : 0d;
            LatLng pos=new LatLng(lastDriverLat,lastDriverLng);
            if(driverMarker==null){
                driverMarker=googleMap.addMarker(new MarkerOptions()
                        .position(pos)
                        .title("Posisi Driver")
                        .flat(true)
                        .rotation((float)deg)
                        .anchor(.5f,.5f)
                        .icon(driverVehicleIcon()));
            }else{ driverMarker.setPosition(pos); driverMarker.setRotation((float)deg); }
            requestStableRoute(false);
            prevDriverLat = lastDriverLat; prevDriverLng = lastDriverLng;
        }catch(Exception ignored){ TransivaDiagnostics.error(this,"order","NON_FATAL_EXCEPTION",ignored); }
    }

    private com.google.android.gms.maps.model.BitmapDescriptor driverVehicleIcon(){
        String name = "car".equals(resolveDriverTypeFromOrder()) ? "map_car_top" : "map_motor_top";
        try{
            Bitmap raw = ResourceUpdateManager.loadBitmapOverride(this, "images/" + name + ".webp");
            if(raw == null) raw = ResourceUpdateManager.loadBitmapOverride(this, "images/" + name + ".png");
            if(raw == null){
                int id = getResources().getIdentifier(name, "drawable", getPackageName());
                if(id > 0) raw = BitmapFactory.decodeResource(getResources(), id);
            }
            if(raw != null){
                int size = dp("car".equals(resolveDriverTypeFromOrder()) ? 46 : 42);
                Bitmap scaled = Bitmap.createScaledBitmap(raw, size, size, true);
                return BitmapDescriptorFactory.fromBitmap(scaled);
            }
        }catch(Exception ignored){ TransivaDiagnostics.error(this,"order","NON_FATAL_EXCEPTION",ignored); }
        return BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE);
    }

    private void requestStableRoute(boolean force){
        if(googleMap == null || !valid(lastDriverLat,lastDriverLng)) return;
        final double pLat=coord("pickup_lat","user_lat"), pLng=coord("pickup_lng","user_lng");
        final double dLat=coord("delivery_lat","destination_lat"), dLng=coord("delivery_lng","destination_lng");
        if(!valid(pLat,pLng) || !valid(dLat,dLng) || routeRequestInFlight) return;
        if(!valid(tripStartLat, tripStartLng)){
            tripStartLat=lastDriverLat;
            tripStartLng=lastDriverLng;
        }
        final String currentStatus=status();
        long now=System.currentTimeMillis();
        boolean statusChanged=!currentStatus.equals(lastOverviewRouteStatus);
        if(!force && overviewRoutesLoaded && !statusChanged && now-lastRouteRequestAt<45000L) return;
        routeRequestInFlight=true;
        lastRouteRequestAt=now;
        final double startLat=tripStartLat, startLng=tripStartLng;
        DriverNetworkExecutor.execute(() -> {
            try{
                StableRouteEngine.Result firstLeg=StableRouteEngine.fetch(startLat,startLng,pLat,pLng);
                StableRouteEngine.Result secondLeg=StableRouteEngine.fetch(pLat,pLng,dLat,dLng);
                pendingPickupRoutePoints=firstLeg.pointsJson();
                pendingPickupRouteKm=firstLeg.distanceMeters/1000d;
                pendingPickupRouteSeconds=firstLeg.durationSeconds;
                pendingDeliveryRoutePoints=secondLeg.pointsJson();
                pendingDeliveryRouteKm=secondLeg.distanceMeters/1000d;
                pendingDeliveryRouteSeconds=secondLeg.durationSeconds;
                overviewRoutesLoaded=true;
                lastOverviewRouteStatus=currentStatus;
                mainHandler.post(this::applyPendingRoute);
            }catch(Exception ignored){
                // Jika OSRM gagal, marker tetap bergerak dan rute lama tidak dihapus.
            }finally{
                routeRequestInFlight=false;
            }
        });
    }

    private void applyPendingRoute(){
        if(googleMap==null || !mapReady) return;
        java.util.List<LatLng> p=parseRoutePoints(pendingPickupRoutePoints);
        java.util.List<LatLng> d=parseRoutePoints(pendingDeliveryRoutePoints);
        if(p.size()<2 || d.size()<2) return;
        try{
            if(pickupPolyline!=null) pickupPolyline.remove();
            if(deliveryPolyline!=null) deliveryPolyline.remove();
            boolean pickupDone="delivery".equals(routeTargetMode());
            pickupPolyline=googleMap.addPolyline(new PolylineOptions().addAll(p).width(dp(5)).color(Color.parseColor(pickupDone?"#94A3B8":"#1683FF")).geodesic(true).zIndex(2f));
            deliveryPolyline=googleMap.addPolyline(new PolylineOptions().addAll(d).width(dp(5)).color(Color.parseColor("#16A34A")).geodesic(true).zIndex(2f));
            if(!overviewMapApplied){ fitNativeOverview(); overviewMapApplied=true; }
        }catch(Exception ignored){ TransivaDiagnostics.error(this,"order","NON_FATAL_EXCEPTION",ignored); }
    }

    private void refreshButtons(){
        if(arrivedPickupBtn == null) return;
        String st = status();

        hideAction(arrivedPickupBtn);
        hideAction(startDeliveryBtn);
        hideAction(arrivedDeliveryBtn);
        hideAction(finishBtn);
        hideAction(updatePriceBtn);
        hideAction(cancelOrderBtn);
        if (DriverOrderCancellationPolicy.canCancel(st)) showAction(cancelOrderBtn, true);
        if(statusBadge != null) statusBadge.setText(statusLabel(st));

        if(st.equals("taken")){
            showAction(arrivedPickupBtn, false);
            float pd = distanceTo(coord("pickup_lat","user_lat"), coord("pickup_lng","user_lng"));
            if(pd >= 0){
                boolean near = pd <= ARRIVE_RADIUS_METER;
                showAction(arrivedPickupBtn, near);
                distanceInfo.setText("📍 Jarak ke penjemputan: " + meter(pd));
                distanceHint.setText(near ? "✓ Anda sudah berada di area penjemputan. Geser kontrol Tiba di Penjemputan ke kanan." : "Menuju penjemputan • kontrol geser akan aktif dalam radius " + (int)ARRIVE_RADIUS_METER + " meter.");
            }else{
                // Jangan menghilangkan aksi ketika GPS belum mendapatkan fix. Driver tetap melihat tahap berikutnya.
                showAction(arrivedPickupBtn, true);
                distanceInfo.setText("📡 GPS belum mendapatkan posisi akurat.");
                distanceHint.setText("Kontrol geser tersedia sebagai konfirmasi manual. Pastikan Anda benar-benar sudah berada di titik penjemputan.");
            }
            return;
        }
        if(st.equals("arrived_pickup")){
            showAction(startDeliveryBtn, true);
            showAction(updatePriceBtn, true);
            distanceInfo.setText("✅ Anda sudah tiba di titik penjemputan.");
            distanceHint.setText(orderKind.equals("pickup") ? "Ambil paket, lalu geser Mulai Antar." : "Pastikan pesanan sudah siap, lalu mulai perjalanan ke pengantaran.");
            return;
        }
        if(st.equals("on_delivery")){
            showAction(arrivedDeliveryBtn, false);
            showAction(updatePriceBtn, true);
            float dd = distanceTo(coord("delivery_lat","destination_lat"), coord("delivery_lng","destination_lng"));
            if(dd >= 0){
                boolean near = dd <= ARRIVE_RADIUS_METER;
                showAction(arrivedDeliveryBtn, near);
                distanceInfo.setText("🏁 Jarak ke pengantaran: " + meter(dd));
                distanceHint.setText(near ? "✓ Anda sudah berada di area pengantaran. Geser kontrol Tiba di Pengantaran ke kanan." : "Menuju pengantaran • kontrol geser akan aktif dalam radius " + (int)ARRIVE_RADIUS_METER + " meter.");
            }else{
                showAction(arrivedDeliveryBtn, true);
                distanceInfo.setText("📡 GPS belum mendapatkan posisi akurat.");
                distanceHint.setText("Kontrol geser tersedia sebagai konfirmasi manual. Pastikan Anda benar-benar sudah sampai di titik pengantaran.");
            }
            return;
        }
        if(st.equals("arrived_delivery")){
            showAction(finishBtn, true);
            showAction(updatePriceBtn, true);
            distanceInfo.setText("🏁 Anda sudah tiba di lokasi pengantaran.");
            distanceHint.setText("Serahkan pesanan ke customer. Setelah diterima, geser Selesaikan Order.");
            return;
        }
        if(st.equals("finished") || st.equals("completed")){
            distanceInfo.setText("✅ Order selesai.");
            distanceHint.setText("Perjalanan telah diselesaikan.");
            return;
        }

        // Fallback aman: status server yang belum dikenali tidak boleh membuat halaman tanpa tombol aksi.
        showAction(arrivedPickupBtn, true);
        distanceInfo.setText("ℹ Status perjalanan: " + statusLabel(st));
        distanceHint.setText("Status server belum dikenali penuh. Gunakan kontrol geser tiba penjemputan bila order memang sedang menuju penjemputan.");
    }

    private void hideAction(View b){
        if(b == null) return;
        b.setVisibility(View.GONE);
        b.setEnabled(false);
        b.setAlpha(1f);
    }

    private void showAction(View b, boolean enabled){
        if(b == null) return;
        b.setVisibility(View.VISIBLE);
        b.setEnabled(enabled && !updatingStatus);
        b.setAlpha(enabled ? 1f : 0.48f);
    }

    private boolean isDeliveryPhase(String st){
        String n = normalizeStatus(st);
        return n.equals("arrived_pickup") || n.equals("on_delivery") || n.equals("arrived_delivery") || n.equals("finished") || n.equals("completed");
    }


    private String pendingFinishOtp = "";

    private void showPickupOtpDialog() {
        if (updatingStatus) return;
        final EditText input = new EditText(this);
        input.setHint("Masukkan 6 digit OTP penerima");
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setPadding(dp(18), dp(8), dp(18), dp(8));
        AlertDialog ad = new AlertDialog.Builder(this)
                .setTitle("Verifikasi OTP TransSend")
                .setMessage("Minta OTP kepada penerima setelah paket benar-benar diterima.")
                .setView(input)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Verifikasi & Selesaikan", null)
                .create();
        ad.setOnShowListener(dialog -> ad.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String otp = input.getText().toString().replaceAll("[^0-9]", "");
            if (otp.length() < 4) { input.setError("OTP belum lengkap"); return; }
            pendingFinishOtp = otp;
            ad.dismiss();
            updateStatus("completed");
        }));
        ad.show();
    }

    private void confirm(String msg, String next){ if(updatingStatus)return; new AlertDialog.Builder(this).setTitle("Konfirmasi").setMessage(msg).setNegativeButton("Batal",null).setPositiveButton("Ya",(d,w)->updateStatus(next)).show(); }
    private void updateStatus(String next){
        if (updatingStatus) return;
        if (!DriverOrderStateGuard.canTransition(status(), next)) {
            info("Status belum sesuai", "Urutan perjalanan harus: diterima → tiba pickup → dalam perjalanan → tiba tujuan → selesai.");
            return;
        }
        updatingStatus = true; setLoading(true);
        DriverNetworkExecutor.execute(() -> { try{
            JSONObject p = new JSONObject();
            p.put("id", internalId());
            p.put("order_id", orderId());
            p.put("driver", driverUsername);
            p.put("driver_username", driverUsername);
            p.put("order_kind", orderKind);
            p.put("source", isPickupOrder() ? "pickup_orders" : "orders");
            p.put("status", next);
            if(isPickupOrder() && (next.equals("finished") || next.equals("completed"))) p.put("otp", pendingFinishOtp);
            String endpoint = endpoint(next);
            JSONObject r = postJson(BASE_URL + endpoint, p);
            boolean ok = r.optBoolean("success", false);
            String m = first(r.optString("message"), ok ? "Status berhasil diperbarui." : "Gagal update status.");
            mainHandler.post(() -> { updatingStatus=false; setLoading(false); if(ok){ pendingFinishOtp = ""; try{ order.put("status", next); }catch(Exception ignored){ TransivaDiagnostics.error(this,"order","NON_FATAL_EXCEPTION",ignored); } saveActiveOrder(); refreshButtons(); mainHandler.postDelayed(() -> updateMap(), 250); info("Berhasil", m); if(next.equals("finished") || next.equals("completed")){ clearActiveOrder(); finish(); } } else info("Gagal", m); });
        }catch(Exception e){
            final String errorMessage = e.getMessage() == null ? "" : e.getMessage().trim();
            mainHandler.post(() -> {
                updatingStatus=false;
                setLoading(false);

                String lower = errorMessage.toLowerCase(Locale.US);
                boolean waitingCustomer =
                        lower.contains("tunggu customer") ||
                        lower.contains("menunggu customer") ||
                        lower.contains("customer menekan terima pesanan") ||
                        lower.contains("customer_received") ||
                        lower.contains("konfirmasi customer");

                if(waitingCustomer){
                    info(
                        "Menunggu konfirmasi customer",
                        "Customer belum mengonfirmasi bahwa pesanan sudah diterima. "
                        + "Order tetap aktif dan tidak akan diselesaikan sampai customer melakukan konfirmasi."
                    );
                    return;
                }

                info(
                    "Koneksi gagal",
                    errorMessage.length() > 0
                        ? errorMessage
                        : "Tidak bisa update status ke server."
                );
            });
        }});
    }
    private String endpoint(String n){
        // pickup_orders memakai endpoint unified karena endpoint lama hanya membaca tabel orders.
        if(isPickupOrder()) return "driver_update_unified_status.php";
        if(n.equals("arrived_pickup"))return "driverArrivedPickup.php";
        if(n.equals("on_delivery"))return "driverStartDelivery.php";
        if(n.equals("arrived_delivery"))return "driverArrivedDelivery.php";
        if(n.equals("finished")||n.equals("completed"))return "finishOrder.php";
        return "driver_update_unified_status.php";
    }
    private boolean isPickupOrder(){
        String source = first(order == null ? "" : order.optString("source"),
                order == null ? "" : order.optString("source_table"), orderKind).toLowerCase(Locale.US);
        return source.equals("pickup_orders") || source.contains("pickup");
    }

    private void updateSpeedMetrics(Location loc){
        if(loc==null) return;
        double instant=0d;
        if(loc.hasSpeed() && loc.getSpeed()>=0f){
            instant=loc.getSpeed()*3.6d;
        }else if(lastSpeedLocation!=null){
            long dt=loc.getTime()-lastSpeedLocation.getTime();
            if(dt>300L && dt<15000L){
                instant=(lastSpeedLocation.distanceTo(loc)/(dt/1000d))*3.6d;
            }
        }
        if(!Double.isFinite(instant) || instant<0d) instant=0d;
        if(instant>180d) instant=180d;
        currentSpeedKmh=currentSpeedKmh<=0d?instant:(currentSpeedKmh*0.62d+instant*0.38d);
        if(currentSpeedKmh>=1d){
            speedSampleSum+=currentSpeedKmh;
            speedSampleCount++;
            averageSpeedKmh=speedSampleSum/Math.max(1L,speedSampleCount);
        }
        lastSpeedLocation=new Location(loc);
    }

    private void pushSpeedToMap(){
        // Speed tetap dihitung untuk UI/navigasi; Google Maps SDK tidak memerlukan JavaScript bridge.
    }

    // Upload lokasi hanya dilakukan oleh LocationService. Activity ini hanya
    // memakai fix GPS untuk UI, jarak, tombol kedatangan dan navigasi.

    private boolean isNonCash(){
        String p = first(order == null ? "" : order.optString("payment_method"), "cash").toLowerCase(Locale.US);
        return p.equals("balance") || p.contains("transpay") || p.contains("transiva_pay") || p.equals("wallet") || p.equals("saldo");
    }
    private Button dangerOutlineButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(Color.parseColor("#DC2626"));
        button.setBackground(stroke("#FFF7F7", "#EF4444", dp(15), 1));
        return button;
    }





    private void showUpdatePriceDialog(){
        if(order == null) return;
        final EditText input = new EditText(this); input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); input.setHint("Total baru"); input.setText(String.valueOf((long)optDouble("price","fare","total")));
        final EditText reason = new EditText(this); reason.setHint("Alasan perubahan, contoh: 1 menu habis");
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(20),dp(4),dp(20),0); box.addView(input); box.addView(reason);
        new AlertDialog.Builder(this).setTitle("Update Total Dibayar").setMessage("Harga turun langsung berlaku. Harga naik wajib disetujui customer.").setView(box).setNegativeButton("Batal",null).setPositiveButton("Kirim",(d,w)->{
            try{
                double value=Double.parseDouble(input.getText().toString().trim()); String why=reason.getText().toString().trim();
                if(value<=0||why.isEmpty()){ info("Harga","Harga dan alasan wajib diisi."); return; }
                requestPriceChange(value,why);
            }catch(Exception e){ info("Harga","Nominal tidak valid."); }
        }).show();
    }
    private void requestPriceChange(double value,String reason){
        setLoading(true); DriverNetworkExecutor.execute(()->{ try{
            JSONObject p=new JSONObject(); p.put("source",isPickupOrder()?"pickup_orders":"orders"); p.put("id",Integer.parseInt(internalId())); p.put("driver",driverUsername); p.put("new_price",value); p.put("reason",reason);
            JSONObject r=postJson(BASE_URL+"driver_request_price_change.php",p); boolean ok=r.optBoolean("success",false); String m=first(r.optString("message"),ok?"Harga diperbarui":"Gagal memperbarui harga");
            mainHandler.post(()->{setLoading(false); if(ok){ try{ if(!r.optBoolean("approval_required",false)) order.put("price",value); }catch(Exception ignored){ TransivaDiagnostics.error(this,"order","NON_FATAL_EXCEPTION",ignored); } info("Total Pembayaran",m); renderOrder(); refreshButtons(); } else info("Gagal",m);});
        }catch(Exception e){mainHandler.post(()->{setLoading(false);info("Gagal","Koneksi server bermasalah.");});}});
    }

    private boolean isFoodOrder(){
        if(order == null) return false;
        String type = first(order.optString("order_type"), order.optString("service_name"), "").toLowerCase(Locale.US);
        return type.contains("food") || type.contains("transfood");
    }



    // Package-private controller bridge: business state remains owned by DriverTripActivity.
    String tripOrderId(){ return orderId(); }
    String tripInternalId(){ return internalId(); }
    String tripStatus(){ return status(); }
    String tripStatusLabel(String value){ return statusLabel(value); }
    String tripSource(){ return first(order.optString("source"), order.optString("_transiva_table"), isPickupOrder()?"pickup_orders":"orders"); }
    String tripPickupAddress(){ return pickupAddress(); }
    boolean tripIsPickupOrder(){ return isPickupOrder(); }
    boolean tripIsFoodOrder(){ return isFoodOrder(); }
    double tripCoord(String a,String b){ return coord(a,b); }
    boolean tripValid(double lat,double lng){ return valid(lat,lng); }
    void tripFitNativeOverview(){ fitNativeOverview(); }
    int tripDp(int value){ return dp(value); }
    void tripSetLoading(boolean value){ setLoading(value); }
    void tripClearActiveOrder(){ clearActiveOrder(); }
    void tripInfo(String title,String message){ info(title,message); }

    private JSONObject postJson(String urlText, JSONObject payload) throws Exception {
        if (api == null) api = new DriverApiClient(session);
        String endpoint = urlText == null ? "" : urlText.trim();
        if (endpoint.startsWith(BASE_URL)) endpoint = endpoint.substring(BASE_URL.length());
        if (endpoint.startsWith("/")) endpoint = endpoint.substring(1);
        DriverApiClient.Result result = api.post(endpoint, payload == null ? new JSONObject() : payload);
        return result.body;
    }

    private void saveActiveOrder(){ if(order==null)return; getSharedPreferences(PREF_NAME,MODE_PRIVATE).edit().putString("driver_active_order_json", order.toString()).putString("driver_active_order_id", orderId()).putString("driver_active_order_kind", orderKind).putString("driver_active_order_status", status()).putString("driver_active_pickup_address", pickupAddress()).putString("driver_active_delivery_address", deliveryAddress()).putString("driver_active_pickup_lat", String.valueOf(coord("pickup_lat","user_lat"))).putString("driver_active_pickup_lng", String.valueOf(coord("pickup_lng","user_lng"))).putString("driver_active_delivery_lat", String.valueOf(coord("delivery_lat","destination_lat"))).putString("driver_active_delivery_lng", String.valueOf(coord("delivery_lng","destination_lng"))).putString("driver_active_price", String.valueOf(optDouble("price","fare","total"))).putString("driver_type", resolveDriverTypeFromOrder()).putString("active_driver_type", resolveDriverTypeFromOrder()).apply(); }
    private void clearActiveOrder(){ getSharedPreferences(PREF_NAME,MODE_PRIVATE).edit().remove("driver_active_order_json").remove("driver_active_order_id").remove("driver_active_order_kind").remove("driver_active_order_status").apply(); }
    private String orderId(){ return first(order.optString("order_id"), order.optString("id"), "-"); } private String internalId(){ return first(order.optString("id"), order.optString("order_id"), ""); } private String status(){ return normalizeStatus(first(order.optString("status"), "taken")); }
    private String normalizeStatus(String raw){
        String s = first(raw, "taken").toLowerCase(Locale.US).trim().replace('-', '_').replace(' ', '_');
        if(s.equals("accepted") || s.equals("driver_accepted") || s.equals("driver_assigned") || s.equals("assigned") || s.equals("merchant_accepted") || s.equals("processing") || s.equals("confirmed")) return "taken";
        if(s.equals("arrived") || s.equals("at_pickup") || s.equals("pickup_arrived") || s.equals("arrive_pickup")) return "arrived_pickup";
        if(s.equals("picked_up") || s.equals("pickedup") || s.equals("start_delivery") || s.equals("delivering") || s.equals("in_delivery") || s.equals("otw_delivery")) return "on_delivery";
        if(s.equals("at_delivery") || s.equals("delivery_arrived") || s.equals("arrive_delivery")) return "arrived_delivery";
        if(s.equals("finish") || s.equals("done") || s.equals("success")) return "finished";
        return s;
    }
    private String pickupAddress(){ return first(order.optString("pickup_address"), order.optString("pickup"), order.optString("sender_address"), "-"); } private String deliveryAddress(){ return first(order.optString("delivery_address"), order.optString("destination_address"), order.optString("destination"), order.optString("receiver_address"), "-"); }
    private String cleanServiceLabel(){ String s=first(order.optString("service_name"), order.optString("order_type"), orderKind.equals("pickup") ? "TransPickup" : "Food Delivery"); return s.trim(); }
    private double coord(String a, String b){ try{return Double.parseDouble(first(order.optString(a), order.optString(b), "0"));}catch(Exception e){return 0;} } private double optDouble(String... keys){ for(String k: keys){ try{ if(order.has(k)) return Double.parseDouble(order.optString(k,"0")); }catch(Exception ignored){ TransivaDiagnostics.error(this,"order","NON_FATAL_EXCEPTION",ignored); } } return 0; }
    private String statusLabel(String s){ if(s.equals("taken"))return "Menuju Penjemputan"; if(s.equals("arrived_pickup"))return "Tiba di Penjemputan"; if(s.equals("on_delivery"))return "Menuju Delivery"; if(s.equals("arrived_delivery"))return "Tiba Delivery"; if(s.equals("merchant_accepted"))return "Diterima Merchant"; if(s.equals("finished")||s.equals("completed"))return "Selesai"; return first(s,"Menuju Penjemputan"); }
    private float distanceTo(double lat, double lng){ if(!valid(lastDriverLat,lastDriverLng)||!valid(lat,lng))return -1; float[] r=new float[1]; Location.distanceBetween(lastDriverLat,lastDriverLng,lat,lng,r); return r[0]; }
    private boolean valid(double lat,double lng){ return lat!=0 && lng!=0 && !Double.isNaN(lat) && !Double.isNaN(lng); } private String meter(float m){ return m>=1000 ? one(m/1000.0)+" km" : Math.round(m)+" meter"; }
    private String rupiah(double v){ return "Rp " + NumberFormat.getNumberInstance(new Locale("id","ID")).format((long)v); } private String one(double v){ return String.format(Locale.US,"%.1f",v); } private String zero(double v){ return String.format(Locale.US,"%.0f",v); } private String pref(String key){ try{return getSharedPreferences(PREF_NAME,MODE_PRIVATE).getString(key,"");}catch(Exception e){return "";} }
    private String first(String... values){ if(values==null)return ""; for(String s: values) if(s!=null && s.trim().length()>0 && !"null".equalsIgnoreCase(s.trim())) return s.trim(); return ""; }
    private String drawableDataUri(String... names){
        try{
            for(String name: names){
                int id = getResources().getIdentifier(name, "drawable", getPackageName());
                if(id <= 0) continue;
                Bitmap bm = BitmapFactory.decodeResource(getResources(), id);
                if(bm == null) continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                bm.compress(Bitmap.CompressFormat.PNG, 100, out);
                String b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
                try{ bm.recycle(); }catch(Exception ignored){ TransivaDiagnostics.error(this,"order","NON_FATAL_EXCEPTION",ignored); }
                return "data:image/png;base64," + b64;
            }
        }catch(Exception ignored){ TransivaDiagnostics.error(this,"order","NON_FATAL_EXCEPTION",ignored); }
        return "";
    }

    private int dp(int v){ return (int)(v * getResources().getDisplayMetrics().density + .5f); } private void add(View v,int l,int t,int r,int b){ LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.setMargins(l,t,r,b); root.addView(v,lp); }
    private LinearLayout.LayoutParams btnLp(int top){ LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(52)); lp.setMargins(0, dp(top), 0, 0); return lp; }
    private LinearLayout card(){ LinearLayout v=new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setBackground(stroke("#FFFFFF", "#D7E6F8", dp(24), 1)); v.setElevation(dp(2)); return v; }
    private TextView text(String s,int sp,String color,boolean bold){ TextView t=new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(Color.parseColor(color)); if(bold)t.setTypeface(Typeface.DEFAULT_BOLD); return t; }
    private Button primary(String s){ Button b=new Button(this); b.setText(s); b.setAllCaps(false); b.setTextColor(Color.WHITE); b.setTextSize(14); b.setTypeface(Typeface.DEFAULT_BOLD); b.setBackground(gradient("#086BFF", "#2EA2FF", dp(18))); return b; } private Button green(String s){ Button b=primary(s); b.setBackground(gradient("#10B981", "#059669", dp(18))); return b; } private Button outline(String s){ Button b=new Button(this); b.setText(s); b.setAllCaps(false); b.setTextColor(Color.parseColor("#0B7CFF")); b.setTextSize(14); b.setTypeface(Typeface.DEFAULT_BOLD); b.setBackground(stroke("#FFFFFF", "#9DCAFF", dp(18), 1)); return b; }
    private GradientDrawable round(String color,int radius){ GradientDrawable g=new GradientDrawable(); g.setColor(Color.parseColor(color)); g.setCornerRadius(radius); return g; } private GradientDrawable stroke(String color,String st,int radius,int sw){ GradientDrawable g=round(color,radius); g.setStroke(dp(sw), Color.parseColor(st)); return g; } private GradientDrawable gradient(String c1,String c2,int radius){ GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{Color.parseColor(c1), Color.parseColor(c2)}); g.setCornerRadius(radius); return g; }
    private void setLoading(boolean b){ if(progressBar!=null) progressBar.setVisibility(b?View.VISIBLE:View.GONE); } private void info(String t,String m){ try{ new AlertDialog.Builder(this).setTitle(t).setMessage(m).setPositiveButton("OK", null).show(); }catch(Exception ignored){ TransivaDiagnostics.error(this,"order","NON_FATAL_EXCEPTION",ignored); } }
}
