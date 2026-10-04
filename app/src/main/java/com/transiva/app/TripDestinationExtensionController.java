package com.transiva.app;

import android.app.AlertDialog;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;
import org.json.JSONObject;

/** Server-priced proposals. No local mutation until an authenticated customer approves. */
final class TripDestinationExtensionController {
    private final DriverTripActivityLayer1 host;
    private AlertDialog picker;
    TripDestinationExtensionController(DriverTripActivityLayer1 host) { this.host = host; }
    void close() { if (picker != null) { picker.dismiss(); picker = null; } }
    private boolean alive() { return !host.isFinishing() && !host.isDestroyed(); }
    void show() {
        if (host.order == null || host.session == null) return;
        if ("pending".equals(host.order.optString("price_change_status"))) {
            host.info("Tujuan tambahan", "Tunggu customer menyetujui atau menolak pengajuan sebelumnya."); return;
        }
        if (host.extensionNeedsArrival()) {
            host.info("Tujuan tambahan", "Tiba di tujuan tambahan sebelumnya terlebih dahulu."); return;
        }
        final LatLng[] chosen = { null };
        LinearLayout body = new LinearLayout(host); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(host.dp(16), host.dp(8), host.dp(16), host.dp(12));
        TextView hint = new TextView(host); hint.setText("Tekan lama pada peta untuk memilih tujuan setelah tujuan terakhir, atau pilih kembali ke titik jemput.");
        hint.setTextSize(14); body.addView(hint);
        MapView map = new MapView(host){
            @Override public boolean dispatchTouchEvent(android.view.MotionEvent event){
                if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(event.getAction()!=android.view.MotionEvent.ACTION_UP && event.getAction()!=android.view.MotionEvent.ACTION_CANCEL);
                return super.dispatchTouchEvent(event);
            }
        }; map.onCreate(null);
        int mapHeight = Math.min(host.dp(280), host.getResources().getDisplayMetrics().heightPixels / 3);
        body.addView(map, new LinearLayout.LayoutParams(-1, mapHeight));
        Button home = new Button(host); home.setText("Kembali ke titik jemput (PP)");body.addView(home);
        TextView location = new TextView(host);location.setText("Belum ada titik yang dipilih");location.setTextSize(12);body.addView(location);
        EditText address = new EditText(host);address.setHint("Nama tempat / alamat tujuan baru");address.setMinLines(1);address.setMaxLines(3);
        address.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(255)});body.addView(address);
        ScrollView scroll = new ScrollView(host);scroll.setFillViewport(true);scroll.addView(body);
        picker = new AlertDialog.Builder(host).setTitle("Tambah tujuan / perjalanan PP").setView(scroll)
            .setNegativeButton("Batal", null).setPositiveButton("Hitung ongkir", null).create();
        picker.setOnDismissListener(v -> { map.onPause();map.onStop();map.onDestroy(); });
        picker.setOnShowListener(v -> picker.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w -> {
            if(chosen[0] == null) { location.setText("Tekan lama pada peta untuk memilih titik.");return; }
            String label = address.getText().toString().trim();if(label.isEmpty()){address.setError("Isi nama tempat atau alamat");return;}
            LatLng target=chosen[0];close();quote(target,label);
        }));
        map.getMapAsync(g -> {
            if(!alive() || picker==null)return;
            double lat=host.coord("delivery_lat","destination_lat"),lng=host.coord("delivery_lng","destination_lng");
            if(host.valid(lat,lng)){LatLng finish=new LatLng(lat,lng);g.moveCamera(CameraUpdateFactory.newLatLngZoom(finish,15));g.addMarker(new MarkerOptions().position(finish).title("Tujuan terakhir saat ini"));}
            g.setOnMapLongClickListener(point -> {
                chosen[0]=point;g.clear();g.addMarker(new MarkerOptions().position(point).title("Tujuan tambahan"));
                location.setText(String.format(java.util.Locale.US,"Titik: %.6f, %.6f",point.latitude,point.longitude));
            });
            home.setOnClickListener(v -> {
                double a=host.coord("pickup_lat","user_lat"),b=host.coord("pickup_lng","user_lng");
                if(!host.valid(a,b)){location.setText("Titik jemput tidak tersedia");return;}
                chosen[0]=new LatLng(a,b);g.clear();g.addMarker(new MarkerOptions().position(chosen[0]).title("Kembali ke titik jemput"));
                g.animateCamera(CameraUpdateFactory.newLatLngZoom(chosen[0],15));address.setText(host.pickupAddress());location.setText("Tujuan pulang: titik jemput awal");
            });
        });
        picker.show();map.onStart();map.onResume();
    }
    private void quote(LatLng point,String label) {
        host.setLoading(true);
        DriverNetworkExecutor.execute(() -> {
            try {
                JSONObject q=new JSONObject().put("id",Integer.parseInt(host.internalId())).put("action","quote")
                    .put("latitude",point.latitude).put("longitude",point.longitude).put("address",label);
                JSONObject r=new com.transiva.app.driver.data.DriverApiClient(host.session).post("driver_destination_extension.php",q).body;
                host.mainHandler.post(() -> {
                    if(!alive())return;host.setLoading(false);
                    PremiumDialogs.builder(host).setTitle("Konfirmasi pengajuan tujuan")
                        .setMessage("Tujuan baru: "+r.optString("address")+"\nJarak tambahan: "+host.one(r.optDouble("distance_km"))+" km\nOngkir tambahan: "+host.rupiah(r.optDouble("extra_fare"))+"\nTotal baru: "+host.rupiah(r.optDouble("total"))+"\n\nTujuan dan biaya berlaku setelah customer menyetujui. Tarif segmen tambahan mengikuti tarif reguler wilayah.")
                        .setNegativeButton("Batal",null).setPositiveButton("Kirim ke customer",(v,w)->send("request",r.optLong("request_id"))).show();
                });
            } catch(Exception e) { failed(e); }
        });
    }
    void arrive() {
        JSONObject r=host.order==null?null:host.order.optJSONObject("destination_extension");
        if(r!=null)send("arrive",r.optLong("id"));
    }
    private void send(String action,long requestId) {
        host.setLoading(true);
        DriverNetworkExecutor.execute(() -> {
            try {
                JSONObject q=new JSONObject().put("id",Integer.parseInt(host.internalId())).put("action",action).put("request_id",requestId);
                if("arrive".equals(action))q.put("driver_lat",host.lastDriverLat).put("driver_lng",host.lastDriverLng);
                JSONObject r=new com.transiva.app.driver.data.DriverApiClient(host.session).post("driver_destination_extension.php",q).body;
                JSONObject fresh=null;
                try{fresh=new com.transiva.app.driver.data.DriverApiClient(host.session).post("driver_trip_realtime.php",new JSONObject().put("order_id",host.orderId())).body.optJSONObject("order");}catch(Exception ignored){}
                final JSONObject snapshot=fresh;
                host.mainHandler.post(() -> {
                    if(!alive())return;host.setLoading(false);
                    if(snapshot!=null){host.order=snapshot;host.saveActiveOrder();host.renderOrder();host.refreshButtons();}
                    host.info("Tujuan tambahan",r.optString("message","Berhasil. Perjalanan akan diperbarui."));
                });
            }catch(Exception e){failed(e);}
        });
    }
    private void failed(Exception e) {
        host.mainHandler.post(() -> {if(alive()){host.setLoading(false);host.info("Tujuan tambahan",host.first(e.getMessage(),"Koneksi bermasalah. Coba lagi."));}});
    }
}
