package com.transiva.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.location.Location;
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

import org.json.JSONObject;
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

    static Button actionButton(Activity activity) {
        Button button=new Button(activity);
        button.setAllCaps(false);
        button.setText("⌖  Perbaiki Lokasi");
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
        TextView status;
        EditText address;
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
            gps.setText("⌖  Ambil lokasi GPS");
            gps.setEnabled(false);
            FrameLayout.LayoutParams gpsPosition=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.END);
            gpsPosition.setMargins(dp(host,8),0,dp(host,8),dp(host,8));
            viewport.addView(gps,gpsPosition);
            body.addView(viewport,new LinearLayout.LayoutParams(-1,
                    Math.min(dp(host,420),host.getResources().getDisplayMetrics().heightPixels/2)));

            address=new EditText(host);
            address.setSingleLine(true);
            address.setHint("Patokan alamat (opsional)");
            address.setTextSize(14);
            address.setTextColor(DriverThemeTokens.textPrimary(host));
            address.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(165)});
            body.addView(address,new LinearLayout.LayoutParams(-1,-2));
            android.widget.ScrollView scroll=new android.widget.ScrollView(host);
            scroll.addView(body);
            dialog=PremiumDialogs.builder(host).setTitle(title).setView(scroll)
                    .setNegativeButton("Batal",null)
                    .setPositiveButton("stop".equals(kind)?"Kirim usulan":"Simpan lokasi",null).create();
            dialog.setOnDismissListener(ignored->{
                cancelGps();
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
            if(!alive())return;
            map=google;
            map.getUiSettings().setMapToolbarEnabled(false);
            map.getUiSettings().setRotateGesturesEnabled(false);
            map.getUiSettings().setTiltGesturesEnabled(false);
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(initial,16f));
            map.setOnCameraMoveStartedListener(reason->{
                cameraMoving=true;
                if(reason==GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE){
                    cancelGps();
                    status.setText("Geser peta untuk menyesuaikan titik.");
                }
                controls();
            });
            map.setOnCameraIdleListener(()->{cameraMoving=false;controls();});
            controls();
        }

        private boolean alive(){return dialog.isShowing() && !host.isFinishing() && !host.isDestroyed();}

        private void controls() {
            gps.setEnabled(!busy && map!=null && gpsRequest==null);
            save.setEnabled(!busy && map!=null && !cameraMoving && gpsRequest==null
                    && !initial.equals(map.getCameraPosition().target));
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
            String label=typed.isEmpty()?String.format(Locale.US,"%.6f, %.6f",point.latitude,point.longitude):typed;
            busy=true;
            map.getUiSettings().setAllGesturesEnabled(false);
            dialog.setCancelable(false);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
            status.setText("stop".equals(kind)?"Menghitung tarif singgah…":"Menyimpan lokasi dan rute…");
            controls();
            if(!DriverNetworkExecutor.executeOrderAction(()->{
                try{
                    JSONObject request=new JSONObject().put("order_id",NavigationTargetPolicy.id(order))
                            .put("source",NavigationTargetPolicy.source(order))
                            .put("point",kind).put("latitude",point.latitude).put("longitude",point.longitude)
                            .put("address",label);
                    com.transiva.app.driver.data.DriverApiClient client=new com.transiva.app.driver.data.DriverApiClient(session);
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

    private static void message(Activity activity,String message) {
        PremiumDialogs.builder(activity).setTitle("Perjalanan").setMessage(message).setPositiveButton("OK",null).show();
    }

    private static int dp(Activity activity,int value) {
        return Math.round(value*activity.getResources().getDisplayMetrics().density);
    }
}
