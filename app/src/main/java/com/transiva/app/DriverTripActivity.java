package com.transiva.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
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
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

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
    private static final long LOCATION_POST_INTERVAL_MS = 5000L;
    private static final float MAP_ANIMATION_MIN_DISTANCE_METER = 5.0f;
    private static final long GPS_PRIORITY_MS = 8000L;
    private static final long MAX_LOCATION_AGE_MS = 30000L;
    private static final long OUT_OF_ORDER_TOLERANCE_MS = 1500L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private LinearLayout root;
    private ProgressBar progressBar;
    private TextView statusBadge, distanceInfo, distanceHint;
    private WebView mapView;
    private Button arrivedPickupBtn, startDeliveryBtn, arrivedDeliveryBtn, finishBtn;
    private JSONObject order;
    private String driverUsername = "";
    private String driverType = "motor";
    private String orderKind = "order";
    private LocationManager locationManager;
    private LocationListener locationListener;
    private double lastDriverLat = 0, lastDriverLng = 0;
    private double renderedDriverLat = 0, renderedDriverLng = 0;
    private double prevDriverLat = 0, prevDriverLng = 0;
    private boolean updatingStatus = false;
    private boolean mapReady = false;
    private boolean locationWatchRunning = false;
    private long lastLocationPostAt = 0L;
    private double lastPostedLat = 0, lastPostedLng = 0;
    private Location lastAcceptedLocation = null;
    private long lastAcceptedAt = 0L;
    private long lastGpsFixAt = 0L;
    private SessionManager session;
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

    private final Runnable locationPostRunnable = new Runnable(){
        @Override public void run(){
            if(order != null && locationWatchRunning && valid(lastDriverLat, lastDriverLng)){
                postDriverLocation(lastDriverLat, lastDriverLng, false);
            }
            if(locationWatchRunning) mainHandler.postDelayed(this, LOCATION_POST_INTERVAL_MS);
        }
    };

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        try{
            getWindow().setStatusBarColor(Color.WHITE);
            getWindow().setNavigationBarColor(Color.WHITE);
            if(Build.VERSION.SDK_INT >= 23) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }catch(Exception ignored){}
        session = new SessionManager(this);
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
    @Override protected void onResume(){ super.onResume(); if(order != null) startLocationWatch(); }
    @Override protected void onPause(){ stopLocationWatch(); super.onPause(); }
    @Override protected void onDestroy(){ stopLocationWatch(); try{ if(mapView != null) mapView.destroy(); }catch(Exception ignored){} super.onDestroy(); }

    private void loadSession(){
        try{
            SessionManager s = new SessionManager(this);
            driverUsername = first(s.getUsername(), s.getName(), "");
            driverType = normalizeDriverType(first(s.getDriverType(), s.getRole(), ""));
        }catch(Exception ignored){}
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
        }catch(Exception ignored){}
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
                }catch(Exception ignored){}
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
        addLocationCard("📍 Lokasi Pickup", pickupAddress(), true);
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
        distanceInfo = text("📡 Mengukur jarak driver...", 13, "#64748B", false); distanceInfo.setPadding(0, dp(10),0,0); h.addView(distanceInfo);
        distanceHint = text("", 13, "#059669", true); distanceHint.setPadding(dp(12), dp(9), dp(12), dp(9)); distanceHint.setBackground(stroke("#ECFDF5", "#86EFAC", dp(14), 1)); LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1,-2); hp.setMargins(0, dp(8),0,0); h.addView(distanceHint, hp);
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
        c.addView(text("🗺️ Peta Perjalanan", 16, "#0B3A78", true)); c.addView(text("Ikon driver mengikuti mode order dan dikunci di dalam jalur rute.", 12, "#64748B", false));
        mapView = new WebView(this); try{ WebSettings st = mapView.getSettings(); st.setJavaScriptEnabled(true); st.setDomStorageEnabled(true); st.setLoadWithOverviewMode(true); st.setUseWideViewPort(true); st.setAllowFileAccess(true); st.setAllowContentAccess(true); st.setCacheMode(WebSettings.LOAD_NO_CACHE); if(Build.VERSION.SDK_INT >= 21) st.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW); mapView.setBackgroundColor(Color.TRANSPARENT); mapView.setWebChromeClient(new WebChromeClient()); }catch(Exception ignored){}
        mapView.setWebViewClient(new WebViewClient(){
            @Override public void onPageFinished(WebView view, String url){
                mapReady = true;
                mainHandler.postDelayed(() -> {
                    applyPendingRoute();
                    updateMap();
                    if(pendingRoutePoints.isEmpty()) requestStableRoute(true);
                }, 120);
            }
        });
        mapView.loadDataWithBaseURL("https://transiva.my.id/", mapHtml(), "text/html", "UTF-8", null); LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, dp(230)); mp.setMargins(0,dp(8),0,0); c.addView(mapView, mp); add(c,0,0,0,dp(12));
    }
    private String mapHtml(){
        double pLat = coord("pickup_lat","user_lat"), pLng = coord("pickup_lng","user_lng"), dLat = coord("delivery_lat","destination_lat"), dLng = coord("delivery_lng","destination_lng");
        double cLat = valid(pLat,pLng) ? pLat : (valid(dLat,dLng) ? dLat : -0.9), cLng = valid(pLat,pLng) ? pLng : (valid(dLat,dLng) ? dLng : 119.87);
        String mode = routeTargetMode();
        String vehicle = resolveDriverTypeFromOrder();
        String carIcon = drawableDataUri("map_car_top", "ic_car_top", "car_top", "ic_transcar", "transcar", "car", "transcar_marker");
        String bikeIcon = drawableDataUri("map_motor_top", "ic_motor_top", "motor_top", "ic_transbike", "transbike", "motor", "bike_marker");
        String pickupPin = drawableDataUri("map_pickup_pin", "ic_pickup_pin", "pickup_pin", "pickup_marker");
        String destPin = drawableDataUri("map_destination_pin", "map_delivery_pin", "ic_destination_pin", "destination_pin", "delivery_marker");

        return "<!doctype html><html><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no'>"+
                "<link rel='stylesheet' href='"+LEAFLET_CSS+"?v=trip_overview_1'>"+
                "<script src='"+LEAFLET_JS+"?v=trip_overview_1'></script>"+
                "<style>html,body,#map{height:100%;width:100%;margin:0;background:#EAF4FF;overflow:hidden}.leaflet-container{font-family:Arial,sans-serif;background:#EAF4FF;border-radius:18px}.leaflet-control-attribution{display:none!important}.leaflet-tile-pane{filter:saturate(.92) contrast(.98) brightness(1.03)}.pinImg{width:48px;height:48px;object-fit:contain;filter:drop-shadow(0 5px 8px rgba(15,23,42,.28))}.fallbackPin{width:42px;height:42px;border-radius:22px;display:flex;align-items:center;justify-content:center;font-size:22px;color:#fff;border:3px solid #fff;box-shadow:0 5px 14px rgba(15,23,42,.24)}.vehicle{width:48px;height:48px;object-fit:contain;filter:drop-shadow(0 5px 6px rgba(0,0,0,.38));transform-origin:center center}.vehicleFallback{width:46px;height:46px;border-radius:23px;background:#fff;display:flex;align-items:center;justify-content:center;font-size:27px;filter:drop-shadow(0 5px 6px rgba(0,0,0,.38));transform-origin:center center}.routeBadge{position:absolute;z-index:999;top:10px;left:10px;right:10px;background:rgba(255,255,255,.96);border:1px solid #D7E6F8;border-radius:14px;padding:9px 11px;color:#0B3A78;font-size:12px;font-weight:700;box-shadow:0 7px 18px rgba(15,23,42,.12)}</style></head><body><div id='map'></div><div id='badge' class='routeBadge'>Menyiapkan seluruh rute...</div><script>"+
                "var pickup=["+pLat+","+pLng+"],dest=["+dLat+","+dLng+"],targetMode='"+mode+"',vehicleType='"+vehicle+"';"+
                "var bikeIconData='"+bikeIcon+"',carIconData='"+carIcon+"',pickupPinData='"+pickupPin+"',destPinData='"+destPin+"';"+
                "var map=null,pickupMarker=null,destMarker=null,driverMarker=null,legPickup=null,legDelivery=null,pickupPts=[],deliveryPts=[],activePts=[],animId=null,lastDeg=0,driverRaw=null,routeProgress=0,lastFitKey='';"+
                "function valid(a,b){a=+a;b=+b;return isFinite(a)&&isFinite(b)&&a!==0&&b!==0;}function setBadge(t){var e=document.getElementById('badge');if(e)e.innerHTML=t;}"+
                "function pinIcon(type){var data=type==='pickup'?pickupPinData:destPinData;if(data&&data.length>20)return L.divIcon({html:'<img class=pinImg src='+data+'>',className:'',iconSize:[48,48],iconAnchor:[24,44]});return L.divIcon({html:'<div class=fallbackPin style=background:'+(type==='pickup'?'#ef4444':'#16a34a')+'>'+(type==='pickup'?'●':'⚑')+'</div>',className:'',iconSize:[44,44],iconAnchor:[22,22]});}"+
                "function vehicleIcon(type,deg){type=type==='car'?'car':'motor';var data=type==='car'?carIconData:bikeIconData;if(data&&data.length>20)return L.divIcon({html:'<img class=vehicle src='+data+' style=\"transform:rotate('+(+deg||0)+'deg)\">',className:'',iconSize:[50,50],iconAnchor:[25,25]});return L.divIcon({html:'<div class=vehicleFallback style=\"transform:rotate('+(+deg||0)+'deg)\">'+(type==='car'?'🚘':'🏍️')+'</div>',className:'',iconSize:[50,50],iconAnchor:[25,25]});}"+
                "function init(){if(typeof L==='undefined'){setTimeout(init,250);return}if(map)return;map=L.map('map',{zoomControl:true,attributionControl:false,preferCanvas:true,zoomAnimation:true,fadeAnimation:false,markerZoomAnimation:false}).setView(["+cLat+","+cLng+"],14);L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:20,crossOrigin:true,updateWhenIdle:true,keepBuffer:4}).addTo(map);if(valid(pickup[0],pickup[1]))pickupMarker=L.marker(pickup,{icon:pinIcon('pickup'),zIndexOffset:600}).addTo(map);if(valid(dest[0],dest[1]))destMarker=L.marker(dest,{icon:pinIcon('delivery'),zIndexOffset:600}).addTo(map);setTimeout(function(){try{map.invalidateSize(false)}catch(e){}},350)}"+
                "function bearingOf(a,b){try{var x1=a[0]*Math.PI/180,x2=b[0]*Math.PI/180,dl=(b[1]-a[1])*Math.PI/180,y=Math.sin(dl)*Math.cos(x2),x=Math.cos(x1)*Math.sin(x2)-Math.sin(x1)*Math.cos(x2)*Math.cos(dl),v=(Math.atan2(y,x)*180/Math.PI)%360;return v<0?v+360:v}catch(e){return null}}"+
                "function selectActive(){activePts=targetMode==='delivery'?deliveryPts:pickupPts;routeProgress=0}"+
                "function snap(lat,lng){if(!activePts||activePts.length<2)return{lat:+lat,lng:+lng,bearing:null};var cos=Math.cos((+lat)*Math.PI/180);if(!isFinite(cos)||Math.abs(cos)<.000001)cos=1;var px=(+lng)*cos,py=+lat,best=1e99,bx=+lng,by=+lat,bi=routeProgress,st=Math.max(0,routeProgress-3),en=Math.min(activePts.length-2,routeProgress+70);for(var i=st;i<=en;i++){var a=activePts[i],b=activePts[i+1],ax=a[1]*cos,ay=a[0],cx=b[1]*cos,cy=b[0],vx=cx-ax,vy=cy-ay,wx=px-ax,wy=py-ay,ll=vx*vx+vy*vy,t=ll?(wx*vx+wy*vy)/ll:0;t=Math.max(0,Math.min(1,t));var qx=ax+vx*t,qy=ay+vy*t,dx=px-qx,dy=py-qy,dd=dx*dx+dy*dy;if(dd<best){best=dd;bx=qx/cos;by=qy;bi=i}}if(Math.sqrt(best)*111320>90)return{lat:+lat,lng:+lng,bearing:null};if(bi>=routeProgress)routeProgress=bi;return{lat:by,lng:bx,bearing:bearingOf(activePts[bi],activePts[Math.min(bi+1,activePts.length-1)])}}"+
                "function rotate(deg){if(!driverMarker)return;var e=driverMarker.getElement();if(!e)return;var i=e.querySelector('.vehicle')||e.querySelector('.vehicleFallback');if(i)i.style.transform='rotate('+(+deg||0)+'deg)'}"+
                "function fitOverview(force){if(!map)return;var key=targetMode+'|'+pickupPts.length+'|'+deliveryPts.length;if(!force&&key===lastFitKey)return;var all=[];if(pickupPts.length)all=all.concat(pickupPts);if(deliveryPts.length)all=all.concat(deliveryPts);if(driverMarker){var d=driverMarker.getLatLng();all.push([d.lat,d.lng])}if(!all.length){if(pickupMarker)all.push(pickup);if(destMarker)all.push(dest)}if(all.length>1){lastFitKey=key;map.fitBounds(L.latLngBounds(all),{paddingTopLeft:[28,58],paddingBottomRight:[28,28],maxZoom:16,animate:false})}}"+
                "function drawLeg(oldLine,pts,color,opacity){if(oldLine){try{map.removeLayer(oldLine)}catch(e){}}if(!pts||pts.length<2)return null;var l=L.polyline(pts,{weight:6,opacity:opacity,color:color,lineCap:'round',lineJoin:'round',smoothFactor:1.2}).addTo(map);try{l.bringToBack()}catch(e){}return l}"+
                "window.applyTripRoutes=function(p1,p2,km1,s1,km2,s2,status,forceFit){try{if(typeof p1==='string')p1=JSON.parse(p1);if(typeof p2==='string')p2=JSON.parse(p2);pickupPts=(p1&&p1.length>1)?p1:[];deliveryPts=(p2&&p2.length>1)?p2:[];targetMode=(status==='arrived_pickup'||status==='on_delivery'||status==='arrived_delivery')?'delivery':'pickup';var pickupDone=targetMode==='delivery';legPickup=drawLeg(legPickup,pickupPts,pickupDone?'#94A3B8':'#1683FF',pickupDone ? .58 : .94);legDelivery=drawLeg(legDelivery,deliveryPts,'#16A34A',targetMode==='delivery' ? .96 : .78);selectActive();var total=(+km1||0)+(+km2||0),mins=Math.max(1,Math.round(((+s1||0)+(+s2||0))/60));setBadge((targetMode==='delivery'?'Menuju delivery':'Menuju pickup')+' • seluruh rute '+total.toFixed(1)+' km • '+mins+' menit');fitOverview(!!forceFit)}catch(e){}};"+
                "function animateTo(target,deg,type){if(animId)cancelAnimationFrame(animId);var cur=driverMarker.getLatLng(),from=[cur.lat,cur.lng],to=[target.lat,target.lng],start=0;driverMarker.setIcon(vehicleIcon(type,deg));function step(ts){if(!start)start=ts;var t=Math.min(1,(ts-start)/1100),q=snap(from[0]+(to[0]-from[0])*t,from[1]+(to[1]-from[1])*t),bd=q.bearing==null?deg:q.bearing;driverMarker.setLatLng([q.lat,q.lng]);rotate(bd);if(t<1)animId=requestAnimationFrame(step);else animId=null}animId=requestAnimationFrame(step)}"+
                "window.updateDrv=function(lat,lng,deg,type){if(!map||!valid(lat,lng))return;driverRaw={lat:+lat,lng:+lng};type=type==='car'?'car':'motor';var q=snap(lat,lng),bd=q.bearing==null?(+deg||lastDeg):q.bearing;lastDeg=bd;var t=L.latLng(q.lat,q.lng);if(!driverMarker)driverMarker=L.marker(t,{icon:vehicleIcon(type,bd),zIndexOffset:9999}).addTo(map);else animateTo(t,bd,type)};"+
                "window.setVehicleType=function(t){vehicleType=t==='car'?'car':'motor';if(driverMarker)driverMarker.setIcon(vehicleIcon(vehicleType,lastDeg))};window.setTargetMode=function(m){targetMode=m==='delivery'?'delivery':'pickup';selectActive()};window.focusTarget=function(m){targetMode=m==='delivery'?'delivery':'pickup';selectActive();fitOverview(true)};window.setSpeed=function(){};init();setTimeout(init,800);"+
                "</script></body></html>";
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
        String customerNote = first(food.optString("customer_note"), food.optString("note_customer"), "");
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
    private void addPlainNoteCard(){
        String note = first(order.optString("note"), order.optString("item_note"), order.optString("description"), "-");
        LinearLayout c = card(); c.setPadding(dp(16), dp(13), dp(16), dp(13)); c.addView(text(orderKind.equals("pickup") ? "📦 Detail Paket" : "📝 Catatan Customer", 16, "#0B3A78", true));
        TextView n = text(note, 14, "#111827", false); n.setPadding(0, dp(6),0,0); c.addView(n); add(c,0,0,0,dp(12));
    }
    private void rowText(LinearLayout p, String l, String v){
        LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(0, dp(7),0,dp(7));
        r.addView(text(l, 14, "#64748B", false), new LinearLayout.LayoutParams(0,-2,1)); r.addView(text(v, 14, "#111827", true)); p.addView(r);
    }
    private void addActions(){
        LinearLayout c = card(); c.setPadding(dp(16), dp(16), dp(16), dp(16));
        c.addView(text("⚡ Aksi Perjalanan", 18, "#0B3A78", true));
        TextView guide = text("Tombol hijau menunjukkan aksi berikutnya. Aksi tiba tetap terlihat dan akan aktif otomatis saat driver mendekati titik tujuan.", 12, "#64748B", false);
        guide.setPadding(0, dp(5), 0, dp(2)); c.addView(guide);

        arrivedPickupBtn = green("📍 Tiba di Lokasi Pickup"); arrivedPickupBtn.setOnClickListener(v -> confirm("Konfirmasi bahwa Anda sudah tiba di lokasi pickup?", "arrived_pickup")); c.addView(arrivedPickupBtn, btnLp(10));
        startDeliveryBtn = green(orderKind.equals("pickup") ? "📦 Paket Sudah Diambil • Mulai Antar" : vehicleEmoji() + " Mulai Perjalanan ke Tujuan"); startDeliveryBtn.setOnClickListener(v -> confirm("Pesanan sudah siap dan mulai perjalanan ke lokasi pengantaran?", "on_delivery")); c.addView(startDeliveryBtn, btnLp(10));
        arrivedDeliveryBtn = green("🏁 Tiba di Lokasi Pengantaran"); arrivedDeliveryBtn.setOnClickListener(v -> confirm("Konfirmasi bahwa Anda sudah tiba di lokasi pengantaran?", "arrived_delivery")); c.addView(arrivedDeliveryBtn, btnLp(10));
        finishBtn = green("✅ Pesanan Diterima • Selesaikan Order"); finishBtn.setOnClickListener(v -> { if (isPickupOrder()) showPickupOtpDialog(); else confirm("Pastikan pesanan sudah diterima customer. Selesaikan order sekarang?", "finished"); }); c.addView(finishBtn, btnLp(10));

        LinearLayout quick = new LinearLayout(this); quick.setOrientation(LinearLayout.HORIZONTAL);
        Button chat = primary("💬 Chat"); chat.setOnClickListener(v -> openChat()); quick.addView(chat, new LinearLayout.LayoutParams(0, dp(50), 1));
        Button nav = outline("➤ Navigasi"); nav.setOnClickListener(v -> openNativeNavigation(!isDeliveryPhase(status())));
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, dp(50), 1); np.setMargins(dp(8),0,0,0); quick.addView(nav, np);
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(-1,-2); qp.setMargins(0,dp(10),0,0); c.addView(quick, qp);
        add(c,0,0,0,dp(12));
    }
    private void startLocationWatch(){
        if(order == null) return;
        if(Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED){ requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 701); return; }
        try{
            locationManager = (LocationManager)getSystemService(LOCATION_SERVICE); if(locationManager == null) return; stopLocationWatch();
            locationListener = new LocationListener(){ @Override public void onLocationChanged(Location l){ if(l==null)return; onDriverLocationChanged(l); } @Override public void onStatusChanged(String p,int s,Bundle e){} @Override public void onProviderEnabled(String p){} @Override public void onProviderDisabled(String p){} };
            if(!valid(lastDriverLat, lastDriverLng) && lastAcceptedLocation == null){
                Location last = getBestLastKnownLocation();
                if(last != null && isFreshEnough(last)) onDriverLocationChanged(last);
            }
            try{ locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 900, 0, locationListener, Looper.getMainLooper()); }catch(Exception ignored){}
            try{ locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1400, 0, locationListener, Looper.getMainLooper()); }catch(Exception ignored){}
            locationWatchRunning = true;
            mainHandler.removeCallbacks(locationPostRunnable);
            mainHandler.post(locationPostRunnable);
        }catch(Exception ignored){}
    }
    private void stopLocationWatch(){ try{ if(locationManager != null && locationListener != null) locationManager.removeUpdates(locationListener); }catch(Exception ignored){} locationWatchRunning = false; mainHandler.removeCallbacks(locationPostRunnable); locationListener = null; }
    private Location getBestLastKnownLocation(){
        Location gps = null, net = null;
        try{ if(locationManager != null) gps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER); }catch(Exception ignored){}
        try{ if(locationManager != null) net = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER); }catch(Exception ignored){}
        if(gps == null) return net;
        if(net == null) return gps;
        if(!isFreshEnough(net)) return gps;
        if(!isFreshEnough(gps)) return net;
        float ga = gps.hasAccuracy() ? gps.getAccuracy() : 9999f;
        float na = net.hasAccuracy() ? net.getAccuracy() : 9999f;
        return ga <= na ? gps : net;
    }

    private boolean isFreshEnough(Location l){
        if(l == null) return false;
        if(Build.VERSION.SDK_INT >= 17){
            long age = android.os.SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos();
            return age <= MAX_LOCATION_AGE_MS * 1000000L;
        }
        return System.currentTimeMillis() - l.getTime() <= MAX_LOCATION_AGE_MS;
    }

    private boolean shouldAcceptLocation(Location l){
        if(l == null) return false;
        if(!valid(l.getLatitude(), l.getLongitude())) return false;
        long now = System.currentTimeMillis();
        if(LocationManager.NETWORK_PROVIDER.equals(l.getProvider()) && now - lastGpsFixAt < GPS_PRIORITY_MS) return false;

        /*
         * FIX MARKER DIAM SAAT LOKASI DIGESER:
         * Versi sebelumnya terlalu ketat menolak lokasi dengan akurasi kasar,
         * loncatan jauh, atau provider tertentu. Saat testing pakai mock/geser lokasi,
         * koordinat baru sering ditolak sehingga motor diam.
         *
         * Yang perlu ditolak hanya data lama yang datang terlambat dari provider lain,
         * karena inilah penyebab marker maju lalu balik ke titik awal.
         */
        if(lastAcceptedLocation != null){
            long newTime = l.getTime();
            long oldTime = lastAcceptedLocation.getTime();
            if(newTime > 0 && oldTime > 0 && newTime + OUT_OF_ORDER_TOLERANCE_MS < oldTime){
                return false;
            }
        }

        return true;
    }

    private void onDriverLocationChanged(Location l){
        SmoothLocationEngine.Fix fix = smoothLocation.offer(l);
        if(fix == null) return;

        Location accepted = fix.location;
        double newLat = accepted.getLatitude();
        double newLng = accepted.getLongitude();
        lastAcceptedLocation = new Location(accepted);
        lastAcceptedAt = System.currentTimeMillis();
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
        if(fix.upload) postDriverLocation(newLat, newLng, false);
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
            if(mapView == null || Build.VERSION.SDK_INT < 19 || !mapReady || !valid(lastDriverLat, lastDriverLng)) return;
            double deg = 0;
            if(valid(prevDriverLat, prevDriverLng)) deg = bearing(prevDriverLat, prevDriverLng, lastDriverLat, lastDriverLng);
            driverType = resolveDriverTypeFromOrder();
            String js = "if(window.setVehicleType)setVehicleType('" + driverType + "');if(window.setTargetMode)setTargetMode('" + routeTargetMode() + "');if(window.updateDrv)updateDrv(" + lastDriverLat + "," + lastDriverLng + "," + deg + ",'" + driverType + "');";
            mapView.evaluateJavascript(js, null);
            requestStableRoute(false);
            prevDriverLat = lastDriverLat;
            prevDriverLng = lastDriverLng;
        }catch(Exception ignored){}
    }
    private void requestStableRoute(boolean force){
        if(mapView == null || !valid(lastDriverLat,lastDriverLng)) return;
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
        new Thread(() -> {
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
        },"transiva-trip-overview-route").start();
    }

    private void applyPendingRoute(){
        if(mapView==null || !mapReady || Build.VERSION.SDK_INT<19) return;
        if(pendingPickupRoutePoints==null || pendingPickupRoutePoints.length()<4) return;
        if(pendingDeliveryRoutePoints==null || pendingDeliveryRoutePoints.length()<4) return;
        try{
            boolean forceFit=!overviewMapApplied;
            String js="if(window.applyTripRoutes)applyTripRoutes("+
                    JSONObject.quote(pendingPickupRoutePoints)+","+
                    JSONObject.quote(pendingDeliveryRoutePoints)+","+
                    pendingPickupRouteKm+","+pendingPickupRouteSeconds+","+
                    pendingDeliveryRouteKm+","+pendingDeliveryRouteSeconds+","+
                    JSONObject.quote(status())+","+forceFit+");";
            mapView.evaluateJavascript(js, null);
            overviewMapApplied=true;
        }catch(Exception ignored){}
    }

    private void refreshButtons(){
        if(arrivedPickupBtn == null) return;
        String st = status();

        hideAction(arrivedPickupBtn);
        hideAction(startDeliveryBtn);
        hideAction(arrivedDeliveryBtn);
        hideAction(finishBtn);
        if(statusBadge != null) statusBadge.setText(statusLabel(st));

        if(st.equals("taken")){
            showAction(arrivedPickupBtn, false);
            float pd = distanceTo(coord("pickup_lat","user_lat"), coord("pickup_lng","user_lng"));
            if(pd >= 0){
                boolean near = pd <= ARRIVE_RADIUS_METER;
                showAction(arrivedPickupBtn, near);
                distanceInfo.setText("📍 Jarak ke pickup: " + meter(pd));
                distanceHint.setText(near ? "✓ Anda sudah berada di area pickup. Tekan tombol Tiba di Lokasi Pickup." : "Menuju pickup • tombol akan aktif dalam radius " + (int)ARRIVE_RADIUS_METER + " meter.");
            }else{
                // Jangan menghilangkan aksi ketika GPS belum mendapatkan fix. Driver tetap melihat tahap berikutnya.
                showAction(arrivedPickupBtn, true);
                distanceInfo.setText("📡 GPS belum mendapatkan posisi akurat.");
                distanceHint.setText("Tombol tiba tersedia sebagai konfirmasi manual. Pastikan Anda benar-benar sudah berada di lokasi pickup.");
            }
            return;
        }
        if(st.equals("arrived_pickup")){
            showAction(startDeliveryBtn, true);
            distanceInfo.setText("✅ Anda sudah tiba di lokasi pickup.");
            distanceHint.setText(orderKind.equals("pickup") ? "Ambil paket, lalu tekan Mulai Antar." : "Pastikan pesanan sudah siap, lalu mulai perjalanan ke tujuan.");
            return;
        }
        if(st.equals("on_delivery")){
            showAction(arrivedDeliveryBtn, false);
            float dd = distanceTo(coord("delivery_lat","destination_lat"), coord("delivery_lng","destination_lng"));
            if(dd >= 0){
                boolean near = dd <= ARRIVE_RADIUS_METER;
                showAction(arrivedDeliveryBtn, near);
                distanceInfo.setText("🏁 Jarak ke pengantaran: " + meter(dd));
                distanceHint.setText(near ? "✓ Anda sudah berada di area pengantaran. Tekan tombol Tiba di Lokasi Pengantaran." : "Menuju pengantaran • tombol akan aktif dalam radius " + (int)ARRIVE_RADIUS_METER + " meter.");
            }else{
                showAction(arrivedDeliveryBtn, true);
                distanceInfo.setText("📡 GPS belum mendapatkan posisi akurat.");
                distanceHint.setText("Tombol tiba tersedia sebagai konfirmasi manual. Pastikan Anda benar-benar sudah sampai di tujuan.");
            }
            return;
        }
        if(st.equals("arrived_delivery")){
            showAction(finishBtn, true);
            distanceInfo.setText("🏁 Anda sudah tiba di lokasi pengantaran.");
            distanceHint.setText("Serahkan pesanan ke customer. Setelah diterima, tekan Selesaikan Order.");
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
        distanceHint.setText("Status server belum dikenali penuh. Gunakan tombol tiba pickup bila order memang sedang menuju pickup.");
    }

    private void hideAction(Button b){
        if(b == null) return;
        b.setVisibility(View.GONE);
        b.setEnabled(false);
        b.setAlpha(1f);
    }

    private void showAction(Button b, boolean enabled){
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
                .setTitle("Verifikasi OTP TransPickup")
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
        updatingStatus = true; setLoading(true);
        new Thread(() -> { try{
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
            mainHandler.post(() -> { updatingStatus=false; setLoading(false); if(ok){ pendingFinishOtp = ""; try{ order.put("status", next); }catch(Exception ignored){} saveActiveOrder(); refreshButtons(); mainHandler.postDelayed(() -> updateMap(), 250); info("Berhasil", m); if(next.equals("finished") || next.equals("completed")){ clearActiveOrder(); finish(); } } else info("Gagal", m); });
        }catch(Exception e){ mainHandler.post(() -> { updatingStatus=false; setLoading(false); info("Koneksi gagal", "Tidak bisa update status ke server."); }); }}).start();
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
        if(mapView==null || !mapReady || Build.VERSION.SDK_INT<19) return;
        try{
            mapView.evaluateJavascript("if(window.setSpeed)setSpeed("+currentSpeedKmh+","+averageSpeedKmh+");",null);
        }catch(Exception ignored){}
    }

    private void postDriverLocation(double lat, double lng, boolean force){
        if(!valid(lat, lng) || driverUsername.length() == 0) return;

        long now = System.currentTimeMillis();
        if(!force && now - lastLocationPostAt < LOCATION_POST_INTERVAL_MS) return;

        if(!force && valid(lastPostedLat, lastPostedLng)){
            float moved = distanceBetween(lastPostedLat, lastPostedLng, lat, lng);
            if(moved < 1.0f && now - lastLocationPostAt < LOCATION_POST_INTERVAL_MS * 2) return;
        }

        lastLocationPostAt = now;
        lastPostedLat = lat;
        lastPostedLng = lng;

        new Thread(() -> {
            try{
                JSONObject p = new JSONObject();
                p.put("username", driverUsername);
                p.put("latitude", lat);
                p.put("longitude", lng);
                driverType = resolveDriverTypeFromOrder();
                p.put("driver_type", driverType);
                if(order != null) p.put("order_id", orderId());
                if(lastAcceptedLocation != null){
                    p.put("accuracy", lastAcceptedLocation.hasAccuracy()?lastAcceptedLocation.getAccuracy():JSONObject.NULL);
                    p.put("speed", lastAcceptedLocation.hasSpeed()?lastAcceptedLocation.getSpeed():JSONObject.NULL);
                    p.put("bearing", lastAcceptedLocation.hasBearing()?lastAcceptedLocation.getBearing():JSONObject.NULL);
                    p.put("location_time", lastAcceptedLocation.getTime());
                }
                postJson(BASE_URL + "driver_update_location_native.php", p);
            }catch(Exception ignored){}
        }).start();
    }
    private void openChat(){
        try{
            String roomId = first(order.optString("room_id"), pref("active_chat_room_id"), "ROOM-" + orderId())
                    .trim().replace("_", "-").toUpperCase(Locale.US).replaceAll("[^A-Z0-9\\-]", "");
            if(!roomId.startsWith("ROOM-")) roomId = "ROOM-" + roomId;
            String customerName = first(order.optString("customer_name"), order.optString("customer"),
                    order.optString("username"), order.optString("user_id"), "Customer");
            getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                    .putString("active_order_id", orderId())
                    .putString("active_chat_order_id", orderId())
                    .putString("active_chat_room_id", roomId)
                    .putString("active_chat_driver_name", driverUsername)
                    .putString("active_chat_customer_name", customerName)
                    .putString("active_chat_order_status", status()).apply();
            Intent i = new Intent(this, DriverChatRoomActivity.class);
            i.putExtra("order_id", orderId());
            i.putExtra("order_db_id", internalId());
            i.putExtra("room_id", roomId);
            i.putExtra("participant_name", customerName);
            i.putExtra("customer_name", customerName);
            i.putExtra("order_type", isPickupOrder() ? "TransPickup" : first(order.optString("order_type"), "Order"));
            i.putExtra("order_status", status());
            i.putExtra("order_source", isPickupOrder() ? "pickup_orders" : "orders");
            i.putExtra("read_only", false);
            startActivity(i);
        }catch(Exception e){ info("Chat", "Gagal membuka chat."); }
    }

    private void openNativeNavigation(boolean pickup){
        double lat = pickup ? coord("pickup_lat","user_lat") : coord("delivery_lat","destination_lat");
        double lng = pickup ? coord("pickup_lng","user_lng") : coord("delivery_lng","destination_lng");
        if(!valid(lat,lng)){ info("Lokasi", "Koordinat belum tersedia."); return; }

        String mode = pickup ? "pickup" : "delivery";
        try{
            if(mapView != null && Build.VERSION.SDK_INT >= 19){
                mapView.evaluateJavascript("if(window.focusTarget)focusTarget('" + mode + "');", null);
            }
        }catch(Exception ignored){}

        try{
            getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                    .putString("driver_navigation_order_json", order == null ? "" : order.toString())
                    .putString("driver_navigation_target", mode)
                    .putString("driver_navigation_lat", String.valueOf(lat))
                    .putString("driver_navigation_lng", String.valueOf(lng))
                    .putString("driver_navigation_driver_lat", String.valueOf(lastDriverLat))
                    .putString("driver_navigation_driver_lng", String.valueOf(lastDriverLng))
                    .apply();
        }catch(Exception ignored){}

        // Use the Activity class directly. The driver APK applicationId is
        // com.transiva.driver, while this Activity intentionally remains in the
        // Java namespace com.transiva.app. Building the class name from
        // getPackageName() therefore points to a non-existent Activity and causes
        // the web fallback below to open instead.
        Intent nativeNav = new Intent(this, DriverNavigationActivity.class);
        nativeNav.putExtra("order_json", order == null ? "" : order.toString());
        nativeNav.putExtra("target", mode);
        nativeNav.putExtra("target_lat", lat);
        nativeNav.putExtra("target_lng", lng);
        nativeNav.putExtra("driver_lat", lastDriverLat);
        nativeNav.putExtra("driver_lng", lastDriverLng);
        try{
            startActivity(nativeNav);
            return;
        }catch(Exception ignored){}

        try{
            String uri = "google.navigation:q=" + lat + "," + lng + "&mode=d";
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri)));
        }catch(Exception e){
            info("Navigasi", pickup ? "Rute diarahkan ke lokasi pickup." : "Rute diarahkan ke tujuan pengantaran.");
        }
    }
    private JSONObject postJson(String urlText, JSONObject payload)throws Exception{ HttpURLConnection c=(HttpURLConnection)new URL(urlText).openConnection(); c.setConnectTimeout(TIMEOUT_MS); c.setReadTimeout(TIMEOUT_MS); c.setRequestMethod("POST"); c.setRequestProperty("Content-Type","application/json; charset=utf-8"); c.setRequestProperty("Accept","application/json"); try{ String t=session==null?"":session.getToken(); if(t!=null&&!t.trim().isEmpty()) c.setRequestProperty("Authorization","Bearer "+t.trim()); }catch(Exception ignored){} c.setDoOutput(true); OutputStream os=c.getOutputStream(); os.write(payload.toString().getBytes(StandardCharsets.UTF_8)); os.flush(); os.close(); InputStream is=c.getResponseCode()>=400?c.getErrorStream():c.getInputStream(); BufferedReader br=new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8)); StringBuilder sb=new StringBuilder(); String line; while((line=br.readLine())!=null) sb.append(line); br.close(); c.disconnect(); String body=sb.toString().trim(); return body.isEmpty()?new JSONObject():new JSONObject(body); }
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
    private double coord(String a, String b){ try{return Double.parseDouble(first(order.optString(a), order.optString(b), "0"));}catch(Exception e){return 0;} } private double optDouble(String... keys){ for(String k: keys){ try{ if(order.has(k)) return Double.parseDouble(order.optString(k,"0")); }catch(Exception ignored){} } return 0; }
    private String statusLabel(String s){ if(s.equals("taken"))return "Menuju Pickup"; if(s.equals("arrived_pickup"))return "Tiba Pickup"; if(s.equals("on_delivery"))return "Menuju Delivery"; if(s.equals("arrived_delivery"))return "Tiba Delivery"; if(s.equals("merchant_accepted"))return "Diterima Merchant"; if(s.equals("finished")||s.equals("completed"))return "Selesai"; return first(s,"Menuju Pickup"); }
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
                try{ bm.recycle(); }catch(Exception ignored){}
                return "data:image/png;base64," + b64;
            }
        }catch(Exception ignored){}
        return "";
    }

    private int dp(int v){ return (int)(v * getResources().getDisplayMetrics().density + .5f); } private void add(View v,int l,int t,int r,int b){ LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.setMargins(l,t,r,b); root.addView(v,lp); }
    private LinearLayout.LayoutParams btnLp(int top){ LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(52)); lp.setMargins(0, dp(top), 0, 0); return lp; }
    private LinearLayout card(){ LinearLayout v=new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setBackground(stroke("#FFFFFF", "#D7E6F8", dp(24), 1)); v.setElevation(dp(2)); return v; }
    private TextView text(String s,int sp,String color,boolean bold){ TextView t=new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(Color.parseColor(color)); if(bold)t.setTypeface(Typeface.DEFAULT_BOLD); return t; }
    private Button primary(String s){ Button b=new Button(this); b.setText(s); b.setAllCaps(false); b.setTextColor(Color.WHITE); b.setTextSize(14); b.setTypeface(Typeface.DEFAULT_BOLD); b.setBackground(gradient("#086BFF", "#2EA2FF", dp(18))); return b; } private Button green(String s){ Button b=primary(s); b.setBackground(gradient("#10B981", "#059669", dp(18))); return b; } private Button outline(String s){ Button b=new Button(this); b.setText(s); b.setAllCaps(false); b.setTextColor(Color.parseColor("#0B7CFF")); b.setTextSize(14); b.setTypeface(Typeface.DEFAULT_BOLD); b.setBackground(stroke("#FFFFFF", "#9DCAFF", dp(18), 1)); return b; }
    private GradientDrawable round(String color,int radius){ GradientDrawable g=new GradientDrawable(); g.setColor(Color.parseColor(color)); g.setCornerRadius(radius); return g; } private GradientDrawable stroke(String color,String st,int radius,int sw){ GradientDrawable g=round(color,radius); g.setStroke(dp(sw), Color.parseColor(st)); return g; } private GradientDrawable gradient(String c1,String c2,int radius){ GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{Color.parseColor(c1), Color.parseColor(c2)}); g.setCornerRadius(radius); return g; }
    private void setLoading(boolean b){ if(progressBar!=null) progressBar.setVisibility(b?View.VISIBLE:View.GONE); } private void info(String t,String m){ try{ new AlertDialog.Builder(this).setTitle(t).setMessage(m).setPositiveButton("OK", null).show(); }catch(Exception ignored){} }
}
