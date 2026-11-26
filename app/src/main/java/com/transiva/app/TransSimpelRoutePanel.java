package com.transiva.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.Locale;

/** Shared route state is owned by the server. Only the customer can submit the order. */
final class TransSimpelRoutePanel {
    private static final int PICKUP = 681;
    private static final int DELIVERY = 682;
    private static final int STOP = 683;
    private final Activity activity;
    private final String id;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::refresh;
    private final LinearLayout view;
    private final android.widget.ScrollView routeScroll;
    private final LinearLayout fareRow;
    private final Button stop;
    private final Button navigation, tripAction;
    private final TripLocationController tripLocation;
    private android.location.Location lastFix;
    private final Button clearStop;
    private final Button quoteRetry;
    private final Button briefButton;
    private final TextView purchaseHint;
    private JSONObject tivaBrief;
    private final TextView pickupAddress;
    private final TextView stopAddress;
    private final TextView deliveryAddress;
    private final TextView summary;
    private final DriverUiScope network;
    private final SessionManager session;
    private JSONObject route;
    private JSONObject previous;
    private int pickerRevision = -1;
    private int quotedRevision = -1;
    private boolean active;
    private boolean busy;
    private boolean readingRoute;
    private boolean available=true;

    private final TextView routeTitle;
    private final Button mapToggle;
    private com.google.android.gms.maps.MapView mapView;
    private com.google.android.gms.maps.GoogleMap googleMap;
    private boolean mapExpanded=true;
    private String mapBoundsKey="";
    private final java.util.ArrayList<com.google.android.gms.maps.model.Marker> mapMarkers=new java.util.ArrayList<>();
    private com.google.android.gms.maps.model.Polyline mapLine;
    private org.json.JSONArray mapPath;
    private String mapRouteKey="";

    TransSimpelRoutePanel(Activity activity, String id) {
        this.activity = activity;
        tripLocation=new TripLocationController(activity,new TripLocationController.Callback() {
            @Override public void onLocation(android.location.Location fix) { lastFix=fix; }
            @Override public void onPermissionRequired() { }
        });
        this.id = id;
        network = new DriverUiScope(activity);
        session = new SessionManager(activity);
        view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(9),dp(4),dp(9),dp(5));
        view.setBackground(surface("#FFFFFF", "#DCE8F5"));
        routeScroll=new android.widget.ScrollView(activity) {
            @Override protected void onMeasure(int widthSpec,int heightSpec) {
                android.view.ViewParent parent=getParent();
                int available=parent instanceof View && ((View)parent).getHeight()>0
                        ? ((View)parent).getHeight()
                        : dp(activity.getResources().getConfiguration().screenHeightDp);
                int cap=Math.max(dp(48),Math.round(available*(mapView==null?.42f:.56f)));
                if (MeasureSpec.getMode(heightSpec)!=MeasureSpec.UNSPECIFIED) cap=Math.min(cap,MeasureSpec.getSize(heightSpec));
                super.onMeasure(widthSpec,MeasureSpec.makeMeasureSpec(cap,MeasureSpec.AT_MOST));
            }
        };
        routeScroll.setFillViewport(false);
        routeScroll.setVisibility(View.GONE);
        routeScroll.addView(view,new android.widget.ScrollView.LayoutParams(-1,-2));
        LinearLayout heading=new LinearLayout(activity);
        heading.setGravity(android.view.Gravity.CENTER_VERTICAL);
        routeTitle=new TextView(activity);
        TextView title=routeTitle;
        title.setText("Atur rute");
        title.setTextColor(0xFF102449);
        title.setTextSize(15);
        title.setMaxLines(3);
        title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        stop = button("+ Singgah", () -> openPicker(STOP));
        clearStop = button("Hapus", this::removeStop);
        style(stop,"#F5F9FF","#075BC8");
        style(clearStop,"#F3F6FA","#41556C");
        briefButton=button("Tiva AI  ›",()->TivaSimpelBrief.show(activity,tivaBrief));
        style(briefButton,"#EFE8FF","#502B93");
        briefButton.setContentDescription("Buka percakapan Tiva AI");
        briefButton.setPadding(dp(10),0,dp(10),0);
        briefButton.setVisibility(View.GONE);
        heading.addView(briefButton,new LinearLayout.LayoutParams(-2,dp(48)));
        mapToggle=button("Sembunyikan peta",()->{
            mapExpanded=!mapExpanded;
            if(route!=null)render(route);
            if(mapExpanded)refresh();
        });
        mapToggle.setVisibility(View.GONE);
        heading.addView(mapToggle,new LinearLayout.LayoutParams(-2,dp(44)));
        view.addView(heading,new LinearLayout.LayoutParams(-1,-2));

        pickupAddress = addressLine("Jemput","●",0xFF08785B,"pickup");
        view.addView(stop,new LinearLayout.LayoutParams(-2,dp(44)));
        stopAddress = addressLine("Singgah","◆",0xFF925200,"stop");
        deliveryAddress = addressLine("Antar","⚑",0xFFB52432,"delivery");
        View divider=new View(activity);
        divider.setBackgroundColor(0xFFDCE8F5);
        view.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));
        fareRow=new LinearLayout(activity);
        fareRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        view.addView(fareRow,new LinearLayout.LayoutParams(-1,-2));
        summary = new TextView(activity);
        summary.setTextColor(0xFF143052);
        summary.setTextSize(13);
        summary.setMaxLines(2);
        summary.setPadding(dp(4),dp(8),dp(4),dp(8));
        fareRow.addView(summary,new LinearLayout.LayoutParams(-1,-2));
        summary.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        summary.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        purchaseHint=new TextView(activity);
        purchaseHint.setTextColor(0xFF526E90);
        purchaseHint.setTextSize(12);
        purchaseHint.setText("Harga makanan atau belanja dibahas di chat.");
        purchaseHint.setVisibility(View.GONE);
        view.addView(purchaseHint);
        view.addView(clearStop,new LinearLayout.LayoutParams(-2,dp(44)));
        quoteRetry = button("Hitung Ulang Ongkir", () -> {
            quotedRevision = -1;
            calculateIfReady();
        });
        view.addView(quoteRetry,new LinearLayout.LayoutParams(-1,dp(44)));
        navigation=button("Navigasi",this::openNavigation);
        tripAction=button("Perbarui perjalanan",this::advanceTrip);
        for (Button control:new Button[]{navigation,tripAction}) {
            control.setVisibility(View.GONE);
            view.addView(control,new LinearLayout.LayoutParams(-1,-2));
        }
    }

    static String contactId(String orderId,String roomId) {
        if (orderId != null && orderId.matches("PUD[0-9A-F]{24}")) return orderId;
        if (roomId != null && roomId.matches("ROOM-PUD[0-9A-F]{24}")) return roomId.substring(5);
        return "";
    }

    View view() { return routeScroll; }

    void fitOnResize(View parent) {
        parent.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
            if (b-t!=ob-ot) routeScroll.requestLayout();
        });
    }

    void resume() {
        active=true;
        if (mapView!=null) mapView.onResume();
        refresh();
    }

    void pause() {
        tripLocation.stop();
        lastFix=null;
        if (active && mapView!=null) mapView.onPause();
        active=false;
        main.removeCallbacks(poll);
    }

    void close() { pause(); removeMap(); network.close(); }

    private void removeMap() {
        if(mapView==null)return;
        if(active)mapView.onPause();
        mapView.onStop();mapView.onDestroy();
        view.removeView(mapView);
        mapView=null;googleMap=null;mapBoundsKey="";mapRouteKey="";mapPath=null;mapMarkers.clear();mapLine=null;
        routeScroll.requestLayout();
    }

    private void showMap() {
        if(mapView!=null){ paintMap(); return; }
        com.google.android.gms.maps.MapView map=new com.google.android.gms.maps.MapView(activity);
        mapView=map;
        map.onCreate(null);
        int height=Math.max(112,Math.min(165,activity.getResources().getConfiguration().screenHeightDp/4));
        view.addView(map,1,new LinearLayout.LayoutParams(-1,dp(height)));
        map.onStart();
        if(active)map.onResume();
        map.getMapAsync(google->{
            DriverGoogleMapStyle.apply(activity, google);
            if(mapView!=map || activity.isFinishing() || activity.isDestroyed())return;
            googleMap=google;
            google.getUiSettings().setAllGesturesEnabled(false);
            google.getUiSettings().setMapToolbarEnabled(false);
            paintMap();
        });
    }

    private void paintMap() {
        if(googleMap==null || route==null)return;
        com.google.android.gms.maps.model.LatLngBounds.Builder bounds=new com.google.android.gms.maps.model.LatLngBounds.Builder();
        StringBuilder key=new StringBuilder();
        for(com.google.android.gms.maps.model.Marker marker:mapMarkers)if(marker!=null)marker.remove();
        mapMarkers.clear();
        String[] names={"pickup","delivery","driver_position"};
        float[] colors={120f,0f,210f};
        for(int index=0;index<names.length;index++){
            JSONObject point=route.optJSONObject(names[index]);
            if(point==null)continue;
            double lat=point.optDouble("lat"),lng=point.optDouble("lng");
            if(!Double.isFinite(lat)||!Double.isFinite(lng)||Math.abs(lat)>90||Math.abs(lng)>180)continue;
            com.google.android.gms.maps.model.LatLng position=new com.google.android.gms.maps.model.LatLng(lat,lng);
            bounds.include(position);
            key.append(index==2?"driver":position.toString());
            mapMarkers.add(googleMap.addMarker(new com.google.android.gms.maps.model.MarkerOptions().position(position)
                    .title(index==0?"Jemput":index==1?"Antar":"Posisi driver")
                    .icon(com.google.android.gms.maps.model.BitmapDescriptorFactory.defaultMarker(colors[index]))));
        }
        if (mapMarkers.isEmpty()) return;
        String routeKey=String.valueOf(route.optJSONObject("pickup"))+"|"
                +String.valueOf(route.optJSONObject("delivery"))+"|"+String.valueOf(route.optJSONObject("stop"));
        if (!routeKey.equals(mapRouteKey)) { mapRouteKey=routeKey;mapPath=null; }
        if (route.has("map_points")) mapPath=route.optJSONArray("map_points");
        org.json.JSONArray path=mapPath;
        java.util.ArrayList<com.google.android.gms.maps.model.LatLng> polyline=new java.util.ArrayList<>();
        if(path!=null)for(int index=0;index<path.length();index++){
            org.json.JSONArray point=path.optJSONArray(index);
            if(point!=null && Double.isFinite(point.optDouble(0)) && Double.isFinite(point.optDouble(1))) {
                com.google.android.gms.maps.model.LatLng position=new com.google.android.gms.maps.model.LatLng(point.optDouble(0),point.optDouble(1));
                polyline.add(position);bounds.include(position);
            }
        }
        if(mapLine!=null)mapLine.remove();
        mapLine=polyline.size()>1?googleMap.addPolyline(new com.google.android.gms.maps.model.PolylineOptions()
                .addAll(polyline).color(0xff0865e8).width(dp(5))):null;
        String signature=key.toString();
        if(!signature.equals(mapBoundsKey)){
            mapBoundsKey=signature;
            com.google.android.gms.maps.model.LatLngBounds area=bounds.build();
            com.google.android.gms.maps.MapView root=mapView;
            root.post(()->{
                if(mapView!=root||root.getWidth()<=0||root.getHeight()<=0)return;
                try{ googleMap.moveCamera(com.google.android.gms.maps.CameraUpdateFactory.newLatLngBounds(area,root.getWidth(),root.getHeight(),dp(20))); }
                catch (IllegalStateException ignored) { }
            });
        }
    }

    void restore(Bundle state) {
        if (state == null) return;
        pickerRevision = state.getInt("simpel_picker_revision",-1);
        String savedPoint = state.getString("simpel_picker_previous","");
        try { previous = savedPoint.isEmpty() ? null : new JSONObject(savedPoint); }
        catch (org.json.JSONException ignored) { previous = null; }
    }

    void save(Bundle out) {
        out.putInt("simpel_picker_revision",pickerRevision);
        if (previous != null) out.putString("simpel_picker_previous",previous.toString());
    }

    private Button button(String title,Runnable click) {
        Button b = new Button(activity);
        b.setAllCaps(false);
        b.setText(title);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(5),0,dp(5),0);
        b.setTextSize(13);
        b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        style(b,"#EAF1FF","#17457C");
        b.setOnClickListener(v -> click.run());
        return b;
    }

    private int dp(int value) {
        return Math.round(activity.getResources().getDisplayMetrics().density * value);
    }

    private GradientDrawable surface(String fill,String stroke) {
        GradientDrawable bg=new GradientDrawable();
        bg.setColor(Color.parseColor(fill));
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1),Color.parseColor(stroke));
        return bg;
    }

    private void style(Button button,String fill,String text) {
        button.setTextColor(new ColorStateList(new int[][]{
                new int[]{-android.R.attr.state_enabled},new int[]{}},
                new int[]{0xFF61748B,Color.parseColor(text)}));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x332379DB),
                surface(fill,fill),null));
    }

    private static String field(int which) {
        return which==PICKUP?"pickup":which==STOP?"stop":"delivery";
    }

    private void removeStop() {
        if ((busy && !readingRoute) || route==null || !"accepted".equals(route.optString("state"))) return;
        JSONObject selected=route.optJSONObject("stop");
        if (selected==null) return;
        try {
            send("set",new JSONObject().put("point","stop").put("revision",route.optInt("revision"))
                    .put("previous",selected).put("location",JSONObject.NULL));
        } catch (org.json.JSONException error) {
            notice("Singgah belum dapat dihapus.");
        }
    }

    private TextView addressLine(String title,String icon,int color,String key) {
        TextView line=new TextView(activity);
        line.setTextColor(color);
        line.setTextSize(13);
        line.setMaxLines(2);
        line.setEllipsize(TextUtils.TruncateAt.END);
        line.setGravity(android.view.Gravity.CENTER_VERTICAL);
        line.setMinHeight(dp(44));
        line.setBackground(surface("#F8FAFD","#E5ECF5"));
        line.setPadding(dp(6),dp(4),dp(4),dp(4));
        line.setOnClickListener(v->{
            if (route!=null && canEditPoint("pickup".equals(key)?PICKUP:"stop".equals(key)?STOP:DELIVERY)
                    && !("pickup".equals(key) && "food".equals(kind()))) {
                openPicker("pickup".equals(key)?PICKUP:"stop".equals(key)?STOP:DELIVERY);
                return;
            }
            new android.app.AlertDialog.Builder(activity)
                .setTitle(title).setMessage(route==null?"Belum diatur":address(key))
                .setPositiveButton("Tutup",null).show();
        });
        line.setTag(icon+"  "+title);
        view.addView(line,new LinearLayout.LayoutParams(-1,-2));
        return line;
    }

    private void showAddress(TextView line,String key) {
        String title=(String)line.getTag();
        String place=address(key);
        android.text.SpannableString display=new android.text.SpannableString(title+"\n    "+place+"  ›");
        display.setSpan(new android.text.style.StyleSpan(Typeface.BOLD),0,title.length(),0);
        display.setSpan(new android.text.style.ForegroundColorSpan(0xFF213953),title.length()+1,display.length(),0);
        line.setText(display);
        line.setContentDescription(title+". "+place+". Ketuk untuk melihat alamat lengkap.");
    }

    private void refresh() {
        if (active && !busy && available) {
            try { send("get",new JSONObject().put("map_live",mapExpanded).put("map",mapExpanded && (mapPath==null || mapPath.length()<2))); }
            catch (org.json.JSONException ignored) { }
        }
    }

    private void render(JSONObject update) {
        int revision=update.optInt("revision");
        if (route!=null && revision<route.optInt("revision")) return;
        if (route!=null && !route.isNull("total") && update.isNull("total")) quotedRevision=-1;
        route=update;
        String state=route.optString("state");
        boolean editable="accepted".equals(state);
        boolean ordered="ordered".equals(state);
        boolean assigned=ordered && route.optBoolean("driver_has_order") && !tripEnded();
        if (assigned && active) tripLocation.start();
        else tripLocation.stop();
        navigation.setVisibility(assigned?View.VISIBLE:View.GONE);
        tripAction.setVisibility(assigned && !nextStep().isEmpty()?View.VISIBLE:View.GONE);
        tripAction.setEnabled(!busy);
        tripAction.setText(stepLabel());
        boolean waiting="waiting_driver".equals(state);
        routeScroll.setVisibility(editable || ordered || waiting ? View.VISIBLE : View.GONE);
        String phase=route.optString("order_status");
        String caption="arrived_pickup".equals(phase)?"Tiba di jemput":
                "on_delivery".equals(phase)||"picked_up".equals(phase)?"Menuju antar":
                "arrived_delivery".equals(phase)?"Tiba di tujuan":
                "finished".equals(phase)||"completed".equals(phase)?"Selesai":"Menuju jemput";
        routeTitle.setText(ordered?"Rute perjalanan\n"+caption:waiting?"Pesanan dalam antrean":"Atur rute");
        boolean canShowMap=ordered && route.optJSONObject("pickup")!=null && route.optJSONObject("delivery")!=null;
        mapToggle.setVisibility(canShowMap?View.VISIBLE:View.GONE);
        mapToggle.setText(mapExpanded?"Tutup peta":"Tampil peta");
        if (canShowMap && mapExpanded) showMap();
        else removeMap();
        briefButton.setVisibility(tivaBrief!=null ? View.VISIBLE : View.GONE);
        briefButton.setText("shop".equals(kind())?"Daftar belanja  ›":"Tiva AI  ›");
        boolean food="food".equals(kind());
        boolean send="send".equals(kind());
        stop.setVisibility((editable && (kind().isEmpty() || "ride".equals(kind())) ||
                assigned && canEditPoint(STOP))?View.VISIBLE:View.GONE);
        stop.setEnabled(!busy);
        boolean hasStop=route.optJSONObject("stop")!=null;
        stop.setText(ordered?"+ Ajukan singgah":hasStop?"Singgah":"+ Singgah");
        stop.setContentDescription(hasStop?"Ubah titik singgah":"Tambah titik singgah opsional");
        clearStop.setVisibility(editable && hasStop?View.VISIBLE:View.GONE);
        clearStop.setEnabled(!busy);
        clearStop.setContentDescription("Hapus titik singgah");
        long amount=route.optLong("total",0);
        showAddress(pickupAddress,"pickup");
        showAddress(stopAddress,"stop");
        stopAddress.setVisibility(hasStop?View.VISIBLE:View.GONE);
        showAddress(deliveryAddress,"delivery");
        summary.setText(amount>0?(food?"Total makanan + ongkir":ordered?"Total":"Estimasi")+"  Rp "
                +NumberFormat.getIntegerInstance(new Locale("id","ID")).format(amount)+" · Tunai":"");
        boolean assistedPurchase=tivaBrief!=null
                && "shop".equals(kind());
        purchaseHint.setVisibility(assistedPurchase?View.VISIBLE:View.GONE);
        if (assistedPurchase) {
            purchaseHint.setMaxLines(4);
            purchaseHint.setEllipsize(android.text.TextUtils.TruncateAt.END);
            purchaseHint.setText("Daftar belanja\n"+tivaBrief.optString("detail"));
            purchaseHint.setOnClickListener(v->TivaSimpelBrief.show(activity,tivaBrief));
        }
        if (amount>0 && assistedPurchase) {
            summary.setText("Ongkir  Rp "+NumberFormat.getIntegerInstance(new Locale("id","ID")).format(amount)+" · Tunai");
        }
        if (waiting) summary.setText("Pesanan antre sampai perjalanan aktif selesai.");
        summary.setVisibility(waiting || amount>0?View.VISIBLE:View.GONE);
        boolean both=route.optJSONObject("pickup")!=null && route.optJSONObject("delivery")!=null;
        quoteRetry.setVisibility(editable && both && amount==0 && quotedRevision==revision ? View.VISIBLE : View.GONE);
        quoteRetry.setEnabled(!busy);
    }

    private String address(String key) {
        JSONObject point=route.optJSONObject(key);
        return point==null?"Belum diatur":point.optString("address").replace('\n',' ');
    }

    private String kind() {
        return tivaBrief==null?"":tivaBrief.optString("kind");
    }

    private void calculateIfReady() {
        if ("send".equals(kind())) return;
        if ("food".equals(kind())) return;

        if (!active || busy || route==null || !"accepted".equals(route.optString("state"))
                || route.optJSONObject("pickup")==null || route.optJSONObject("delivery")==null
                || !route.isNull("total")) return;
        int revision=route.optInt("revision");
        if (revision==quotedRevision) return;
        quotedRevision=revision;
        send("quote",null);
    }

    private void openPicker(int which) {
        if (route==null || !canEditPoint(which)) return;
        String field=field(which);
        pickerRevision=route.optInt("revision");
        previous=route.optJSONObject(field);
        JSONObject seed=previous!=null?previous:route.optJSONObject(which==PICKUP?"delivery":"pickup");
        Intent intent=new Intent(activity,DriverSimpelPointActivity.class)
                .putExtra("point_kind",field)
                .putExtra("title",which==PICKUP?"Atur Titik Jemput":which==STOP?"Atur Titik Singgah":"Atur Titik Antar");
        if (seed!=null) intent.putExtra("lat",seed.optDouble("lat")).putExtra("lng",seed.optDouble("lng"));
        intent.putExtra("route_contact",id).putExtra("route_snapshot",route.toString());
        for (String marker:new String[]{"pickup","delivery","stop"}) {
            JSONObject point=route.optJSONObject(marker);
            if (point!=null) intent.putExtra("saved_"+marker,point.toString());
        }

        try { activity.startActivityForResult(intent,which); }
        catch (android.content.ActivityNotFoundException e) { notice("Pemilih lokasi belum dapat dibuka."); }
    }

    boolean result(int request,int result,Intent data) {
        if (request!=PICKUP && request!=DELIVERY && request!=STOP) return false;
        if (result!=Activity.RESULT_OK || data==null || pickerRevision<0) return true;
        try {
            JSONObject payload=new JSONObject()
                    .put("point",field(request))
                    .put("revision",data.getIntExtra("route_revision",pickerRevision))
                    .put("previous",previousPoint(data))
                    .put("location",new JSONObject().put("lat",data.getDoubleExtra("lat",0))
                            .put("lng",data.getDoubleExtra("lng",0)).put("address",data.getStringExtra("address")));
            pickerRevision=-1;
            if ("ordered".equals(route.optString("state"))) {
                JSONObject location=payload.getJSONObject("location");
                send("propose",new JSONObject().put("source",route.optString("source"))
                        .put("order_id",route.optString("order_id")).put("point",field(request))
                        .put("latitude",location.optDouble("lat")).put("longitude",location.optDouble("lng"))
                        .put("address",location.optString("address")));
            } else if (busy) queuedPoint=payload;
            else send("set",payload);
        } catch (Exception error) { notice("Titik belum dapat disimpan."); }
        return true;
    }

    private Object previousPoint(Intent data) throws org.json.JSONException {
        if (!data.hasExtra("route_previous")) return previous==null?JSONObject.NULL:previous;
        String point=data.getStringExtra("route_previous");
        return point==null || point.isEmpty()?JSONObject.NULL:new JSONObject(point);
    }

    private JSONObject queuedPoint;

    private void send(String action,JSONObject data) {
        busy=true;
        readingRoute="get".equals(action);
        main.removeCallbacks(poll);
        if (!readingRoute && route!=null) render(route);
        if ("quote".equals(action)) {
            summary.setVisibility(View.VISIBLE);
            summary.setText("Menghitung ongkir…");
        }
        if (!submit(() -> {
            try {
                JSONObject body=data==null?new JSONObject():data;
                body.put("action",action).put("contact_id",id);
                JSONObject reply=request(body);
                if (!reply.optBoolean("success")) throw new IllegalStateException(reply.optString("message","Permintaan belum berhasil."));
                post(() -> complete(action,reply,null));
            } catch (Exception error) {
                post(() -> complete(action,null,error.getMessage()));
            }
        })) {
            complete(action,null,"Koneksi sibuk. Coba lagi.");
        }
    }

    private void complete(String action,JSONObject reply,String failure) {
        boolean wasReading=readingRoute;
        busy=false;
        readingRoute=false;
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (reply!=null && !reply.optBoolean("available",true)) {
            available=false;
            routeScroll.setVisibility(View.GONE);
            return;
        }
        boolean briefChanged=false;
        if (reply!=null && reply.has("tiva_brief")) {
            JSONObject latest=reply.optJSONObject("tiva_brief");
            briefChanged=!sameSelection(latest,tivaBrief);
            if (briefChanged) {
                tivaBrief=latest;
                briefButton.setVisibility(tivaBrief!=null?View.VISIBLE:View.GONE);
            }
        }
        JSONObject updated=reply==null?null:reply.optJSONObject("route");
        if (updated!=null && (!wasReading || briefChanged || !sameSelection(updated,route))) render(updated);
        else if (!wasReading && route!=null) render(route);
        if (failure!=null && !"get".equals(action)) notice(failure);
        if (reply!=null && ("trip".equals(action)||"propose".equals(action))) {
            if (active) refresh();
            return;
        }
        if (queuedPoint!=null) {
            JSONObject point=queuedPoint;
            queuedPoint=null;
            send("set",point);
            return;
        }
        if (active) {
            calculateIfReady();
            if (!busy && (route==null || "accepted".equals(route.optString("state"))
                    || "waiting_driver".equals(route.optString("state"))
                    || ("ordered".equals(route.optString("state")) && !tripEnded()))) main.postDelayed(poll,5000);
        }
    }

    private static boolean sameSelection(JSONObject left,JSONObject right) {
        return left==right || (left!=null && right!=null && left.toString().equals(right.toString()));
    }

    private void notice(String message) {
        if (!activity.isFinishing()) Toast.makeText(activity,message,Toast.LENGTH_LONG).show();
    }

    private boolean tripEnded() {
        String phase=DriverOrderStateMachine.normalize(route.optString("order_status"));
        return "finished".equals(phase)||"cancelled".equals(phase)||"canceled".equals(phase);
    }

    private boolean canEditPoint(int which) {
        if ("accepted".equals(route.optString("state"))) return true;
        if (!"ordered".equals(route.optString("state"))||!route.optBoolean("driver_has_order")) return false;
        String phase=DriverOrderStateMachine.normalize(route.optString("order_status"));
        return which==PICKUP?!"food".equals(kind()) && "taken".equals(phase):"on_delivery".equals(phase);
    }

    private JSONObject nextStop() {
        JSONObject ecosystem=route.optJSONObject("ecosystem");
        org.json.JSONArray stops=ecosystem==null?null:ecosystem.optJSONArray("waypoints");
        for (int i=0;stops!=null && i<stops.length();i++) {
            JSONObject point=stops.optJSONObject(i);
            if (point!=null && !java.util.Arrays.asList("arrived","skipped","cancelled","canceled").contains(point.optString("status"))) return point;
        }
        return null;
    }

    private String nextStep() {
        switch (DriverOrderStateMachine.normalize(route.optString("order_status"))) {
            case "taken": return "arrived_pickup";
            case "arrived_pickup": return "on_delivery";
            case "on_delivery": return nextStop()==null?"arrived_delivery":"arrive_waypoint";
            case "arrived_delivery": return route.optInt("customer_received")==1?"finished":"";
            default:return "";
        }
    }

    private String stepLabel() {
        switch(nextStep()) {
            case "arrived_pickup":return "Tiba di titik jemput";
            case "on_delivery":return "Mulai pengantaran";
            case "arrive_waypoint":return "Tiba di titik singgah";
            case "arrived_delivery":return "Tiba di tujuan";
            case "finished":return "Selesaikan pesanan";
            default:return "";
        }
    }

    private void advanceTrip() {
        if (busy || route==null || !route.optBoolean("driver_has_order")) return;
        String next=nextStep();
        if (next.isEmpty()) return;
        android.location.Location fix=TripArrivalLocation.verified(lastFix);
        if (fix==null && next.startsWith("arriv")) { notice("Tunggu GPS akurat di titik perjalanan."); return; }
        new android.app.AlertDialog.Builder(activity)
                .setMessage(stepLabel()+"?").setNegativeButton("Kembali",null)
                .setPositiveButton("Konfirmasi",(dialog,which)->{
                    if (busy || !next.equals(nextStep())) return;
                    try {
                        JSONObject payload=new JSONObject().put("order_id",route.optString("order_id"))
                                .put("id",route.optLong("order_db_id")).put("source",route.optString("source"))
                                .put("driver",session.getUsername()).put("driver_username",session.getUsername())
                                .put("status",next);
                        if (fix!=null) payload.put("driver_lat",fix.getLatitude()).put("driver_lng",fix.getLongitude());
                        if ("arrive_waypoint".equals(next)) payload.put("sequence",nextStop().optInt("sequence"));
                        send("trip",payload);
                    } catch (org.json.JSONException invalid) { notice("Status belum dapat disimpan."); }
                }).show();
    }

    private void openNavigation() {
        if (route==null || !route.optBoolean("driver_has_order")) return;
        try {
            JSONObject order=new JSONObject().put("id",route.optLong("order_db_id"))
                    .put("order_id",route.optString("order_id")).put("source",route.optString("source"))
                    .put("status",route.optString("order_status")).put("order_type",route.optString("order_type"))
                    .put("driver_type","bike").put("customer_received",route.optInt("customer_received"))
                    .put("ecosystem",route.opt("ecosystem"));
            for (String key:new String[]{"pickup","delivery"}) {
                JSONObject point=route.getJSONObject(key);
                order.put(key+"_lat",point.optDouble("lat")).put(key+"_lng",point.optDouble("lng"))
                        .put(key+"_address",point.optString("address"));
            }
            activity.startActivity(new Intent(activity,DriverNavigationActivity.class).putExtra("order_json",order.toString()));
        } catch (Exception unavailable) { notice("Rute navigasi belum tersedia."); }
    }

    private JSONObject request(JSONObject data) throws Exception {
        String action=data.optString("action");
        if ("propose".equals(action)) {
            return DriverMessageApi.post(session,DriverApiConfig.BASE_URL+"driver_propose_location.php",data,network.owner);
        }
        if ("trip".equals(action)) {
            String status=data.optString("status");
            String endpoint=DriverOrderStateMachine.endpoint(status,"pickup_orders".equals(data.optString("source")));
            if ("arrive_waypoint".equals(status)) {
                endpoint="ride_ecosystem_order.php";
                data.put("action","arrive_waypoint");
            }
            return DriverMessageApi.post(session,DriverApiConfig.BASE_URL+endpoint,data,network.owner);
        }
        return DriverMessageApi.post(session,DriverApiConfig.BASE_URL+"trans_simpel.php",data,network.owner);
    }
    private boolean submit(Runnable work) { return network.execute(work); }
    private void post(Runnable work) { network.post(work); }
}
