package com.transiva.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.location.CurrentLocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.tasks.CancellationTokenSource;

import com.transiva.app.driver.data.DriverApiClient;

import org.json.JSONObject;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Assigns the crosshair center to the selected order point. Stops still use their quoted proposal. */
final class DriverLocationProposal {
    private DriverLocationProposal() {}

    static String pointFor(JSONObject order) {
        String status=DriverOrderStateMachine.normalize(order.optString("status"));
        if ("taken".equals(status)) return "pickup";
        if ("on_delivery".equals(status)) return "delivery";
        return "";
    }

    static boolean available(JSONObject order) {
        if (order==null || order.optString("order_id").trim().isEmpty() || pointFor(order).isEmpty()) return false;
        if ("pickup_orders".equals(NavigationTargetPolicy.source(order))) return true;
        switch (order.optString("order_type").trim().toLowerCase(Locale.ROOT)) {
            case "transbike": case "transride": case "transcar": case "ride": case "bike": case "car":
            case "trans simpel": case "transsimpel": return true;
            default: return false;
        }
    }

    static boolean canAddStop(JSONObject order) {
        if (!available(order) || !"delivery".equals(pointFor(order))) return false;
        if ("pending".equals(order.optString("price_change_status"))
                || "qris".equalsIgnoreCase(order.optString("payment_method"))) return false;
        JSONObject policy=order.optJSONObject("stop_pricing");
        return policy==null || DriverEcosystemFeatures.from(order).waypoints.length()<policy.optInt("max_stops");
    }

    static Button actionButton(Activity activity) {
        Button button=new Button(activity);
        button.setAllCaps(false);
        button.setText("Perbaiki Lokasi");
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_trip_correct_location,0,0,0);
        button.setCompoundDrawablePadding(dp(activity,5));
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextSize(13);
        button.setTextColor(DriverThemeTokens.isDark(activity)?0xffc4dfff:0xff0958bd);
        button.setMinHeight(dp(activity,48));
        button.setMinimumHeight(dp(activity,48));
        button.setPadding(dp(activity,12),dp(activity,5),dp(activity,12),dp(activity,5));
        button.setStateListAnimator(null);
        GradientDrawable fill=DriverNavigationUi.roundRect(DriverThemeTokens.surfaceAlt(activity),dp(activity,13));
        fill.setStroke(dp(activity,1),DriverThemeTokens.border(activity));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33086bff),fill,null));
        return button;
    }

    static void show(Activity activity, SessionManager session, JSONObject order, Location latest, boolean stop) {
        if(session==null || order==null || activity.isFinishing() || activity.isDestroyed()) return;
        String phase=pointFor(order);
        if(!available(order) || (stop && !"delivery".equals(phase))) {
            message(activity,"Perbaiki lokasi saat menuju titik jemput atau tujuan. Singgah dapat diajukan saat mengantar.");
            return;
        }
        String reason=order.optString("price_change_reason");
        boolean previousCorrection=!stop && reason.startsWith("[Lokasi #")
                && reason.contains("] "+("pickup".equals(phase)?"Jemput:":"Tujuan:"));
        if("pending".equals(order.optString("price_change_status")) && !previousCorrection) {
            message(activity,"Masih ada pengajuan lain yang menunggu customer.");
            return;
        }

        String kind=stop?"stop":phase;
        double lat=order.optDouble(phase+"_lat",order.optDouble("pickup".equals(phase)?"user_lat":"destination_lat"));
        double lng=order.optDouble(phase+"_lng",order.optDouble("pickup".equals(phase)?"user_lng":"destination_lng"));
        if(!NavigationTargetPolicy.valid(lat,lng)){
            message(activity,"Titik awal belum tersedia.");
            return;
        }
        new Picker(activity,session,order,latest,kind,new LatLng(lat,lng)).show();
    }

    private static final class Picker {
        final Activity host;
        final SessionManager session;
        final JSONObject order;
        final Location latest;
        final String kind;
        final LatLng initial;
        AlertDialog dialog;
        MapView mapView;
        GoogleMap map;
        Button gps,save;
        TextView status, addressPreview;
        EditText address;
        final Handler main = new Handler(Looper.getMainLooper());
        Runnable lookup;
        int addressVersion;
        LatLng addressPoint;
        AddressLabel resolvedAddress;
        boolean addressLoading;
        boolean busy,cameraMoving;
        CancellationTokenSource gpsRequest;

        Picker(Activity host,SessionManager session,JSONObject order,Location latest,String kind,LatLng initial) {
            this.host=host;this.session=session;this.order=order;this.latest=latest;this.kind=kind;this.initial=initial;
        }

        void show() {
            String title="stop".equals(kind)?"Tambah Singgah":"Perbaiki lokasi "
                    +("pickup".equals(kind)?"jemput":"tujuan");
            LinearLayout body=new LinearLayout(host);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(dp(host,12),dp(host,3),dp(host,12),dp(host,8));
            status=new TextView(host);
            status.setTextColor(DriverThemeTokens.textSecondary(host));
            if ("stop".equals(kind)) {
                TextView tariff=new TextView(host);
                tariff.setTextColor(DriverThemeTokens.textPrimary(host));
                JSONObject policy=order.optJSONObject("stop_pricing");
                String fee=policy==null?"Biaya singgah mengikuti tarif server."
                        :"Biaya singgah Rp"+java.text.NumberFormat.getIntegerInstance(new Locale("id","ID"))
                                .format(policy.optLong("stop_fee"))+".";
                tariff.setText(fee+" Total juga mengikuti tambahan jarak. Jika bayar pakai TransPay, customer akan menerima pengajuan.");
                tariff.setPadding(0,0,0,dp(host,6));
                body.addView(tariff);
            }
            status.setText("Geser peta hingga titik yang benar berada di ujung marka.");
            status.setPadding(0,0,0,dp(host,8));
            body.addView(status);

            FrameLayout viewport=new FrameLayout(host);
            viewport.setBackground(DriverNavigationUi.roundRect(DriverThemeTokens.surfaceAlt(host),dp(host,14)));
            viewport.setClipToOutline(true);
            mapView=new MapView(host){
                @Override public boolean dispatchTouchEvent(MotionEvent event) {
                    if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(
                            event.getAction()!=MotionEvent.ACTION_UP && event.getAction()!=MotionEvent.ACTION_CANCEL);
                    return super.dispatchTouchEvent(event);
                }
            };
            mapView.onCreate(null);
            viewport.addView(mapView,new FrameLayout.LayoutParams(-1,-1));

            ImageView pin=new ImageView(host);
            pin.setImageResource("pickup".equals(kind)?R.drawable.map_pickup_pin:
                    "stop".equals(kind)?R.drawable.map_center_pin:R.drawable.map_destination_pin);
            pin.setScaleType(ImageView.ScaleType.FIT_XY);
            pin.setImportantForAccessibility(ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO);
            pin.setTranslationY(-dp(host,23));
            viewport.addView(pin,new FrameLayout.LayoutParams(dp(host,36),dp(host,46),Gravity.CENTER));

            gps=actionButton(host);
            gps.setText("Ambil lokasi GPS");
            gps.setEnabled(false);
            FrameLayout.LayoutParams gpsPosition=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.END);
            gpsPosition.setMargins(dp(host,8),0,dp(host,8),dp(host,8));
            viewport.addView(gps,gpsPosition);
            body.addView(viewport,new LinearLayout.LayoutParams(-1,
                    Math.min(dp(host,420),host.getResources().getDisplayMetrics().heightPixels/2)));

            addressPreview = new TextView(host);
            addressPreview.setTextSize(13);
            addressPreview.setTextColor(DriverThemeTokens.textPrimary(host));
            addressPreview.setMaxLines(2);
            addressPreview.setEllipsize(TextUtils.TruncateAt.END);
            addressPreview.setPadding(0, dp(host,6), 0, 0);
            addressPreview.setVisibility(TextView.GONE);
            body.addView(addressPreview, new LinearLayout.LayoutParams(-1,-2));

            address=new EditText(host);
            address.setSingleLine(true);
            address.setHint("Patokan alamat (opsional)");
            address.setTextSize(14);
            address.setTextColor(DriverThemeTokens.textPrimary(host));
            address.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(165)});
            body.addView(address,new LinearLayout.LayoutParams(-1,-2));
            address.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (save != null) controls();
                }
                @Override public void afterTextChanged(Editable ignored) {}
            });
            android.widget.ScrollView scroll=new android.widget.ScrollView(host);
            scroll.addView(body);
            dialog=PremiumDialogs.builder(host).setTitle(title).setView(scroll)
                    .setNegativeButton("Batal",null)
                    .setPositiveButton("stop".equals(kind)?"Tambah persinggahan":"Simpan lokasi",null).create();
            dialog.setOnDismissListener(ignored->{
                cancelGps();
                cancelAddressLookup();
                mapView.onPause();mapView.onStop();mapView.onDestroy();
                map=null;
            });
            dialog.show();
            mapView.onStart();mapView.onResume();
            save=dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            save.setEnabled(false);
            save.setOnClickListener(v->submit());
            gps.setOnClickListener(v->locate());
            mapView.getMapAsync(this::ready);
        }

        private void ready(GoogleMap google) {
            DriverGoogleMapStyle.apply(host, google);
            if(!alive())return;
            map=google;
            map.getUiSettings().setMapToolbarEnabled(false);
            map.getUiSettings().setRotateGesturesEnabled(false);
            map.getUiSettings().setTiltGesturesEnabled(false);
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(initial,16f));
            map.setOnCameraMoveStartedListener(reason->{
                cameraMoving=true;
                cancelAddressLookup();
                addressPreview.setVisibility(TextView.GONE);
                if(reason==GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE){
                    cancelGps();
                    status.setText("Geser peta untuk menyesuaikan titik.");
                }
                controls();
            });
            map.setOnCameraIdleListener(() -> {
                cameraMoving = false;
                if (!busy) lookupAddress(map.getCameraPosition().target);
                controls();
            });
            controls();
        }

        private boolean alive(){return dialog.isShowing() && !host.isFinishing() && !host.isDestroyed();}

        private void controls() {
            gps.setEnabled(!busy && map!=null && gpsRequest==null);
            save.setEnabled(!busy && map!=null && !cameraMoving && gpsRequest==null
                    && !initial.equals(map.getCameraPosition().target)
                    && (!addressLoading || !address.getText().toString().trim().isEmpty()));
        }

        private void cancelAddressLookup() {
            ++addressVersion;
            if (lookup != null) main.removeCallbacks(lookup);
            lookup = null;
            resolvedAddress = null;
            addressPoint = null;
            addressLoading = false;
        }

        private void lookupAddress(LatLng point) {
            cancelAddressLookup();
            if (initial.equals(point) || !NavigationTargetPolicy.valid(point.latitude, point.longitude)) return;
            int version = addressVersion;
            addressLoading = true;
            addressPreview.setText("Mencari alamat titik pilihan…");
            addressPreview.setVisibility(TextView.VISIBLE);
            lookup = () -> {
                boolean queued = DriverNetworkExecutor.execute(() -> {
                    AddressLabel found = reverseAddress(point, true);
                    main.post(() -> showAddress(version, point, found));
                });
                if (!queued) showAddress(version, point, new AddressLabel("", false));
            };
            main.postDelayed(lookup, 450);
        }

        private void showAddress(int version, LatLng point, AddressLabel found) {
            if (version != addressVersion || !alive() || map == null
                    || !point.equals(map.getCameraPosition().target)) return;
            resolvedAddress = found;
            addressPoint = point;
            addressLoading = false;
            addressPreview.setText(found.text.isEmpty() ? "Alamat jalan belum ditemukan. Tambahkan patokan jika ada."
                    : found.google ? "Google Maps · " + found.text : found.text);
            controls();
        }

        private AddressLabel reverseAddress(LatLng point, boolean preview) {
            DriverApiClient client = new DriverApiClient(session);
            try {
                JSONObject query = new JSONObject().put("latitude", point.latitude)
                        .put("longitude", point.longitude).put("google_preview", preview);
                JSONObject response = client.post("driver_reverse_geocode.php", query).body;
                String label = compactAddress(response.optString("address"));
                if (!label.isEmpty()) return new AddressLabel(label, "google".equals(response.optString("provider")));
            } catch (Exception ignored) {
                // Devices with an Android geocoder can still resolve the selected coordinates.
            } finally {
                client.shutdown();
            }
            try {
                if (Geocoder.isPresent()) {
                    List<Address> matches = new Geocoder(host, new Locale("id", "ID"))
                            .getFromLocation(point.latitude, point.longitude, 1);
                    if (matches != null && !matches.isEmpty()) {
                        return new AddressLabel(compactAddress(matches.get(0).getAddressLine(0)), false);
                    }
                }
            } catch (Exception ignored) {}
            return new AddressLabel("", false);
        }

        private void cancelGps() {
            CancellationTokenSource current=gpsRequest;
            gpsRequest=null;
            if(current!=null)current.cancel();
        }

        private static boolean usableFix(Location fix) {
            if(fix==null || !fix.hasAccuracy() || fix.getAccuracy()>80) return false;
            long age=SystemClock.elapsedRealtimeNanos()-fix.getElapsedRealtimeNanos();
            return age>=0 && age<12_000_000_000L && NavigationTargetPolicy.valid(fix.getLatitude(),fix.getLongitude());
        }

        private void locate() {
            if(!alive()||busy||map==null)return;
            if(usableFix(latest)){applyGps(latest);return;}
            if(host.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED
                    && host.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
                status.setText("Aktifkan izin lokasi untuk memakai GPS.");
                return;
            }
            CancellationTokenSource request=new CancellationTokenSource();
            gpsRequest=request;
            status.setText("Mengambil lokasi GPS…");
            controls();
            try{
                CurrentLocationRequest current=new CurrentLocationRequest.Builder()
                        .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                        .setMaxUpdateAgeMillis(10000).setDurationMillis(10000).build();
                LocationServices.getFusedLocationProviderClient(host).getCurrentLocation(current,request.getToken())
                        .addOnSuccessListener(fix->completeGps(request,fix))
                        .addOnFailureListener(error->completeGps(request,null));
            }catch(SecurityException error){completeGps(request,null);}
        }

        private void completeGps(CancellationTokenSource expected,Location fix) {
            if(gpsRequest!=expected || !alive())return;
            gpsRequest=null;
            applyGps(fix);
        }

        private void applyGps(Location fix){
            if(!usableFix(fix)){
                status.setText("GPS belum akurat. Coba lagi atau geser peta.");
                controls();
                return;
            }
            cameraMoving=true;
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(fix.getLatitude(),fix.getLongitude()),17f));
            status.setText("Lokasi GPS ±"+Math.round(fix.getAccuracy())+" m");
            controls();
        }

        private void submit() {
            if(busy || cameraMoving || !alive() || map==null) return;
            LatLng point=map.getCameraPosition().target;
            if(point.equals(initial)||!NavigationTargetPolicy.valid(point.latitude,point.longitude))return;
            String typed=address.getText().toString().trim();
            AddressLabel current = point.equals(addressPoint) ? resolvedAddress : null;
            busy=true;
            map.getUiSettings().setAllGesturesEnabled(false);
            dialog.setCancelable(false);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
            status.setText("stop".equals(kind)?"Menghitung tarif singgah…":"Menyimpan lokasi dan rute…");
            controls();
            if(!DriverNetworkExecutor.executeOrderAction(()->{
                try{
                    String label = typed;
                    if (label.isEmpty()) {
                        // The Google label is a live preview. Resolve the shared order label independently.
                        label = current == null || current.google
                                ? reverseAddress(point, false).text : current.text;
                        if (label.isEmpty()) label = "pickup".equals(kind) ? "Titik jemput di peta"
                                : "stop".equals(kind) ? "Titik singgah di peta" : "Titik tujuan di peta";
                    }
                    JSONObject request=new JSONObject().put("order_id",NavigationTargetPolicy.id(order))
                            .put("source",NavigationTargetPolicy.source(order))
                            .put("point",kind).put("apply_cash_stop","stop".equals(kind))
                            .put("latitude",point.latitude).put("longitude",point.longitude)
                            .put("address",label);
                    DriverApiClient client = new DriverApiClient(session);
                    JSONObject response;
                    try{response=client.post("driver_propose_location.php",request).body;}
                    finally{client.shutdown();}
                    host.runOnUiThread(()->{
                        if(!alive())return;
                        dialog.dismiss();
                        Toast.makeText(host,response.optString("message","Lokasi diperbarui."),Toast.LENGTH_LONG).show();
                    });
                }catch(Exception error){host.runOnUiThread(()->failed(error.getMessage()));}
            }))failed("Koneksi sedang sibuk. Coba lagi.");
        }

        private void failed(String error){
            if(!alive())return;
            busy=false;
            map.getUiSettings().setAllGesturesEnabled(true);
            map.getUiSettings().setRotateGesturesEnabled(false);
            map.getUiSettings().setTiltGesturesEnabled(false);
            dialog.setCancelable(true);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
            status.setText(error==null?"Lokasi belum tersimpan. Coba lagi.":error);
            controls();
        }
    }

    private static final class AddressLabel {
        final String text;
        final boolean google;

        AddressLabel(String text, boolean google) {
            this.text = text;
            this.google = google;
        }
    }

    static String compactAddress(String address) {
        if (address == null) return "";
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        for (String raw : address.split(",")) {
            String part = raw.trim().replaceFirst("\\s+[0-9]{5}$", "");
            if (part.isEmpty() || part.equalsIgnoreCase("Indonesia") || part.matches("[0-9]{5}")) continue;
            parts.add(part);
            if (parts.size() == 3) break;
        }
        String result = TextUtils.join(", ", parts);
        int length = result.codePointCount(0, result.length());
        return length <= 120 ? result : result.substring(0, result.offsetByCodePoints(0, 119)).trim() + "…";
    }

    private static void message(Activity activity,String message) {
        PremiumDialogs.builder(activity).setTitle("Perjalanan").setMessage(message).setPositiveButton("OK",null).show();
    }

    private static int dp(Activity activity,int value) {
        return Math.round(value*activity.getResources().getDisplayMetrics().density);
    }
}
