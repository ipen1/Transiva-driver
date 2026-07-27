package com.transiva.app;

import android.app.Activity;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Locale;

public class DriverLeafletNavigationActivity extends Activity {
    private WebView map;
    private JSONObject order;
    private String target = "pickup";
    private double lat = 0, lng = 0;
    private LocationManager lm;
    private LocationListener listener;

    @Override protected void onCreate(Bundle b){ super.onCreate(b); try{getWindow().setStatusBarColor(Color.WHITE); if(Build.VERSION.SDK_INT>=23)getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);}catch(Exception ignored){} try{order=new JSONObject(getIntent().getStringExtra("order_json"));}catch(Exception e){order=new JSONObject();} target=getIntent().getStringExtra("target")==null?"pickup":getIntent().getStringExtra("target"); build(); startGps(); }
    private void build(){ FrameLayout page=new FrameLayout(this); page.setBackgroundColor(Color.WHITE); LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); page.addView(root,new FrameLayout.LayoutParams(-1,-1)); LinearLayout top=new LinearLayout(this); top.setPadding(dp(18),dp(18),dp(18),dp(12)); top.setGravity(android.view.Gravity.CENTER_VERTICAL); TextView back=t("‹",34,"#0B3A78",true); back.setGravity(android.view.Gravity.CENTER); back.setOnClickListener(v->finish()); top.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48))); TextView title=t(target.equals("pickup")?"Navigasi ke Pickup":"Navigasi ke Delivery",20,"#0B3A78",true); top.addView(title,new LinearLayout.LayoutParams(0,-2,1)); root.addView(top); map=new WebView(this); WebSettings s=map.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); if(Build.VERSION.SDK_INT>=21)s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW); root.addView(map,new LinearLayout.LayoutParams(-1,0,1)); map.loadDataWithBaseURL("https://transiva.my.id/",html(),"text/html","UTF-8",null); setContentView(page); DriverAppSettings.apply(this); }
    private String html(){ double pLat=coord("pickup_lat","user_lat"),pLng=coord("pickup_lng","user_lng"),dLat=coord("delivery_lat","destination_lat"),dLng=coord("delivery_lng","destination_lng"); double tLat=target.equals("pickup")?pLat:dLat,tLng=target.equals("pickup")?pLng:dLng; String pin=icon(target.equals("pickup")?"map_pickup_pin":"map_destination_pin"); String driver=icon("map_motor_top"); return "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'><link rel='stylesheet' href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css'><script src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'></script><style>html,body,#map{height:100%;margin:0}.leaflet-control-attribution{display:none}.info{position:absolute;left:12px;right:12px;bottom:18px;z-index:999;background:white;border-radius:18px;padding:14px;font:15px sans-serif;color:#0B3A78;box-shadow:0 8px 24px #0002}</style></head><body><div id='map'></div><div class='info'>Ikuti rute biru menuju lokasi tujuan.</div><script>var map=L.map('map',{zoomControl:true,attributionControl:false}).setView(["+tLat+","+tLng+"],16);L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19}).addTo(map);function ico(u,s){return L.icon({iconUrl:u,iconSize:[s,s],iconAnchor:[s/2,s/2]});}var targetIcon=ico('"+pin+"',46), driverIcon=ico('"+driver+"',52);var dest=L.marker(["+tLat+","+tLng+"],{icon:targetIcon}).addTo(map);var drv=null,line=null;function update(a,b){if(!drv)drv=L.marker([a,b],{icon:driverIcon}).addTo(map);else drv.setLatLng([a,b]);if(line)line.remove();line=L.polyline([[a,b],["+tLat+","+tLng+"]],{color:'#086BFF',weight:7,opacity:.85}).addTo(map);map.fitBounds([[a,b],["+tLat+","+tLng+"]],{padding:[50,50],maxZoom:18});}window.navUpdate=update;</script></body></html>"; }
    private void startGps(){ try{ lm=(LocationManager)getSystemService(LOCATION_SERVICE); listener=new LocationListener(){public void onLocationChanged(Location l){lat=l.getLatitude();lng=l.getLongitude(); String js="if(window.navUpdate){navUpdate("+lat+","+lng+");}"; if(Build.VERSION.SDK_INT>=19)map.evaluateJavascript(js,null); else map.loadUrl("javascript:"+js);} public void onStatusChanged(String p,int s,Bundle e){} public void onProviderEnabled(String p){} public void onProviderDisabled(String p){}}; lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,2000,2,listener);}catch(Exception ignored){} }
    @Override protected void onDestroy(){try{if(lm!=null&&listener!=null)lm.removeUpdates(listener);}catch(Exception ignored){} super.onDestroy();}
    private double coord(String a,String b){try{return Double.parseDouble(first(order.optString(a),order.optString(b),"0"));}catch(Exception e){return 0;}} private String first(String...v){for(String s:v)if(s!=null&&s.trim().length()>0&&!s.equals("null"))return s.trim();return"";} private String icon(String name){try{int id=getResources().getIdentifier(name,"drawable",getPackageName());if(id!=0){InputStream is=getResources().openRawResource(id);ByteArrayOutputStream bos=new ByteArrayOutputStream();byte[] buf=new byte[4096];int n;while((n=is.read(buf))>0)bos.write(buf,0,n);return"data:image/png;base64,"+ Base64.encodeToString(bos.toByteArray(),Base64.NO_WRAP);}}catch(Exception ignored){}return"https://unpkg.com/leaflet@1.9.4/dist/images/marker-icon.png";} private TextView t(String s,int sp,String c,boolean b){TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setTextColor(Color.parseColor(c));if(b)v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);return v;} private int dp(int v){return(int)(v*getResources().getDisplayMetrics().density+0.5f);} }
