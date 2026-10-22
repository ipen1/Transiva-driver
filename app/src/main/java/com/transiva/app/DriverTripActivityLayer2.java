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
/** Stability 4.2 Layer Retirement Phase 1: frozen compatibility layer; new logic belongs in controllers. */
@Deprecated
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
        n.setVisibility(View.GONE);c.setOnClickListener(v->n.setVisibility(n.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));
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
        TextView guide = text("Chat, navigasi, dan pilihan perjalanan", 12, "#64748B", false);
        guide.setPadding(0, dp(4), 0, dp(4)); c.addView(guide);

        arrivedPickupBtn = slideAction("📍 Geser • Tiba di Penjemputan", () -> updateStatus("arrived_pickup")); actionDock.addView(arrivedPickupBtn, slideLp(0));
        startDeliveryBtn = slideAction(orderKind.equals("pickup") ? "📦 Geser • Paket Diambil, Mulai Antar" : vehicleEmoji() + " Geser • Mulai Perjalanan", () -> updateStatus("on_delivery")); actionDock.addView(startDeliveryBtn, slideLp(0));
        DriverEcosystemFeatures ecoNow=DriverEcosystemFeatures.from(order); JSONObject pendingStop=ecoNow.nextPendingWaypoint();
        if(isDeliveryPhase(status()) && pendingStop!=null){ int seq=pendingStop.optInt("sequence",1); SlideActionView stopBtn=slideAction("📍 Geser • Tiba di Stop "+seq, () -> markCurrentWaypointArrived(seq)); c.addView(stopBtn, slideLp(8)); }
        arrivedDeliveryBtn = slideAction("🏁 Geser • Tiba di Pengantaran", () -> { if(extensionNeedsArrival()) destinationExtension.arrive(); else updateStatus("arrived_delivery"); }); actionDock.addView(arrivedDeliveryBtn, slideLp(0));
        finishBtn = slideAction("✅ Geser • Selesaikan Order", () -> { if (isPickupOrder()) showPickupOtpDialog(); else updateStatus("finished"); }); actionDock.addView(finishBtn, slideLp(0));
        dockHint=text("Geser untuk melanjutkan perjalanan",12,"#64748B",false);actionDock.addView(dockHint);
        LinearLayout options=new LinearLayout(this);options.setOrientation(LinearLayout.VERTICAL);options.setVisibility(View.GONE);
        Button more=outline("Opsi perjalanan  ▾");more.setOnClickListener(v->{boolean open=options.getVisibility()!=View.VISIBLE;options.setVisibility(open?View.VISIBLE:View.GONE);more.setText(open?"Opsi perjalanan  ▴":"Opsi perjalanan  ▾");});c.addView(more,btnLp(6));c.addView(options);
        updatePriceBtn = outline("💰 Update Total"); updatePriceBtn.setOnClickListener(v -> showUpdatePriceDialog()); options.addView(updatePriceBtn, btnLp(6));
        if(isDeliveryPhase(status())){ Button extraFare=outline("Ajukan ongkir tambahan"); extraFare.setOnClickListener(v->requestExtraFareForMissedDistance()); options.addView(extraFare,btnLp(6)); }
        if(canExtendDestination()){
            Button addDestination=outline("＋ Tujuan baru / Pulang (PP)");
            addDestination.setOnClickListener(v->destinationExtension.show());c.addView(addDestination,btnLp(8));
            JSONObject proposal=order.optJSONObject("destination_extension");
            if(proposal!=null && "pending".equals(proposal.optString("status"))){
                c.addView(text("Menunggu customer: "+proposal.optString("address")+" • tambahan "+rupiah(proposal.optDouble("extra_fare")),13,"#B45309",true));
            }
        }
        cancelOrderBtn = dangerOutlineButton("Batalkan Order");
        cancellationController = new TripCancellationController(this, order, session, cancelOrderBtn);
        cancelOrderBtn.setOnClickListener(v -> cancellationController.show());
        options.addView(cancelOrderBtn, btnLp(6));

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
        base.setStroke(dp(1), DriverThemeTokens.color(this, "#BBD8F5"));

        GradientDrawable fill = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{DriverThemeTokens.color(this, "#0878F9"), DriverThemeTokens.color(this, "#29A8FF")}
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
                new int[]{DriverThemeTokens.color(this, "#FFFFFF"), DriverThemeTokens.color(this, "#EAF5FF")}
        );
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setSize(dp(42), dp(42));
        thumb.setStroke(dp(3), DriverThemeTokens.color(this, "#0878F9"));
        return thumb;
    }

    protected void startLocationWatch(){ if(order!=null && tripLocationController!=null) tripLocationController.start(); }
    protected void stopLocationWatch(){ if(tripLocationController!=null) tripLocationController.stop(); }



    protected void onDriverLocationChanged(Location l){
        SmoothLocationEngine.Fix fix = smoothLocation.offer(l);
        if(fix == null) return;

        Location accepted = fix.location;
        tripArrivalFix=new Location(l);
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
        if(tripLiveRoutes!=null)tripLiveRoutes.fix(l);
        if(tripVehicleMotion!=null)tripVehicleMotion.offer(accepted);
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
        if(tripVehicleMotion!=null)tripVehicleMotion.mapReady();
        requestStableRoute(false);
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

    protected boolean applyAgreedServerRoute(){
        JSONObject snapshot=order==null?null:order.optJSONObject("server_route");
        JSONObject payload=snapshot==null?null:snapshot.optJSONObject("route");JSONArray routes=payload==null?null:payload.optJSONArray("routes");
        JSONObject route=routes==null?null:routes.optJSONObject(0);JSONObject geometry=route==null?null:route.optJSONObject("geometry");JSONArray coords=geometry==null?null:geometry.optJSONArray("coordinates");
        if(googleMap==null||coords==null||coords.length()<2)return false;
        String routeKey=System.identityHashCode(googleMap)+"|"+snapshot.optString("route_hash",String.valueOf(System.identityHashCode(route)))+"|"+snapshot.optLong("version",0);
        if(routeKey.equals(appliedAgreedRouteHash)&&deliveryPolyline!=null)return true;
        java.util.ArrayList<LatLng> points=new java.util.ArrayList<>();for(int i=0;i<coords.length();i++){JSONArray xy=coords.optJSONArray(i);if(xy!=null&&xy.length()>1)points.add(new LatLng(xy.optDouble(1),xy.optDouble(0)));}
        if(points.size()<2)return false;
        if(deliveryPolyline!=null)deliveryPolyline.remove();deliveryPolyline=googleMap.addPolyline(new PolylineOptions().addAll(points).width(dp(5)).color(DriverThemeTokens.color(this,"#16A34A")));
        appliedAgreedRouteHash=routeKey;return true;
    }
    private String tripRouteKey(){
        return order==null?"":order.optString("order_id",order.optString("id"))+"|"+order.optString("source",orderKind)+"|"+status()+"|"+coord("pickup_lat","user_lat")+"|"+coord("pickup_lng","user_lng")+"|"+coord("delivery_lat","destination_lat")+"|"+coord("delivery_lng","destination_lng")+"|"+DriverEcosystemFeatures.from(order).waypoints.toString();
    }
    protected void requestStableRoute(boolean force){
        if(tripLiveRoutes!=null)tripLiveRoutes.request(force);
    }

    protected void applyPendingRoute(){
        if(tripLiveRoutes!=null)tripLiveRoutes.render();
    }

    protected void refreshButtons(){
        if(arrivedPickupBtn == null) return;
        String st = status();
        if(dockHint!=null)dockHint.setText(extensionNeedsArrival()?"Menuju tujuan tambahan":"Geser untuk melanjutkan perjalanan");

        beginActionUpdate();
        try{
        hideAction(arrivedPickupBtn);
        hideAction(startDeliveryBtn);
        hideAction(arrivedDeliveryBtn);
        hideAction(finishBtn);
        hideAction(updatePriceBtn);
        hideAction(cancelOrderBtn);
        if (DriverOrderCancellationPolicy.canCancel(st)) showAction(cancelOrderBtn, true);
        if(statusBadge != null) statusBadge.setText(extensionNeedsArrival()?"Tujuan tambahan":("arrived_delivery".equals(st)?"Tiba di tujuan":statusLabel(st)));

        if(st.equals("taken")){
            showAction(arrivedPickupBtn, false);
            float pd = distanceTo(coord("pickup_lat","user_lat"), coord("pickup_lng","user_lng"));
            if(pd >= 0){
                boolean near = arrivalNear(coord("pickup_lat","user_lat"),coord("pickup_lng","user_lng"),pd,ARRIVE_RADIUS_METER);
                showAction(arrivedPickupBtn, near);
                distanceInfo.setText("📍 Jarak langsung ke penjemputan: " + meter(pd));
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
                boolean near = arrivalNear(coord("delivery_lat","destination_lat"),coord("delivery_lng","destination_lng"),dd,ARRIVE_RADIUS_METER);
                showAction(arrivedDeliveryBtn, near);
                distanceInfo.setText("🏁 Jarak langsung ke pengantaran: " + meter(dd));
                distanceHint.setText(near ? "✓ Anda sudah berada di area pengantaran. Geser kontrol Tiba di Pengantaran ke kanan." : "Menuju pengantaran • kontrol geser akan aktif dalam radius " + (int)ARRIVE_RADIUS_METER + " meter.");
            }else{
                showAction(arrivedDeliveryBtn, true);
                distanceInfo.setText("📡 GPS belum mendapatkan posisi akurat.");
                distanceHint.setText("Kontrol geser tersedia sebagai konfirmasi manual. Pastikan Anda benar-benar sudah sampai di titik pengantaran.");
            }
            return;
        }
        if(st.equals("arrived_delivery") && extensionNeedsArrival()){
            float dd=distanceTo(coord("delivery_lat","destination_lat"),coord("delivery_lng","destination_lng"));
            showAction(arrivedDeliveryBtn,arrivalNear(coord("delivery_lat","destination_lat"),coord("delivery_lng","destination_lng"),dd,200));
            distanceInfo.setText(dd>=0 ? "🏁 Jarak langsung ke tujuan tambahan: "+meter(dd) : "Menunggu GPS tujuan tambahan");
            distanceHint.setText("Perjalanan tambahan disetujui. Tiba di semua stop, lalu geser Tiba di Pengantaran dalam radius 200 m.");
            return;
        }
        if(st.equals("arrived_delivery")){
            boolean received=order.optInt("customer_received",0)==1;
            boolean pending="pending".equals(order.optString("price_change_status"));
            showAction(finishBtn,received && !pending);
            if(dockHint!=null)dockHint.setText(pending?"Menunggu persetujuan biaya customer":received?"Customer telah mengonfirmasi. Geser untuk selesai.":"Menunggu konfirmasi customer");
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
            }finally{endActionUpdate();}
    }

    protected void hideAction(View b){
        if(b == null) return;
        if(collectingActions){desiredActions.put(b,null);return;}
        if(b.getVisibility()!=View.GONE)b.setVisibility(View.GONE);
        if(b.isEnabled())b.setEnabled(false);
        if(b.getAlpha()!=1f)b.setAlpha(1f);
    }

    protected void renderEcosystemFeatures(){ if(order==null)return; DriverEcosystemFeatures f=DriverEcosystemFeatures.from(order); if(distanceHint!=null && !f.summary().isEmpty()){String old=String.valueOf(distanceHint.getText()); if(!old.contains("Guardian")) distanceHint.setText(old+f.summary());} }

    protected void markCurrentWaypointArrived(int sequence){
        if(session==null||order==null||waypointArrivalBusy)return;
        final Location fix=trustedArrivalFix();
        if(fix==null){tripInfo("Multi Destination","Menunggu GPS terbaru yang akurat. Coba lagi sebentar.");return;}
        final String capturedOrder=orderId();final String capturedStatus=status();
        waypointArrivalBusy=true;if(progressBar!=null)progressBar.setVisibility(View.VISIBLE);
        boolean queued=tripRouteScope.execute(()->{
            try{
                JSONObject body=new JSONObject().put("action","arrive_waypoint").put("order_id",capturedOrder).put("sequence",sequence).put("driver_lat",fix.getLatitude()).put("driver_lng",fix.getLongitude());
                JSONObject response=new com.transiva.app.driver.data.DriverApiClient(session).post("ride_ecosystem_order.php",body).body;
                tripRouteScope.post(()->{
                    waypointArrivalBusy=false;if(progressBar!=null)progressBar.setVisibility(View.GONE);
                    if(order==null||!capturedOrder.equals(orderId())||!capturedStatus.equals(status()))return;
                    try{if(response.optJSONObject("ecosystem")!=null)order.put("ecosystem",response.optJSONObject("ecosystem"));if(response.optJSONObject("smart_waiting")!=null)order.put("smart_waiting",response.optJSONObject("smart_waiting"));}catch(org.json.JSONException e){NavigationDiagnostics.error(this,"TRIP_WAYPOINT_RESPONSE",e);}
                    saveActiveOrder();renderOrder();refreshButtons();requestStableRoute(true);
                });
            }catch(Exception e){tripRouteScope.post(()->{waypointArrivalBusy=false;if(progressBar!=null)progressBar.setVisibility(View.GONE);if(order!=null&&capturedOrder.equals(orderId()))tripInfo("Multi Destination",first(e.getMessage(),"Gagal menandai pemberhentian. Coba lagi."));});}
        });
        if(!queued){waypointArrivalBusy=false;if(progressBar!=null)progressBar.setVisibility(View.GONE);}
    }
}
