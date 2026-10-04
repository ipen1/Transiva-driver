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
    private TextView pickerStatus;
    private EditText pickerAddress;
    private boolean quotePending;
    private long generation,selectionVersion;
    private Runnable lookup=()->{};
    TripDestinationExtensionController(DriverTripActivityLayer1 host) { this.host = host; }
    void close() { if (picker != null) { picker.dismiss(); picker = null; } }
    private boolean alive() { return !host.isFinishing() && !host.isDestroyed(); }
    void show() {
        if(picker!=null && picker.isShowing())return;
        if (host.order == null || host.session == null) return;
        if ("pending".equals(host.order.optString("price_change_status"))) {
            host.info("Tujuan tambahan", "Tunggu customer menyetujui atau menolak pengajuan sebelumnya."); return;
        }
        if (host.extensionNeedsArrival()) {
            host.info("Tujuan tambahan", "Tiba di tujuan tambahan sebelumnya terlebih dahulu."); return;
        }
        final LatLng[] chosen={null};final LatLng[] preset={null};
        final long pickerVersion=++generation;
        LinearLayout body=new LinearLayout(host);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(host.dp(12),host.dp(8),host.dp(12),host.dp(10));body.setBackgroundColor(DriverThemeTokens.surface(host));
        TextView hint=new TextView(host);hint.setText("Geser peta. Pin tengah menentukan tujuan baru.");hint.setTextSize(13);hint.setTextColor(DriverThemeTokens.textSecondary(host));body.addView(hint);
        MapView map=new MapView(host){
            @Override public boolean dispatchTouchEvent(android.view.MotionEvent event){
                if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(event.getAction()!=android.view.MotionEvent.ACTION_UP && event.getAction()!=android.view.MotionEvent.ACTION_CANCEL);
                return super.dispatchTouchEvent(event);
            }
        };map.onCreate(null);
        android.widget.FrameLayout mapFrame=new android.widget.FrameLayout(host);
        mapFrame.addView(map,new android.widget.FrameLayout.LayoutParams(-1,-1));
        TextView pin=new TextView(host);pin.setText("▼");pin.setTextSize(32);pin.setTextColor(android.graphics.Color.RED);pin.setGravity(Gravity.CENTER);pin.setTranslationY(-host.dp(15));pin.setContentDescription("Titik tujuan di tengah peta");
        android.widget.FrameLayout.LayoutParams pinLp=new android.widget.FrameLayout.LayoutParams(host.dp(44),host.dp(44));pinLp.gravity=Gravity.CENTER;mapFrame.addView(pin,pinLp);
        body.addView(mapFrame,new LinearLayout.LayoutParams(-1,Math.min(host.dp(300),host.getResources().getDisplayMetrics().heightPixels/3)));
        Button home=new Button(host);home.setText("Kembali ke titik jemput (PP)");home.setTextColor(DriverThemeTokens.accent(host));body.addView(home);
        TextView location=new TextView(host);pickerStatus=location;location.setText("Menyiapkan peta…");location.setTextSize(12);location.setTextColor(DriverThemeTokens.textSecondary(host));body.addView(location);
        EditText address=new EditText(host);pickerAddress=address;address.setHint("Alamat otomatis • dapat diperbaiki");address.setTextColor(DriverThemeTokens.textPrimary(host));address.setHintTextColor(DriverThemeTokens.hint(host));address.setMaxLines(3);address.setTextSize(14);
        address.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(255)});body.addView(address);
        ScrollView scroll=new ScrollView(host);scroll.setFillViewport(true);scroll.addView(body);
        TextView title=new TextView(host);title.setText("Tujuan baru / Pulang (PP)");title.setTextSize(18);title.setTextColor(DriverThemeTokens.textPrimary(host));title.setPadding(host.dp(16),host.dp(16),host.dp(16),host.dp(8));title.setBackgroundColor(DriverThemeTokens.surface(host));
        picker=new AlertDialog.Builder(host).setCustomTitle(title).setView(scroll).setNegativeButton("Batal",null).setPositiveButton("Hitung ongkir",null).create();
        picker.setOnDismissListener(v->{generation++;quotePending=false;host.setLoading(false);host.mainHandler.removeCallbacks(lookup);map.onPause();map.onStop();map.onDestroy();picker=null;});
        picker.setOnShowListener(v->{
            picker.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(DriverThemeTokens.accent(host));
            picker.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(DriverThemeTokens.accent(host));
            picker.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            picker.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{
                if(chosen[0]==null)return;
                String label=address.getText().toString().trim();if(label.isEmpty()){address.setError("Alamat belum tersedia. Isi alamat atau tunggu pencarian.");return;}
                if(quotePending)return;LatLng target=chosen[0];quote(target,label);
            });
        });
        address.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){if(picker!=null && picker.getButton(AlertDialog.BUTTON_POSITIVE)!=null)picker.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!quotePending && chosen[0]!=null && s.toString().trim().length()>0);}
            public void afterTextChanged(android.text.Editable s){}
        });
        map.getMapAsync(g->{
            if(!alive()||picker==null||generation!=pickerVersion)return;
            g.getUiSettings().setMapToolbarEnabled(false);
            g.setOnCameraMoveStartedListener(reason->{selectionVersion++;host.mainHandler.removeCallbacks(lookup);chosen[0]=null;address.setText("");location.setText("Geser peta untuk menentukan tujuan…");});
            g.setOnCameraIdleListener(()->{
                final LatLng point=g.getCameraPosition().target;chosen[0]=point;
                if(preset[0]!=null && Math.abs(point.latitude-preset[0].latitude)<0.00001 && Math.abs(point.longitude-preset[0].longitude)<0.00001){preset[0]=null;address.setText(host.pickupAddress());location.setText("Pulang ke titik jemput awal");return;}
                preset[0]=null;location.setText("Mencari alamat titik pilihan…");
                final long selection=++selectionVersion;
                lookup=()->DriverNetworkExecutor.execute(()->{
                    try{
                        JSONObject q=new JSONObject().put("latitude",point.latitude).put("longitude",point.longitude);
                        JSONObject r=new com.transiva.app.driver.data.DriverApiClient(host.session).post("driver_reverse_geocode.php",q).body;
                        String label=r.optString("address",r.optString("display_name","")).trim();
                        host.mainHandler.post(()->{if(picker==null||generation!=pickerVersion||selection!=selectionVersion||chosen[0]!=point)return;if(address.getText().toString().trim().isEmpty())address.setText(label);location.setText(label.isEmpty()?"Alamat belum ditemukan. Isi alamat secara manual.":"Alamat terisi dari pin tengah peta");});
                    }catch(Exception error){host.mainHandler.post(()->{if(picker!=null&&generation==pickerVersion&&selection==selectionVersion&&chosen[0]==point)location.setText("Alamat otomatis belum tersedia. Isi alamat atau geser peta untuk mencoba lagi.");});}
                });host.mainHandler.postDelayed(lookup,650);
            });
            double lat=host.coord("delivery_lat","destination_lat"),lng=host.coord("delivery_lng","destination_lng");
            if(host.valid(lat,lng))g.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(lat,lng),16));
            home.setOnClickListener(v->{double x=host.coord("pickup_lat","user_lat"),y=host.coord("pickup_lng","user_lng");if(!host.valid(x,y)){location.setText("Titik jemput belum tersedia");return;}preset[0]=new LatLng(x,y);g.moveCamera(CameraUpdateFactory.newLatLngZoom(preset[0],16));});
        });
        picker.show();if(picker.getWindow()!=null)picker.getWindow().setBackgroundDrawable(host.round("#FFFFFF",host.dp(18)));map.onStart();map.onResume();
    }
    private void quote(LatLng point,String label) {
        final long dialogVersion=generation,pointVersion=selectionVersion;
        quotePending=true;if(picker!=null)picker.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        if(pickerStatus!=null)pickerStatus.setText("Server menghitung seluruh rute dan ongkir…");host.setLoading(true);
        DriverNetworkExecutor.execute(() -> {
            try {
                JSONObject q=new JSONObject().put("id",Integer.parseInt(host.internalId())).put("action","quote")
                    .put("latitude",point.latitude).put("longitude",point.longitude).put("address",label);
                JSONObject r=new com.transiva.app.driver.data.DriverApiClient(host.session).post("driver_destination_extension.php",q).body;
                host.mainHandler.post(() -> {
                    if(!alive()||generation!=dialogVersion)return;host.setLoading(false);quotePending=false;
                    if(pointVersion!=selectionVersion||pickerAddress==null||!label.equals(pickerAddress.getText().toString().trim())){
                        if(pickerStatus!=null)pickerStatus.setText("Pilihan berubah. Hitung ulang untuk titik terakhir.");if(picker!=null)picker.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);return;
                    }
                    close();
                    PremiumDialogs.builder(host).setTitle("Konfirmasi pengajuan tujuan")
                        .setMessage("Tujuan baru: "+r.optString("address")+"\nJarak tambahan: "+host.one(r.optDouble("distance_km"))+" km\nOngkir tambahan: "+host.rupiah(r.optDouble("extra_fare"))+"\nSeluruh rute: "+host.one(r.optDouble("total_distance_km"))+" km\nTotal baru: "+host.rupiah(r.optDouble("total"))+"\n\nTujuan dan biaya berlaku setelah customer menyetujui. Tarif segmen tambahan mengikuti tarif reguler wilayah.")
                        .setNegativeButton("Batal",null).setPositiveButton("Kirim ke customer",(v,w)->send("request",r.optLong("request_id"))).show();
                });
            } catch(Exception e) {
                host.mainHandler.post(()->{if(!alive()||generation!=dialogVersion)return;host.setLoading(false);quotePending=false;
                    if(pickerStatus!=null)pickerStatus.setText(host.first(e.getMessage(),"Layanan rute belum tersedia. Coba hitung lagi."));
                    if(picker!=null)picker.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(pickerAddress!=null&&!pickerAddress.getText().toString().trim().isEmpty());
                });
            }
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
