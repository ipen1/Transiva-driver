package com.transiva.app;

import android.annotation.SuppressLint;
import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/** Peta sosial driver: hanya driver online + idle dalam radius 20 km. */
public class DriverLocationActivity extends FragmentActivity implements OnMapReadyCallback {
    private static final int REQ_LOCATION = 9231;
    private static final long REFRESH_MS = 15000L;
    private static final String BASE = "https://transiva.my.id/server/";

    private final Handler main = new Handler(Looper.getMainLooper());
    private GoogleMap map;
    private SessionManager session;
    private FusedLocationProviderClient locationClient;
    private Location myLocation;
    private LinearLayout driverCard;
    private TextView driverName;
    private TextView driverMeta;
    private Button greetButton;
    private ProgressBar loading;
    private JSONObject selectedDriver;
    private boolean firstCamera = true;
    private boolean requestInFlight = false;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            updateMyLocationAndDrivers();
            main.postDelayed(this, REFRESH_MS);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        session = new SessionManager(this);
        if (!session.isLoggedIn()) { finish(); return; }
        locationClient = LocationServices.getFusedLocationProviderClient(this);
        setContentView(buildScreen());

        SupportMapFragment fragment = SupportMapFragment.newInstance();
        getSupportFragmentManager().beginTransaction().replace(10021, fragment).commitNow();
        fragment.getMapAsync(this);
    }

    private View buildScreen() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        FrameLayout mapHost = new FrameLayout(this);
        mapHost.setId(10021);
        root.addView(mapHost, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(12), dp(8), dp(12), dp(8));
        top.setBackgroundColor(Color.WHITE);
        top.setElevation(dp(5));
        TextView back = text("‹", 32, "#0B3A78", true);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(40), dp(46)));
        LinearLayout titles = new LinearLayout(this); titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("Lokasi Driver", 19, "#0B3A78", true));
        titles.addView(text("Driver online & idle • radius 20 km", 10, "#64748B", false));
        top.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP); tp.setMargins(dp(10), dp(10), dp(10), 0);
        root.addView(top, tp);

        loading = new ProgressBar(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER);
        root.addView(loading, lp);

        driverCard = new LinearLayout(this); driverCard.setOrientation(LinearLayout.VERTICAL); driverCard.setPadding(dp(16),dp(13),dp(16),dp(14));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable(); bg.setColor(Color.WHITE); bg.setCornerRadius(dp(20)); driverCard.setBackground(bg); driverCard.setElevation(dp(8)); driverCard.setVisibility(View.GONE);
        driverName = text("Driver", 17, "#0B3A78", true); driverMeta = text("", 11, "#64748B", false);
        driverCard.addView(driverName); driverCard.addView(driverMeta);
        greetButton = new Button(this); greetButton.setText("👋  Sapa"); greetButton.setAllCaps(false); greetButton.setTextSize(14); greetButton.setOnClickListener(v -> sendGreeting());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(48)); bp.setMargins(0,dp(10),0,0); driverCard.addView(greetButton,bp);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM); cp.setMargins(dp(12),0,dp(12),dp(18)); root.addView(driverCard,cp);
        return root;
    }

    @Override public void onMapReady(GoogleMap googleMap) {
        map = googleMap;
        map.getUiSettings().setZoomControlsEnabled(false);
        map.getUiSettings().setMapToolbarEnabled(false);
        map.setOnMapClickListener(p -> hideDriverCard());
        map.setOnMarkerClickListener(marker -> {
            Object tag = marker.getTag();
            if (tag instanceof JSONObject) { showDriver((JSONObject) tag); return true; }
            return false;
        });

        // Kamera awal hanya sebagai fallback visual. Setelah GPS tersedia kamera otomatis
        // berpindah ke posisi driver pada acceptLocation(). Ini juga membuat kegagalan
        // autentikasi Maps mudah dibedakan dari kegagalan GPS/API driver.
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(-0.9003, 119.8779), 11.5f));
        enableMyLocation();
    }

    private void enableMyLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        }
        try { if (map != null) map.setMyLocationEnabled(true); } catch (SecurityException ignored) {}
        updateMyLocationAndDrivers();
    }

    @SuppressLint("MissingPermission")
    private void updateMyLocationAndDrivers() {
        if (requestInFlight || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        try {
            locationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).addOnSuccessListener(location -> {
                if (location == null) {
                    locationClient.getLastLocation().addOnSuccessListener(last -> {
                        if (last == null) { loading.setVisibility(View.GONE); toast("Lokasi belum tersedia. Pastikan GPS aktif."); return; }
                        acceptLocation(last);
                    });
                    return;
                }
                acceptLocation(location);
            }).addOnFailureListener(e -> { loading.setVisibility(View.GONE); toast("Gagal membaca lokasi."); });
        } catch (SecurityException ignored) {}
    }

    private void acceptLocation(Location location) {
        myLocation = location;
        if (map != null && firstCamera) {
            firstCamera = false;
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(location.getLatitude(), location.getLongitude()), 14.2f));
        }
        loadDrivers(location);
    }

    private void loadDrivers(Location location) {
        requestInFlight = true; loading.setVisibility(View.VISIBLE);
        final String url = BASE + "driver_nearby_map.php?lat=" + String.format(Locale.US,"%.7f",location.getLatitude()) + "&lng=" + String.format(Locale.US,"%.7f",location.getLongitude()) + "&radius_km=20&_=" + System.currentTimeMillis();
        DriverNetworkExecutor.execute(() -> {
            try {
                JSONObject response = DriverMessageApi.get(session, url);
                JSONArray drivers = response.optJSONArray("drivers");
                runOnUiThread(() -> renderDrivers(drivers));
            } catch (Exception e) {
                runOnUiThread(() -> toast("Gagal memuat lokasi driver."));
            } finally {
                requestInFlight=false; runOnUiThread(() -> loading.setVisibility(View.GONE));
            }
        });
    }

    private void renderDrivers(JSONArray drivers) {
        if (map == null) return;
        map.clear(); hideDriverCard();
        if (drivers == null) return;
        for (int i=0;i<drivers.length();i++) {
            JSONObject d=drivers.optJSONObject(i); if(d==null) continue;
            double lat=d.optDouble("latitude",0), lng=d.optDouble("longitude",0); if(lat==0&&lng==0) continue;
            String type=d.optString("driver_type","bike");
            Marker marker=map.addMarker(new MarkerOptions().position(new LatLng(lat,lng)).title(d.optString("name","Driver")).icon(vehicleIcon(type)).anchor(.5f,.5f));
            if(marker!=null) marker.setTag(d);
        }
    }

    private BitmapDescriptor vehicleIcon(String type) {
        int res = getResources().getIdentifier(type.toLowerCase().contains("car") ? "map_car_top" : "map_motor_top", "drawable", getPackageName());
        Drawable drawable = ContextCompat.getDrawable(this,res);
        if(drawable==null) return BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE);
        int w=dp(42), h=dp(42); Bitmap bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888); Canvas c=new Canvas(bitmap); drawable.setBounds(0,0,w,h); drawable.draw(c); return BitmapDescriptorFactory.fromBitmap(bitmap);
    }

    private void showDriver(JSONObject d) {
        selectedDriver=d;
        String type=d.optString("driver_type","bike").toLowerCase().contains("car") ? "Transcar" : "Transbike";
        driverName.setText(d.optString("name", d.optString("username","Driver")));
        driverMeta.setText(type + " • idle • " + String.format(Locale.US,"%.1f km dari kamu",d.optDouble("distance_km",0)));
        greetButton.setEnabled(true); greetButton.setText("👋  Sapa"); driverCard.setVisibility(View.VISIBLE);
    }

    private void hideDriverCard(){ selectedDriver=null; if(driverCard!=null)driverCard.setVisibility(View.GONE); }

    private void sendGreeting() {
        JSONObject target=selectedDriver; if(target==null)return;
        String username=target.optString("username",""); if(username.isEmpty())return;
        greetButton.setEnabled(false); greetButton.setText("Mengirim…");
        DriverNetworkExecutor.execute(() -> {
            try {
                JSONObject body=new JSONObject(); body.put("target_username",username);
                JSONObject r=DriverMessageApi.post(session,BASE+"driver_send_greeting.php",body);
                runOnUiThread(() -> { greetButton.setText("✓ Tersapa"); toast(r.optString("message","Sapaan terkirim 👋")); });
            } catch(Exception e) {
                runOnUiThread(() -> { greetButton.setEnabled(true); greetButton.setText("👋  Sapa"); toast("Sapaan belum berhasil dikirim."); });
            }
        });
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){ super.onRequestPermissionsResult(requestCode,permissions,grantResults); if(requestCode==REQ_LOCATION&&grantResults.length>0&&grantResults[0]==PackageManager.PERMISSION_GRANTED)enableMyLocation(); else toast("Izin lokasi diperlukan untuk Lokasi Driver."); }
    @Override protected void onResume(){ super.onResume(); main.removeCallbacks(refresh); main.post(refresh); }
    @Override protected void onPause(){ main.removeCallbacks(refresh); super.onPause(); }

    private TextView text(String value,float size,String color,boolean bold){ TextView t=new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(Color.parseColor(color)); if(bold)t.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD); return t; }
    private int dp(int v){ return Math.round(v*getResources().getDisplayMetrics().density); }
    private void toast(String s){ Toast.makeText(this,s,Toast.LENGTH_SHORT).show(); }
}
