package com.transiva.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.SystemClock;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.tasks.CancellationTokenSource;
import org.json.JSONObject;

import java.util.Locale;

final class DriverLocationProposal {
    private DriverLocationProposal() {}

    static String pointFor(JSONObject order) {
        String status=DriverOrderStateMachine.normalize(order.optString("status"));
        if ("taken".equals(status)) return "pickup";
        if ("on_delivery".equals(status)) return "delivery";
        return "";
    }

    static void show(Activity activity, SessionManager session, JSONObject order, Location latest, boolean stop) {
        if (session==null || order==null || activity.isFinishing()) return;
        String phase=pointFor(order);
        if (phase.isEmpty() || (stop && !"delivery".equals(phase))) {
            message(activity,"Perubahan lokasi tersedia saat menuju titik jemput atau tujuan. Singgah bisa ditambah saat mengantar.");
            return;
        }
        if ("pending".equals(order.optString("price_change_status"))) {
            message(activity,"Tunggu konfirmasi customer atas usulan sebelumnya.");
            return;
        }
        String source=NavigationTargetPolicy.source(order);
        String orderId=order.optString("order_id");
        if (orderId.isEmpty()) return;
        String kind=stop?"stop":phase;
        double lat=order.optDouble(phase+"_lat");
        double lng=order.optDouble(phase+"_lng");
        if (!Double.isFinite(lat) || !Double.isFinite(lng)) {
            message(activity,"Titik awal belum tersedia.");
            return;
        }
        String title=stop?"Tambah Singgah":"Perbaiki Lokasi "+("pickup".equals(phase)?"Jemput":"Tujuan");
        LinearLayout body=new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(activity,14),dp(activity,4),dp(activity,14),dp(activity,8));
        TextView status=new TextView(activity);
        status.setText("Geser marka atau ketuk titik pada peta.");
        status.setPadding(0,0,0,dp(activity,8));
        body.addView(status,new LinearLayout.LayoutParams(-1,-2));
        MapView map=new MapView(activity);
        map.onCreate(null);
        body.addView(map,new LinearLayout.LayoutParams(-1,Math.min(dp(activity,300),
                activity.getResources().getDisplayMetrics().heightPixels/3)));
        Button gps=new Button(activity);
        gps.setText("◎  Lokasi GPS saya");
        gps.setEnabled(false);
        body.addView(gps,new LinearLayout.LayoutParams(-1,dp(activity,48)));
        android.widget.EditText address=new android.widget.EditText(activity);
        address.setSingleLine(true);
        address.setHint("Patokan alamat (opsional)");
        address.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(165)});
        body.addView(address,new LinearLayout.LayoutParams(-1,-2));
        android.widget.ScrollView scroll=new android.widget.ScrollView(activity);
        scroll.addView(body);
        AlertDialog dialog=PremiumDialogs.builder(activity).setTitle(title).setView(scroll)
                .setNegativeButton("Batal",null).setPositiveButton("Kirim usulan",null).create();
        CancellationTokenSource gpsRequest=new CancellationTokenSource();
        dialog.setOnDismissListener(ignored->{
            gpsRequest.cancel();
            map.onPause(); map.onStop(); map.onDestroy();
        });
        dialog.show();
        map.onStart();map.onResume();
        Button send=dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        send.setEnabled(false);
        map.getMapAsync(google->{
            if (!dialog.isShowing() || activity.isDestroyed()) return;
            google.getUiSettings().setMapToolbarEnabled(false);
            LatLng initial=new LatLng(lat,lng);
            Marker marker=google.addMarker(new MarkerOptions().position(initial).title(title).draggable(true));
            if (marker==null) return;
            google.moveCamera(CameraUpdateFactory.newLatLngZoom(initial,16f));
            google.setOnMapClickListener(point->{marker.setPosition(point);send.setEnabled(true);});
            google.setOnMarkerDragListener(new com.google.android.gms.maps.GoogleMap.OnMarkerDragListener() {
                public void onMarkerDragStart(Marker moving) { send.setEnabled(false); }
                public void onMarkerDrag(Marker moving) {}
                public void onMarkerDragEnd(Marker moving) { send.setEnabled(true); }
            });
            gps.setEnabled(true);
            java.util.function.Consumer<Location> move=fix->{
                if (!dialog.isShowing()) return;
                gps.setEnabled(true);
                if (fix==null || !fix.hasAccuracy() || fix.getAccuracy()>80) { status.setText("Lokasi GPS belum tersedia. Coba lagi atau geser marka."); return; }
                LatLng point=new LatLng(fix.getLatitude(),fix.getLongitude());
                marker.setPosition(point);
                google.animateCamera(CameraUpdateFactory.newLatLngZoom(point,17f));
                status.setText("Marka mengikuti lokasi GPS Anda.");
                send.setEnabled(true);
            };
            gps.setOnClickListener(view->{
                if (latest!=null && SystemClock.elapsedRealtimeNanos()-latest.getElapsedRealtimeNanos()<12_000_000_000L
                        && latest.hasAccuracy() && latest.getAccuracy()<=80) {
                    move.accept(latest);
                } else if (activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
                        || activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED) {
                    gps.setEnabled(false);
                    try {
                        LocationServices.getFusedLocationProviderClient(activity)
                                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY,gpsRequest.getToken())
                                .addOnSuccessListener(move::accept).addOnFailureListener(error->move.accept(null));
                    } catch (SecurityException denied) { move.accept(null); }
                } else status.setText("Aktifkan izin lokasi untuk memakai GPS.");
            });
            send.setOnClickListener(view->{
                LatLng point=marker.getPosition();
                String typed=address.getText().toString().trim();
                String label=typed.isEmpty()?String.format(Locale.US,"%.6f, %.6f",point.latitude,point.longitude):typed;
                send.setEnabled(false);gps.setEnabled(false);
                status.setText("Menghitung tarif dan mengirim usulan…");
                boolean queued=DriverNetworkExecutor.executeOrderAction(()->{
                    try {
                        JSONObject request=new JSONObject().put("order_id",orderId).put("source",source)
                                .put("point",kind).put("latitude",point.latitude).put("longitude",point.longitude).put("address",label);
                        com.transiva.app.driver.data.DriverApiClient client=new com.transiva.app.driver.data.DriverApiClient(session);
                        JSONObject result;
                        try { result=client.post("driver_propose_location.php",request).body; }
                        finally { client.shutdown(); }
                        activity.runOnUiThread(()->{
                            if(activity.isFinishing() || activity.isDestroyed()) return;
                            if(dialog.isShowing()) dialog.dismiss();
                            message(activity,result.optString("message","Usulan dikirim."));
                        });
                    } catch (Exception error) {
                        activity.runOnUiThread(()->{
                            if (!dialog.isShowing()) return;
                            status.setText(error.getMessage()==null?"Usulan belum terkirim. Coba lagi.":error.getMessage());
                            send.setEnabled(true);gps.setEnabled(true);
                        });
                    }
                });
                if (!queued) { status.setText("Koneksi sedang sibuk. Coba lagi.");send.setEnabled(true);gps.setEnabled(true); }
            });
        });
    }

    private static void message(Activity activity,String message) {
        PremiumDialogs.builder(activity).setTitle("Perjalanan").setMessage(message).setPositiveButton("OK",null).show();
    }

    private static int dp(Activity activity,int value) {
        return Math.round(value*activity.getResources().getDisplayMetrics().density);
    }
}
