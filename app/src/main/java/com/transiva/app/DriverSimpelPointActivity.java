package com.transiva.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.location.Address;
import android.location.Geocoder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.model.LatLng;

import org.json.JSONObject;
import java.util.List;
import java.util.Locale;

public final class DriverSimpelPointActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private DriverUiScope scope;
    private MapView mapView;
    private LatLng center;
    private Button use;
    private boolean resolving;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        scope=new DriverUiScope(this);
        LinearLayout content=new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        TextView title=new TextView(this);
        title.setText(getIntent().getStringExtra("title"));
        title.setTextSize(19);
        title.setPadding(dp(16),dp(12),dp(16),dp(12));
        content.addView(title);
        FrameLayout viewport=new FrameLayout(this);
        content.addView(viewport,new LinearLayout.LayoutParams(-1,0,1));
        mapView=new MapView(this);
        viewport.addView(mapView,new FrameLayout.LayoutParams(-1,-1));
        String kind=getIntent().getStringExtra("point_kind");
        ImageView pin=new ImageView(this);
        if ("stop".equals(kind)) {
            pin.setImageResource(R.drawable.map_destination_pin);
            pin.setColorFilter(Color.rgb(174,104,12));
        } else {
            pin.setImageResource("delivery".equals(kind)?R.drawable.map_destination_pin:R.drawable.map_pickup_pin);
        }
        pin.setScaleType(ImageView.ScaleType.FIT_CENTER);
        pin.setTranslationY(-dp(20));
        viewport.addView(pin,new FrameLayout.LayoutParams(dp(40),dp(40),Gravity.CENTER));
        use=new Button(this);
        use.setAllCaps(false);
        use.setText("Pakai Titik Ini");
        use.setEnabled(false);
        use.setOnClickListener(v->confirm());
        content.addView(use,new LinearLayout.LayoutParams(-1,-2));
        setContentView(content);
        DriverAppSettings.apply(this);
        JSONObject location=new SessionManager(this).getLastLocationJson();
        double lat=saved==null?getIntent().getDoubleExtra("lat",location.optDouble("latitude",-0.9003)):saved.getDouble("lat");
        double lng=saved==null?getIntent().getDoubleExtra("lng",location.optDouble("longitude",119.8779)):saved.getDouble("lng");
        LatLng seed=valid(lat,lng)?new LatLng(lat,lng):new LatLng(-0.9003,119.8779);
        mapView.onCreate(saved==null?null:saved.getBundle("simpel_map"));
        mapView.getMapAsync(map->{
            if (isFinishing()||isDestroyed()) return;
            map.getUiSettings().setMapToolbarEnabled(false);
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(seed,16f));
            map.setOnCameraMoveStartedListener(reason->use.setEnabled(false));
            map.setOnCameraIdleListener(()->{
                center=map.getCameraPosition().target;
                use.setEnabled(!resolving && valid(center.latitude,center.longitude));
            });
            center=map.getCameraPosition().target;
            use.setEnabled(valid(center.latitude,center.longitude));
        });
    }

    private void confirm() {
        if (resolving||center==null) return;
        resolving=true;
        use.setEnabled(false);
        LatLng point=center;
        String fallback=String.format(Locale.US,"%.6f, %.6f",point.latitude,point.longitude);
        main.postDelayed(()->finishPoint(point,fallback),2800);
        if (!scope.execute(()->{
            String address=fallback;
            try {
                List<Address> found=new Geocoder(this,new Locale("id","ID"))
                        .getFromLocation(point.latitude,point.longitude,1);
                if (found!=null&&!found.isEmpty()&&found.get(0).getAddressLine(0)!=null)
                    address=found.get(0).getAddressLine(0);
            } catch (Exception ignored) { }
            String selected=address;
            scope.post(()->finishPoint(point,selected));
        })) finishPoint(point,fallback);
    }

    private void finishPoint(LatLng point,String address) {
        if (isFinishing()||isDestroyed()) return;
        main.removeCallbacksAndMessages(null);
        setResult(RESULT_OK,new Intent().putExtra("lat",point.latitude)
                .putExtra("lng",point.longitude).putExtra("address",address));
        finish();
    }

    private static boolean valid(double lat,double lng) {
        return Math.abs(lat)<=90 && Math.abs(lng)<=180 && lat!=0 && lng!=0;
    }

    private int dp(int n) { return Math.round(getResources().getDisplayMetrics().density*n); }
    @Override protected void onStart() {super.onStart();mapView.onStart();}
    @Override protected void onResume() {super.onResume();mapView.onResume();}
    @Override protected void onPause() {mapView.onPause();super.onPause();}
    @Override protected void onStop() {mapView.onStop();super.onStop();}
    @Override public void onLowMemory() {super.onLowMemory();mapView.onLowMemory();}
    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        scope.close();
        mapView.onDestroy();
        super.onDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        Bundle map=new Bundle();
        mapView.onSaveInstanceState(map);
        out.putBundle("simpel_map",map);
        if (center!=null) {out.putDouble("lat",center.latitude);out.putDouble("lng",center.longitude);}
    }
}
