package com.transiva.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.location.Address;
import android.location.Geocoder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
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
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.tasks.CancellationTokenSource;

import org.json.JSONObject;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DriverSimpelPointActivity extends android.app.Activity {
    private static final int LOCATION_PERMISSION=724;
    private static final float ZOOM=17f;
    private static final LatLng DEFAULT_CENTER=new LatLng(-0.9003,119.8779);
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService geocoder=Executors.newSingleThreadExecutor();
    private MapView mapView;
    private GoogleMap googleMap;
    private LatLng selected;
    private TextView address;
    private Button usePoint, locate, routeUpdate;
    private final java.util.List<com.google.android.gms.maps.model.Marker> sharedMarkers=new java.util.ArrayList<>();
    private final Runnable routePoll=this::loadRoute;
    private String contactId;
    private JSONObject baseRoute, latestRoute;
    private boolean routeVisible, routeReading, routeClosed, cameraMoving;
    private Future<?> addressRead;
    private Runnable addressDebounce;
    private CancellationTokenSource locationRequest;
    private String kind, resolved="";
    private int selectionVersion;
    private boolean confirming;
    private ImageView selectionPin;
    private View selectionShadow;
    private long landingAt;
    private DriverUiScope routeNetwork;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Intent intent=getIntent();
        routeNetwork=new DriverUiScope(this);
        contactId=intent.getStringExtra("route_contact");
        baseRoute=parse(saved==null?intent.getStringExtra("route_snapshot"):saved.getString("route_base"));
        latestRoute=parse(saved==null?null:saved.getString("route_latest"));
        kind=intent.getStringExtra("point_kind");
        if (!"pickup".equals(kind) && !"delivery".equals(kind) && !"stop".equals(kind)) {
            String title=intent.getStringExtra("title");
            kind=title!=null && title.toLowerCase(Locale.ROOT).contains("antar")?"delivery":"pickup";
        }
        buildScreen();

        Bundle mapState=saved==null?null:saved.getBundle("simpel_map");
        mapView.onCreate(mapState);
        double lat=saved==null?intent.getDoubleExtra("lat",Double.NaN):saved.getDouble("lat",Double.NaN);
        double lng=saved==null?intent.getDoubleExtra("lng",Double.NaN):saved.getDouble("lng",Double.NaN);
        boolean seeded=valid(lat,lng);
        LatLng seed=seeded?new LatLng(lat,lng):DEFAULT_CENTER;
        mapView.getMapAsync(map->{
            if (isFinishing() || isDestroyed()) return;
            googleMap=map;
            map.getUiSettings().setMapToolbarEnabled(false);
            map.getUiSettings().setMyLocationButtonEnabled(false);
            map.getUiSettings().setRotateGesturesEnabled(false);
            map.getUiSettings().setTiltGesturesEnabled(false);
            updateSavedMarkers();
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(seed,ZOOM));
            map.setOnCameraMoveStartedListener(reason->{
                if (reason==GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) cancelLocation();
                cameraMoving=true;
                invalidateAddress();
                if (!confirming) address.setText("Menyesuaikan titik…");
                usePoint.setEnabled(false);
                usePoint.setAlpha(.55f);
            });
            map.setOnCameraIdleListener(()->{
                cameraMoving=false;
                updateSelection(map.getCameraPosition().target);
            });
            if (seeded) updateSelection(seed);
            else {
                locate.setEnabled(true);
                address.setText("Geser peta atau ambil lokasi");
            }
        });
    }

    private void buildScreen() {
        LinearLayout page=new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xFFEAF3FF);
        LinearLayout header=new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8),dp(2),dp(12),dp(2));
        header.setBackground(gradient(0xFF092D78,0xFF075FCF,0));
        Button back=button("‹",0xFFFFFFFF);
        back.setTextSize(30);
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setContentDescription("Kembali");
        back.setOnClickListener(v->finish());
        header.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));
        String title="delivery".equals(kind)?"Tentukan alamat pengantaran":"stop".equals(kind)?"Atur titik singgah":"Atur titik jemput";
        header.addView(text(title,19,Color.WHITE,true),new LinearLayout.LayoutParams(0,-2,1));
        page.addView(header);

        FrameLayout viewport=new FrameLayout(this);
        mapView=new MapView(this);
        viewport.addView(mapView,new FrameLayout.LayoutParams(-1,-1));
        selectionShadow=new View(this);
        GradientDrawable oval=new GradientDrawable();
        oval.setShape(GradientDrawable.OVAL);
        oval.setColor(0x500F3153);
        selectionShadow.setBackground(oval);
        selectionShadow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        viewport.addView(selectionShadow,new FrameLayout.LayoutParams(dp(16),dp(6),Gravity.CENTER));
        ImageView pin=new ImageView(this);
        selectionPin=pin;
        pin.setImageBitmap(pin(kind));
        pin.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        pin.setTranslationY(-dp(33));
        viewport.addView(pin,new FrameLayout.LayoutParams(dp(40),dp(46),Gravity.CENTER));
        TextView pinLabel=text(label(kind),13,0xFF10284C,true);
        pinLabel.setPadding(dp(10),dp(5),dp(10),dp(5));
        pinLabel.setBackground(gradient(Color.WHITE,0xFFF1F7FE,16));
        pinLabel.setElevation(dp(2));
        pinLabel.setTranslationY(-dp(63));
        viewport.addView(pinLabel,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
        locate=button("⊙  Ambil lokasi",0xFF124480);
        locate.setBackground(gradient(Color.WHITE,0xFFF1F7FE,21));
        locate.setElevation(dp(3));
        locate.setEnabled(false);
        locate.setOnClickListener(v->requestLocation());
        FrameLayout.LayoutParams locationParams=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.END);
        locationParams.setMargins(dp(10),0,dp(12),dp(15));
        viewport.addView(locate,locationParams);
        page.addView(viewport,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout sheet=new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(14),dp(10),dp(14),dp(10));
        sheet.setBackground(gradient(Color.WHITE,0xFFF8FBFF,16));
        TextView pointLabel=text(label(kind),12,pinColor(kind),true);
        sheet.addView(pointLabel);
        address=text("Menyiapkan peta…",16,0xFF10284C,true);
        address.setMaxLines(2);
        sheet.addView(address);
        TextView hint=text("Geser peta untuk menyesuaikan titik.",13,0xFF405975,false);
        hint.setPadding(0,dp(2),0,dp(8));
        sheet.addView(hint);
        routeUpdate=button("Rute diperbarui · Tinjau",0xFF124480);
        routeUpdate.setBackground(gradient(0xFFE0F0FF,0xFFF0F7FF,12));
        routeUpdate.setOnClickListener(v->reviewRoute());
        routeUpdate.setVisibility(latestRoute==null?View.GONE:View.VISIBLE);
        sheet.addView(routeUpdate);
        usePoint=button("✓  Pakai Titik Ini",Color.WHITE);
        usePoint.setBackground(gradient(0xFF0735B7,0xFF0879EB,13));
        usePoint.setEnabled(false);
        usePoint.setAlpha(.55f);
        usePoint.setOnClickListener(v->confirm());
        sheet.addView(usePoint,new LinearLayout.LayoutParams(-1,-2));
        page.addView(sheet);
        setContentView(page);
    }

    private static JSONObject parse(String raw) {
        try { return raw==null?null:new JSONObject(raw); }
        catch (org.json.JSONException ignored) { return null; }
    }

    private void updateSavedMarkers() {
        if (googleMap==null) return;
        for (com.google.android.gms.maps.model.Marker marker:sharedMarkers) marker.remove();
        sharedMarkers.clear();
        for (String type:new String[]{"pickup","delivery","stop"}) {
            if (type.equals(kind)) continue;
            JSONObject point=baseRoute==null?parse(getIntent().getStringExtra("saved_"+type)):baseRoute.optJSONObject(type);
            if (point==null) continue;
            double lat=point.optDouble("lat"),lng=point.optDouble("lng");
            if (!valid(lat,lng)) continue;
            Bitmap image=Bitmap.createScaledBitmap(pin(type),dp(28),dp(32),true);
            com.google.android.gms.maps.model.Marker marker=googleMap.addMarker(new MarkerOptions()
                    .position(new LatLng(lat,lng)).anchor(.5f,1f).title(label(type))
                    .snippet(point.optString("address")).icon(BitmapDescriptorFactory.fromBitmap(image)));
            if (marker!=null) sharedMarkers.add(marker);
        }
    }

    private void loadRoute() {
        if (!routeVisible || routeReading || routeClosed || confirming || baseRoute==null || contactId==null) return;
        routeReading=true;
        if (!routeNetwork.execute(()->{
            JSONObject reply=null;
            try {
                reply=DriverMessageApi.post(new SessionManager(this),DriverApiConfig.BASE_URL+"trans_simpel.php",
                        new JSONObject().put("action","get").put("contact_id",contactId),routeNetwork.owner);
            } catch (Exception ignored) { }
            JSONObject result=reply;
            routeNetwork.post(()->routeLoaded(result));
        })) routeLoaded(null);
    }

    private void routeLoaded(JSONObject reply) {
        routeReading=false;
        if (!routeVisible || isFinishing() || isDestroyed() || confirming) return;
        JSONObject snapshot=reply==null?null:reply.optJSONObject("route");
        if (snapshot!=null && reply.optBoolean("success")) {
            if (!"accepted".equals(snapshot.optString("state"))) {
                routeClosed=true;
                routeUpdate.setVisibility(View.VISIBLE);
                routeUpdate.setText("Rute tidak dapat diubah");
                routeUpdate.setEnabled(false);
                updateConfirmButton();
                return;
            }
            if (snapshot.optInt("revision")>baseRoute.optInt("revision")
                    && (latestRoute==null || snapshot.optInt("revision")>=latestRoute.optInt("revision"))) {
                latestRoute=snapshot;
                routeUpdate.setVisibility(View.VISIBLE);
                updateConfirmButton();
            }
        }
        main.postDelayed(routePoll,4500L);
    }

    private boolean pointChanged() {
        if (latestRoute==null || baseRoute==null) return false;
        JSONObject oldPoint=baseRoute.optJSONObject(kind), newPoint=latestRoute.optJSONObject(kind);
        if (oldPoint==null || newPoint==null) return oldPoint!=newPoint;
        return oldPoint.optDouble("lat")!=newPoint.optDouble("lat")
                || oldPoint.optDouble("lng")!=newPoint.optDouble("lng")
                || !oldPoint.optString("address").equals(newPoint.optString("address"));
    }

    private void reviewRoute() {
        if (latestRoute==null || routeClosed || confirming) return;
        new android.app.AlertDialog.Builder(this).setTitle("Rute diperbarui")
                .setMessage(pointChanged()?"Titik ini diubah dari perangkat lain. Pilih titik terbaru atau pertahankan pratinjau Anda."
                        :"Titik lain di rute telah berubah. Pratinjau Anda tetap tersedia.")
                .setNegativeButton("Pertahankan pratinjau",(dialog,which)->adoptRoute(false))
                .setPositiveButton("Muat titik terbaru",(dialog,which)->adoptRoute(true)).show();
    }

    private void adoptRoute(boolean center) {
        if (latestRoute==null || routeClosed || confirming) return;
        baseRoute=latestRoute;
        latestRoute=null;
        routeUpdate.setVisibility(View.GONE);
        updateSavedMarkers();
        JSONObject point=baseRoute.optJSONObject(kind);
        if (center && googleMap!=null && point!=null && valid(point.optDouble("lat"),point.optDouble("lng"))) {
            LatLng target=new LatLng(point.optDouble("lat"),point.optDouble("lng"));
            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(target,ZOOM));
            if (!cameraMoving) updateSelection(target);
        }
        updateConfirmButton();
    }

    private void updateConfirmButton() {
        boolean enabled=selected!=null && !cameraMoving && !confirming && !routeClosed && !pointChanged();
        usePoint.setEnabled(enabled);
        usePoint.setAlpha(enabled?1f:.55f);
    }

    private void invalidateAddress() {
        selectionVersion++;
        if (addressDebounce!=null) main.removeCallbacks(addressDebounce);
        if (addressRead!=null) addressRead.cancel(true);
    }

    private void updateSelection(LatLng point) {
        if (confirming || !valid(point.latitude,point.longitude)) return;
        invalidateAddress();
        selected=point;
        resolved="";
        address.setText(coordinates(point));
        updateConfirmButton();
        locate.setEnabled(locationRequest==null);
        int version=selectionVersion;
        addressDebounce=()->addressRead=geocoder.submit(()->{
            String found="";
            try {
                List<Address> matches=new Geocoder(this,new Locale("id","ID"))
                        .getFromLocation(point.latitude,point.longitude,1);
                if (matches!=null && !matches.isEmpty()) found=matches.get(0).getAddressLine(0);
            } catch (Exception ignored) {}
            String ready=found==null?"":found.trim();
            main.post(()->{
                if (version!=selectionVersion || isFinishing() || isDestroyed()) return;
                resolved=ready;
                if (!resolved.isEmpty()) address.setText(resolved);
                if (confirming) finishPoint(point,resolved.isEmpty()?coordinates(point):resolved);
            });
        });
        main.postDelayed(addressDebounce,350L);
    }

    private void confirm() {
        if (confirming || selected==null || !usePoint.isEnabled()) return;
        cancelLocation();
        confirming=true;
        LatLng point=selected;
        googleMap.getUiSettings().setAllGesturesEnabled(false);
        if (android.os.Build.VERSION.SDK_INT<26 || android.animation.ValueAnimator.areAnimatorsEnabled()) {
            landingAt=android.os.SystemClock.uptimeMillis()+200L;
            selectionShadow.animate().scaleX(1.24f).setDuration(180).start();
            selectionPin.animate().translationY(-dp(23)).setDuration(195)
                    .setInterpolator(new android.view.animation.BounceInterpolator()).start();
        }
        if (!resolved.isEmpty()) {
            finishPoint(point,resolved);
            return;
        }
        usePoint.setEnabled(false);
        locate.setEnabled(false);
        usePoint.setText("Menentukan alamat…");
        main.postDelayed(()->finishPoint(point,coordinates(point)),2500L);
    }

    private void finishPoint(LatLng point,String resultAddress) {
        if (isFinishing() || isDestroyed()) return;
        long delay=landingAt-android.os.SystemClock.uptimeMillis();
        if (delay>0) {
            main.postDelayed(()->finishPoint(point,resultAddress),delay);
            return;
        }
        Intent result=new Intent().putExtra("lat",point.latitude).putExtra("lng",point.longitude)
                .putExtra("address",resultAddress);
        if (baseRoute!=null) {
            JSONObject previous=baseRoute.optJSONObject(kind);
            result.putExtra("route_revision",baseRoute.optInt("revision"))
                    .putExtra("route_previous",previous==null?"":previous.toString());
        }
        setResult(RESULT_OK,result);
        finish();
    }

    private void requestLocation() {
        if (googleMap==null || confirming || locationRequest!=null) return;
        boolean fine=checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
        if (!fine && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},LOCATION_PERMISSION);
            return;
        }
        CancellationTokenSource request=new CancellationTokenSource();
        locationRequest=request;
        locate.setEnabled(false);
        CurrentLocationRequest query=new CurrentLocationRequest.Builder()
                .setPriority(fine?Priority.PRIORITY_HIGH_ACCURACY:Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .setDurationMillis(8000).setMaxUpdateAgeMillis(8000).build();
        try {
            LocationServices.getFusedLocationProviderClient(this).getCurrentLocation(query,request.getToken())
                    .addOnCompleteListener(task->{
                        if (request!=locationRequest || isFinishing() || isDestroyed()) return;
                        cancelLocation();
                        android.location.Location location=task.isSuccessful()?task.getResult():null;
                        if (location!=null && valid(location.getLatitude(),location.getLongitude())) {
                            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(
                                    new LatLng(location.getLatitude(),location.getLongitude()),ZOOM));
                            updateSelection(googleMap.getCameraPosition().target);
                        } else showLocationError();
                    });
        } catch (SecurityException error) { cancelLocation(); showLocationError(); }
    }

    private void showLocationError() {
        Toast.makeText(this,"Lokasi belum ditemukan. Geser peta untuk memilih titik.",Toast.LENGTH_SHORT).show();
    }

    private void cancelLocation() {
        CancellationTokenSource previous=locationRequest;
        locationRequest=null;
        if (previous!=null) previous.cancel();
        if (locate!=null) locate.setEnabled(googleMap!=null && !confirming);
    }

    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if (request==LOCATION_PERMISSION && results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED) requestLocation();
    }

    private static String coordinates(LatLng p) {
        return String.format(Locale.US,"%.6f, %.6f",p.latitude,p.longitude);
    }

    private static boolean valid(double lat,double lng) {
        return Math.abs(lat)<=90 && Math.abs(lng)<=180 && lat!=0 && lng!=0;
    }

    private static String label(String type) {
        return "delivery".equals(type)?"Titik antar":"stop".equals(type)?"Titik singgah":"Titik jemput";
    }

    private static int pinColor(String type) {
        return "delivery".equals(type)?0xFFDA382E:"stop".equals(type)?0xFF9A5707:0xFF08785B;
    }

    private Bitmap pin(String type) {
        Bitmap bitmap=Bitmap.createBitmap(dp(40),dp(46),Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(bitmap);
        canvas.scale(bitmap.getWidth()/40f,bitmap.getHeight()/46f);
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(pinColor(type));
        Path shape=new Path();
        shape.moveTo(20,46);
        shape.cubicTo(15,38,2,29,2,19);
        shape.cubicTo(2,-4,38,-4,38,19);
        shape.cubicTo(38,29,25,38,20,46);
        canvas.drawPath(shape,paint);
        paint.setColor(Color.WHITE);
        if ("delivery".equals(type)) {
            canvas.drawRect(12,10,14,28,paint);
            for (int row=0;row<3;row++) {
                for (int col=0;col<3;col++) {
                    if ((row+col)%2==0) canvas.drawRect(15+4*col,11+4*row,19+4*col,15+4*row,paint);
                }
            }
        } else if ("stop".equals(type)) {
            canvas.drawRoundRect(15,12,25,25,2,2,paint);
        } else {
            canvas.drawCircle(20,13,4,paint);
            canvas.drawRoundRect(13,19,27,27,4,4,paint);
        }
        return bitmap;
    }

    private Button button(String title,int color) {
        Button button=new Button(this);
        button.setAllCaps(false);
        button.setText(title);
        button.setTextColor(color);
        button.setTextSize(15);
        button.setMinHeight(dp(48));
        return button;
    }

    private TextView text(String title,int size,int color,boolean bold) {
        TextView view=new TextView(this);
        view.setText(title);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(null,android.graphics.Typeface.BOLD);
        return view;
    }

    private GradientDrawable gradient(int start,int end,int radius) {
        GradientDrawable bg=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{start,end});
        bg.setCornerRadius(dp(radius));
        return bg;
    }

    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density*value); }

    @Override protected void onStart() { super.onStart(); mapView.onStart(); }
    @Override protected void onResume() { super.onResume(); mapView.onResume(); routeVisible=true; loadRoute(); }
    @Override protected void onPause() {
        routeVisible=false;
        main.removeCallbacks(routePoll);
        cancelLocation();
        mapView.onPause();
        super.onPause();
    }
    @Override protected void onStop() { mapView.onStop(); super.onStop(); }
    @Override public void onLowMemory() { super.onLowMemory(); mapView.onLowMemory(); }
    @Override protected void onDestroy() {
        invalidateAddress();
        main.removeCallbacksAndMessages(null);
        geocoder.shutdownNow();
        routeNetwork.close();
        mapView.onDestroy();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        Bundle map=new Bundle();
        mapView.onSaveInstanceState(map);
        out.putBundle("simpel_map",map);
        if (baseRoute!=null) out.putString("route_base",baseRoute.toString());
        if (latestRoute!=null) out.putString("route_latest",latestRoute.toString());
        if (googleMap!=null) {
            LatLng point=googleMap.getCameraPosition().target;
            if (selected!=null) {
                out.putDouble("lat",point.latitude);
                out.putDouble("lng",point.longitude);
            }
        }
    }
}
