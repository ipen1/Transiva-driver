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
abstract class DriverTripActivityLayer1 extends Activity {
    protected static final String BASE_URL = "https://transiva.my.id/server/";
    protected static final String WEB_APP_URL = "https://transiva.my.id/";
    protected static final String LEAFLET_CSS = WEB_APP_URL + "js/leaflet.css";
    protected static final String LEAFLET_JS  = WEB_APP_URL + "js/leaflet.js";
    protected static final String PREF_NAME = "transiva";
    protected static final int TIMEOUT_MS = 20000;
    protected static final float ARRIVE_RADIUS_METER = 100f;
    protected static final float MAP_ANIMATION_MIN_DISTANCE_METER = 5.0f;
    protected static final long GPS_PRIORITY_MS = 8000L;
    protected static final long MAX_LOCATION_AGE_MS = 30000L;
    protected static final long OUT_OF_ORDER_TOLERANCE_MS = 1500L;

    protected final Handler mainHandler = new Handler(Looper.getMainLooper());
    protected LinearLayout root;
    protected ProgressBar progressBar;
    protected TextView statusBadge, distanceInfo, distanceHint;
    protected MapView mapView;
    protected GoogleMap googleMap;
    protected Marker driverMarker, pickupMarker, deliveryMarker;
    protected Polyline pickupPolyline, deliveryPolyline;
    protected SlideActionView arrivedPickupBtn, startDeliveryBtn, arrivedDeliveryBtn, finishBtn;
    protected Button updatePriceBtn, cancelOrderBtn, customerChatBtn;
    protected TripCancellationController cancellationController;
    protected TripCommunicationController communicationController;
    protected TripLocationController tripLocationController;
    protected TripOrderSnapshotStore tripSnapshotStore;
    protected JSONObject order;
    protected String driverUsername = "";
    protected String driverType = "motor";
    protected String orderKind = "order";
    protected double lastDriverLat = 0, lastDriverLng = 0;
    protected double renderedDriverLat = 0, renderedDriverLng = 0;
    protected double prevDriverLat = 0, prevDriverLng = 0;
    protected boolean updatingStatus = false;
    protected boolean mapReady = false;
    protected SessionManager session;
    protected DriverApiClient api;
    protected final SmoothLocationEngine smoothLocation = new SmoothLocationEngine(2500L);
    protected volatile boolean routeRequestInFlight = false;
    protected long lastRouteRequestAt = 0L;
    protected double lastRouteFromLat = 0d, lastRouteFromLng = 0d;
    protected String lastNativeRouteMode = "";
    protected String pendingRoutePoints = "";
    protected double pendingRouteKm = 0d;
    protected double pendingRouteSeconds = 0d;
    // Overview map: dua segmen tetap agar kamera tidak melompat setiap GPS berubah.
    protected String pendingPickupRoutePoints = "";
    protected String pendingDeliveryRoutePoints = "";
    protected double pendingPickupRouteKm = 0d;
    protected double pendingPickupRouteSeconds = 0d;
    protected double pendingDeliveryRouteKm = 0d;
    protected double pendingDeliveryRouteSeconds = 0d;
    protected double tripStartLat = 0d, tripStartLng = 0d;
    protected boolean overviewRoutesLoaded = false;
    protected boolean overviewMapApplied = false;
    protected String lastOverviewRouteStatus = "";
    protected double currentSpeedKmh = 0d;
    protected double averageSpeedKmh = 0d;
    protected double speedSampleSum = 0d;
    protected long speedSampleCount = 0L;
    protected Location lastSpeedLocation = null;

    protected final class SlideActionView extends LinearLayout {
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


    protected String pendingFinishOtp = "";

    protected void showAction(View b, boolean enabled){
        if(b == null) return;
        b.setVisibility(View.VISIBLE);
        b.setEnabled(enabled && !updatingStatus);
        b.setAlpha(enabled ? 1f : 0.48f);
    }

    protected boolean isDeliveryPhase(String st){ return DriverOrderStateMachine.isDeliveryPhase(st); }

    protected void showPickupOtpDialog() {
        if (updatingStatus) return;
        final EditText input = new EditText(this);
        input.setHint("Masukkan 6 digit OTP penerima");
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setPadding(dp(18), dp(8), dp(18), dp(8));
        AlertDialog ad = PremiumDialogs.builder(this)
                .setTitle("Verifikasi OTP TransSend")
                .setMessage("Minta OTP kepada penerima setelah paket benar-benar diterima.")
                .setView(input)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Verifikasi & Selesaikan", null)
                .create();
        ad.setOnShowListener(dialog -> { PremiumDialogs.applyPremiumStyle(ad); ad.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String otp = input.getText().toString().replaceAll("[^0-9]", "");
            if (otp.length() < 4) { input.setError("OTP belum lengkap"); return; }
            pendingFinishOtp = otp;
            ad.dismiss();
            updateStatus("completed");
        }); });
        ad.show();
    }

    protected void confirm(String msg, String next){ if(updatingStatus)return; PremiumDialogs.builder(this).setTitle("Konfirmasi").setMessage(msg).setNegativeButton("Batal",null).setPositiveButton("Ya",(d,w)->updateStatus(next)).show(); }
    protected void updateStatus(String next){
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
            mainHandler.post(() -> {
                updatingStatus=false;
                setLoading(false);
                if(ok){
                    pendingFinishOtp = "";
                    try{ order.put("status", next); }catch(Exception ignored){ TransivaDiagnostics.error(this,"order","NON_FATAL_EXCEPTION",ignored); }
                    saveActiveOrder();
                    refreshButtons();
                    mainHandler.postDelayed(() -> updateMap(), 250);
                    if(next.equals("finished") || next.equals("completed")){
                        final String finishedOrderId = orderId();
                        final String finishedInternalId = internalId();
                        final String finishedSource = isPickupOrder() ? "pickup_orders" : "orders";
                        DriverMessageUnreadRepository.clearOrder(this, finishedOrderId);
                        clearActiveOrder();
                        DriverOrderCompletionDialog.show(this, r, order,
                                (rating, review, callback) -> DriverNetworkExecutor.execute(() -> {
                                    try {
                                        JSONObject ratingPayload = new JSONObject();
                                        ratingPayload.put("source", finishedSource);
                                        ratingPayload.put("id", finishedInternalId);
                                        ratingPayload.put("order_id", finishedOrderId);
                                        ratingPayload.put("rating", rating);
                                        ratingPayload.put("review", review);
                                        JSONObject ratingResponse = postJson(BASE_URL + "driver_rate_customer.php", ratingPayload);
                                        callback.complete(ratingResponse.optBoolean("success", false), first(ratingResponse.optString("message"), "Penilaian customer tersimpan."));
                                    } catch (Exception ex) {
                                        callback.complete(false, first(ex.getMessage(), "Gagal menyimpan penilaian customer."));
                                    }
                                }),
                                this::finish);
                    } else {
                        info("Berhasil", m);
                    }
                } else info("Gagal", m);
            });
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
    protected String endpoint(String n){ return DriverOrderStateMachine.endpoint(n, isPickupOrder()); }
    protected boolean isPickupOrder(){
        String source = first(order == null ? "" : order.optString("source"),
                order == null ? "" : order.optString("source_table"), orderKind).toLowerCase(Locale.US);
        return source.equals("pickup_orders") || source.contains("pickup");
    }

    protected void updateSpeedMetrics(Location loc){
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

    protected void pushSpeedToMap(){
        // Speed tetap dihitung untuk UI/navigasi; Google Maps SDK tidak memerlukan JavaScript bridge.
    }

    // Upload lokasi hanya dilakukan oleh LocationService. Activity ini hanya
    // memakai fix GPS untuk UI, jarak, tombol kedatangan dan navigasi.

    protected boolean isNonCash(){
        String p = first(order == null ? "" : order.optString("payment_method"), "cash").toLowerCase(Locale.US);
        return p.equals("balance") || p.contains("transpay") || p.contains("transiva_pay") || p.equals("wallet") || p.equals("saldo");
    }
    protected Button dangerOutlineButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(Color.parseColor("#DC2626"));
        button.setBackground(stroke("#FFF7F7", "#EF4444", dp(15), 1));
        return button;
    }





    protected void showUpdatePriceDialog(){
        if(order == null) return;
        final EditText input = new EditText(this); input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); input.setHint("Total baru"); input.setText(String.valueOf((long)optDouble("price","fare","total")));
        final EditText reason = new EditText(this); reason.setHint("Alasan perubahan, contoh: 1 menu habis");
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(20),dp(4),dp(20),0); box.addView(input); box.addView(reason);
        PremiumDialogs.builder(this).setTitle("Update Total Dibayar").setMessage("Harga turun langsung berlaku. Harga naik wajib disetujui customer.").setView(box).setNegativeButton("Batal",null).setPositiveButton("Kirim",(d,w)->{
            try{
                double value=Double.parseDouble(input.getText().toString().trim()); String why=reason.getText().toString().trim();
                if(value<=0||why.isEmpty()){ info("Harga","Harga dan alasan wajib diisi."); return; }
                requestPriceChange(value,why);
            }catch(Exception e){ info("Harga","Nominal tidak valid."); }
        }).show();
    }
    protected void requestPriceChange(double value,String reason){
        setLoading(true); DriverNetworkExecutor.execute(()->{ try{
            JSONObject p=new JSONObject(); p.put("source",isPickupOrder()?"pickup_orders":"orders"); p.put("id",Integer.parseInt(internalId())); p.put("driver",driverUsername); p.put("new_price",value); p.put("reason",reason);
            JSONObject r=postJson(BASE_URL+"driver_request_price_change.php",p); boolean ok=r.optBoolean("success",false); String m=first(r.optString("message"),ok?"Harga diperbarui":"Gagal memperbarui harga");
            mainHandler.post(()->{setLoading(false); if(ok){ try{ if(!r.optBoolean("approval_required",false)) order.put("price",value); }catch(Exception ignored){ TransivaDiagnostics.error(this,"order","NON_FATAL_EXCEPTION",ignored); } info("Total Pembayaran",m); renderOrder(); refreshButtons(); } else info("Gagal",m);});
        }catch(Exception e){mainHandler.post(()->{setLoading(false);info("Gagal","Koneksi server bermasalah.");});}});
    }

    protected boolean isFoodOrder(){
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

    protected JSONObject postJson(String urlText, JSONObject payload) throws Exception {
        if (api == null) api = new DriverApiClient(session);
        String endpoint = urlText == null ? "" : urlText.trim();
        if (endpoint.startsWith(BASE_URL)) endpoint = endpoint.substring(BASE_URL.length());
        if (endpoint.startsWith("/")) endpoint = endpoint.substring(1);
        DriverApiClient.Result result = api.post(endpoint, payload == null ? new JSONObject() : payload);
        return result.body;
    }

    protected void saveActiveOrder(){ if(tripSnapshotStore!=null) tripSnapshotStore.save(order, orderKind, resolveDriverTypeFromOrder()); }
    protected void clearActiveOrder(){ if(tripSnapshotStore!=null) tripSnapshotStore.clear(); }
    protected String orderId(){ return first(order.optString("order_id"), order.optString("id"), "-"); } protected String internalId(){ return first(order.optString("id"), order.optString("order_id"), ""); } protected String status(){ return normalizeStatus(first(order.optString("status"), "taken")); }
    protected String normalizeStatus(String raw){ return DriverOrderStateMachine.normalize(raw); }
    protected String pickupAddress(){ return first(order.optString("pickup_address"), order.optString("pickup"), order.optString("sender_address"), "-"); } protected String deliveryAddress(){ return first(order.optString("delivery_address"), order.optString("destination_address"), order.optString("destination"), order.optString("receiver_address"), "-"); }
    protected String cleanServiceLabel(){ String s=first(order.optString("service_name"), order.optString("order_type"), orderKind.equals("pickup") ? "TransPickup" : "Food Delivery"); return s.trim(); }
    protected double coord(String a, String b){ try{return Double.parseDouble(first(order.optString(a), order.optString(b), "0"));}catch(Exception e){return 0;} } protected double optDouble(String... keys){ for(String k: keys){ try{ if(order.has(k)) return Double.parseDouble(order.optString(k,"0")); }catch(Exception ignored){ TransivaDiagnostics.error(this,"order","NON_FATAL_EXCEPTION",ignored); } } return 0; }
    protected String statusLabel(String s){ return DriverOrderStateMachine.label(s); }
    protected float distanceTo(double lat, double lng){ if(!valid(lastDriverLat,lastDriverLng)||!valid(lat,lng))return -1; float[] r=new float[1]; Location.distanceBetween(lastDriverLat,lastDriverLng,lat,lng,r); return r[0]; }
    protected boolean valid(double lat,double lng){ return lat!=0 && lng!=0 && !Double.isNaN(lat) && !Double.isNaN(lng); } protected String meter(float m){ return m>=1000 ? one(m/1000.0)+" km" : Math.round(m)+" meter"; }
    protected String rupiah(double v){ return "Rp " + NumberFormat.getNumberInstance(new Locale("id","ID")).format((long)v); } protected String one(double v){ return String.format(Locale.US,"%.1f",v); } protected String zero(double v){ return String.format(Locale.US,"%.0f",v); } protected String pref(String key){ try{return getSharedPreferences(PREF_NAME,MODE_PRIVATE).getString(key,"");}catch(Exception e){return "";} }
    protected String first(String... values){ if(values==null)return ""; for(String s: values) if(s!=null && s.trim().length()>0 && !"null".equalsIgnoreCase(s.trim())) return s.trim(); return ""; }
    protected String drawableDataUri(String... names){
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

    protected int dp(int v){ return (int)(v * getResources().getDisplayMetrics().density + .5f); } protected void add(View v,int l,int t,int r,int b){ LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.setMargins(l,t,r,b); root.addView(v,lp); }
    protected LinearLayout.LayoutParams btnLp(int top){ LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(52)); lp.setMargins(0, dp(top), 0, 0); return lp; }
    protected LinearLayout card(){ LinearLayout v=new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setBackground(stroke("#FFFFFF", "#D7E6F8", dp(24), 1)); v.setElevation(dp(2)); return v; }
    protected TextView text(String s,int sp,String color,boolean bold){ TextView t=new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(Color.parseColor(color)); if(bold)t.setTypeface(Typeface.DEFAULT_BOLD); return t; }
    protected Button primary(String s){ Button b=new Button(this); b.setText(s); b.setAllCaps(false); b.setTextColor(Color.WHITE); b.setTextSize(14); b.setTypeface(Typeface.DEFAULT_BOLD); b.setBackground(gradient("#086BFF", "#2EA2FF", dp(18))); return b; } protected Button green(String s){ Button b=primary(s); b.setBackground(gradient("#10B981", "#059669", dp(18))); return b; } protected Button outline(String s){ Button b=new Button(this); b.setText(s); b.setAllCaps(false); b.setTextColor(Color.parseColor("#0B7CFF")); b.setTextSize(14); b.setTypeface(Typeface.DEFAULT_BOLD); b.setBackground(stroke("#FFFFFF", "#9DCAFF", dp(18), 1)); return b; }
    protected GradientDrawable round(String color,int radius){ GradientDrawable g=new GradientDrawable(); g.setColor(Color.parseColor(color)); g.setCornerRadius(radius); return g; } protected GradientDrawable stroke(String color,String st,int radius,int sw){ GradientDrawable g=round(color,radius); g.setStroke(dp(sw), Color.parseColor(st)); return g; } protected GradientDrawable gradient(String c1,String c2,int radius){ GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{Color.parseColor(c1), Color.parseColor(c2)}); g.setCornerRadius(radius); return g; }
    protected void setLoading(boolean b){ if(progressBar!=null) progressBar.setVisibility(b?View.VISIBLE:View.GONE); } protected void info(String t,String m){ try{ PremiumDialogs.builder(this).setTitle(t).setMessage(m).setPositiveButton("OK", null).show(); }catch(Exception ignored){ TransivaDiagnostics.error(this,"order","NON_FATAL_EXCEPTION",ignored); } }
    // Cross-layer contracts keep the split type-safe without duplicating state.
    protected abstract void onCreate(Bundle b);
    protected abstract void onResume();
    protected abstract void onPause();
    protected abstract void onDestroy();
    protected abstract void onLowMemory();
    protected abstract void loadSession();
    protected abstract String normalizeDriverType(String value);
    protected abstract String resolveDriverTypeFromOrder();
    protected abstract String vehicleEmoji();
    protected abstract String vehicleLabel();
    protected abstract void loadOrder();
    protected abstract void buildBase();
    protected abstract void renderEmpty();
    protected abstract void renderOrder();
    protected abstract void top(String title, String sub);
    protected abstract void addHeaderCard();
    protected abstract void mini(LinearLayout parent, String icon, String label, String value);
    protected abstract void addLocationCard(String title, String body, boolean pickup);
    protected abstract void addMapCard();
    protected abstract void initNativeTripMarkers();
    protected abstract java.util.List<LatLng> parseRoutePoints(String json);
    protected abstract void fitNativeOverview();
    protected abstract String routeTargetMode();
    protected abstract double bearing(double lat1, double lng1, double lat2, double lng2);
    protected abstract void addFoodOrNoteCard();
    protected abstract String merchantStatusLabel(String raw);
    protected abstract String foodItemOptions(JSONObject item);
    protected abstract JSONObject parseFoodNote();
    protected abstract String customerNote();
    protected abstract void addPlainNoteCard();
    protected abstract void rowText(LinearLayout p, String l, String v);
    protected abstract void addActions();
    protected abstract LinearLayout.LayoutParams slideLp(int top);
    protected abstract SlideActionView slideAction(String label, Runnable action);
    protected abstract Drawable premiumSliderTrack();
    protected abstract Drawable premiumSliderThumb();
    protected abstract void startLocationWatch();
    protected abstract void stopLocationWatch();
    protected abstract void onDriverLocationChanged(Location l);
    protected abstract float distanceBetween(double aLat, double aLng, double bLat, double bLng);
    protected abstract void updateMap();
    protected abstract com.google.android.gms.maps.model.BitmapDescriptor driverVehicleIcon();
    protected abstract void requestStableRoute(boolean force);
    protected abstract void applyPendingRoute();
    protected abstract void refreshButtons();
    protected abstract void hideAction(View b);

}
