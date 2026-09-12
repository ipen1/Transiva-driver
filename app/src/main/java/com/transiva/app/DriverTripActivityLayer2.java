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
abstract class DriverTripActivityLayer2 extends DriverTripActivityLayer1 {
    protected JSONObject parseFoodNote(){
        try{ JSONObject d = new JSONObject(first(order.optString("note"), "{}")); return "food".equalsIgnoreCase(d.optString("type")) ? d : null; }catch(Exception e){ return null; }
    }
    protected String customerNote(){
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

    protected void addPlainNoteCard(){
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
    protected void rowText(LinearLayout p, String l, String v){
        LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(0, dp(7),0,dp(7));
        r.addView(text(l, 14, "#64748B", false), new LinearLayout.LayoutParams(0,-2,1)); r.addView(text(v, 14, "#111827", true)); p.addView(r);
    }
    protected void addActions(){
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
    protected LinearLayout.LayoutParams slideLp(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(72));
        p.setMargins(0, dp(top), 0, 0);
        return p;
    }

    protected SlideActionView slideAction(String label, Runnable action) {
        return new SlideActionView(label, action);
    }

    /** Premium thick progress track for the trip status slider. */
    protected Drawable premiumSliderTrack() {
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

    protected Drawable premiumSliderThumb() {
        GradientDrawable thumb = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.parseColor("#FFFFFF"), Color.parseColor("#EAF5FF")}
        );
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setSize(dp(42), dp(42));
        thumb.setStroke(dp(3), Color.parseColor("#0878F9"));
        return thumb;
    }

    protected void startLocationWatch(){ if(order!=null && tripLocationController!=null) tripLocationController.start(); }
    protected void stopLocationWatch(){ if(tripLocationController!=null) tripLocationController.stop(); }



    protected void onDriverLocationChanged(Location l){
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

    protected float distanceBetween(double aLat, double aLng, double bLat, double bLng){
        try{
            float[] r = new float[1];
            Location.distanceBetween(aLat, aLng, bLat, bLng, r);
            return r[0];
        }catch(Exception e){ return 999f; }
    }

    protected void updateMap(){
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

    protected com.google.android.gms.maps.model.BitmapDescriptor driverVehicleIcon(){
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

    protected void requestStableRoute(boolean force){
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

    protected void applyPendingRoute(){
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

    protected void refreshButtons(){
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

    protected void hideAction(View b){
        if(b == null) return;
        b.setVisibility(View.GONE);
        b.setEnabled(false);
        b.setAlpha(1f);
    }
}
