package com.transiva.app;

import android.Manifest;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.transiva.app.driver.domain.DriverDashboardState;
import com.transiva.app.driver.domain.DriverOrder;

import java.util.LinkedHashMap;
import java.util.Map;

final class DashboardMapPanel {
    static final String ACCEPT_ACTION = "dashboard_offer_accept";
    private static final int WAVE_COLOR = 0x00EA6043;
    private final DriverDashboardActivity host;
    private final FrameLayout page;
    private final FrameLayout controls;
    private final MapView mapView;
    private final TextView status;
    private final DriverUiScope queueScope;
    private android.app.AlertDialog queueDialog;
    private final ScrollView offers;
    private final LinearLayout popup;
    private static final String ACTIVE = "dashboard:active";
    private final FrameLayout acceptDock;
    private final LinearLayout tabs;
    private final TextView offersTab, activeTab;
    private java.util.List<String> liveKeys = java.util.Collections.emptyList();
    private int appliedPadding = -1;
    private long lastPulseAt;
    private com.google.android.gms.tasks.CancellationTokenSource locateRequest;
    private final Map<String, Pin> pins = new LinkedHashMap<>();
    private final Map<String, DriverOrder> assignedPickups = new LinkedHashMap<>();
    private String assignedFocus = "";
    private String requestedAssignment = "";
    private GoogleMap map;
    private ValueAnimator pulse;
    private boolean resumed;
    private boolean centered;
    private boolean destroyed;
    private DriverDashboardState state;
    private String focusedOffer = "";
    private View actionCard;
    private android.view.ViewGroup actionHome;
    private android.view.ViewGroup.LayoutParams actionParams;
    private String requestedId = "", requestedToken = "";

    DashboardMapPanel(DriverDashboardActivity host, FrameLayout page, Bundle saved) {
        this.host = host;
        this.page = page;
        queueScope=new DriverUiScope(host);
        mapView = new MapView(host);
        mapView.onCreate(saved == null ? null : saved.getBundle("dashboard_map"));
        page.addView(mapView, 0, new FrameLayout.LayoutParams(-1, -1));
        controls = new FrameLayout(host);
        page.addView(controls, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = host.card();
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(13), 0, dp(3), 0);
        top.setElevation(dp(6));
        top.addView(host.text("Transiva Driver", 17, "#0B3A78", true), new LinearLayout.LayoutParams(0, -2, 1));
        status = host.text("Memuat status", 12, "#64748B", true);
        status.setGravity(Gravity.CENTER);
        status.setMinHeight(dp(48));
        status.setPadding(dp(5), 0, dp(5), 0);
        status.setOnClickListener(v -> showDetails());
        top.addView(status);
        TextView menu = host.text("⋮", 24, "#0B3A78", true);
        menu.setContentDescription("Menu dashboard driver");
        menu.setGravity(Gravity.CENTER);
        menu.setOnClickListener(v -> showDetails());
        top.addView(menu, new LinearLayout.LayoutParams(dp(44), dp(48)));
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        topParams.setMargins(dp(10), dp(9), dp(10), 0);
        controls.addView(top, topParams);

        TextView queue=host.text("≡",27,"#0B3A78",true);
        queue.setContentDescription("Buka antrean order");
        queue.setGravity(Gravity.CENTER);
        queue.setBackground(host.round("#FFFFFF",dp(25)));
        queue.setElevation(dp(5));
        queue.setOnClickListener(v->openOrderQueue());
        FrameLayout.LayoutParams queueParams=new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.TOP|Gravity.START);
        queueParams.setMarginStart(dp(12));
        queueParams.topMargin=dp(65);
        controls.addView(queue,queueParams);

        popup = new LinearLayout(host);
        popup.setOrientation(LinearLayout.VERTICAL);
        popup.setVisibility(View.GONE);
        popup.setElevation(dp(6));
        popup.setBackground(host.round("#FFFFFF", dp(22)));
        popup.setPadding(dp(9), 0, dp(9), dp(5));
        tabs = new LinearLayout(host);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        offersTab = tab("Tawaran", this::nextOffer);
        activeTab = tab("Order aktif", () -> selectOffer(ACTIVE, false));
        tabs.addView(offersTab, new LinearLayout.LayoutParams(0, dp(44), 1));
        tabs.addView(activeTab, new LinearLayout.LayoutParams(0, dp(44), 1));
        popup.addView(tabs, new LinearLayout.LayoutParams(-1, -2));
        offers = new ScrollView(host);
        offers.setFillViewport(false);
        offers.setVerticalScrollBarEnabled(false);
        offers.addView(host.orderSections, new ScrollView.LayoutParams(-1, -2));
        popup.addView(offers, new LinearLayout.LayoutParams(-1, -2));
        acceptDock = new FrameLayout(host);
        acceptDock.setVisibility(View.GONE);
        popup.addView(acceptDock, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams offerParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        offerParams.setMargins(dp(10), 0, dp(10), dp(10));
        controls.addView(popup, offerParams);

        TextView locate = host.text("◎", 23, "#0B3A78", true);
        locate.setContentDescription("Pusatkan ke lokasi saya");
        locate.setGravity(Gravity.CENTER);
        locate.setElevation(dp(5));
        locate.setBackground(host.round("#FFFFFF", dp(25)));
        locate.setOnClickListener(v -> centerDriver(true));
        FrameLayout.LayoutParams locateParams = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM | Gravity.END);
        locateParams.rightMargin = dp(12);
        locateParams.bottomMargin = dp(25);
        controls.addView(locate, locateParams);
        popup.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int margin = (popup.getVisibility() == View.VISIBLE ? popup.getHeight() : 0) + dp(21);
            if (locateParams.bottomMargin != margin) {
                locateParams.bottomMargin = margin;
                locate.setLayoutParams(locateParams);
            }
            updateMapPadding();
        });
        page.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> limitPopup());
        mapView.getMapAsync(this::ready);
    }

    private void openOrderQueue() {
        if (queueDialog!=null && queueDialog.isShowing()) return;
        ScrollView scroll=new ScrollView(host);
        LinearLayout rows=new LinearLayout(host);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(14),dp(6),dp(14),dp(10));
        scroll.addView(rows);
        android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(host)
                .setTitle("Antrean order").setView(scroll).setNegativeButton("Tutup",null).create();
        queueDialog=dialog;
        renderOrderQueue(dialog,rows,null,false);
        dialog.show();
        if (!queueScope.execute(()->{
            try {
                org.json.JSONObject response=DriverMessageApi.post(new SessionManager(host),
                        DriverApiConfig.BASE_URL+"transsend_contact.php",new org.json.JSONObject().put("action","list"),queueScope.owner);
                queueScope.post(()->renderOrderQueue(dialog,rows,response.optJSONArray("contacts"),!response.optBoolean("success")));
            } catch (Exception error) {
                queueScope.post(()->renderOrderQueue(dialog,rows,null,true));
            }
        })) renderOrderQueue(dialog,rows,null,true);
    }

    private void renderOrderQueue(android.app.AlertDialog dialog,LinearLayout rows,org.json.JSONArray contacts,boolean failed) {
        if (queueDialog!=dialog || destroyed) return;
        rows.removeAllViews();
        boolean waiting=contacts==null && !failed;
        rows.addView(host.text("Tawaran order",15,"#0B3A78",true));
        int count=0;
        if (state!=null && state.online && state.offers!=null) for (DriverOrder order:state.offers) {
            if (order.raw==null) continue;
            String key=host.offerCountdownController.key(order);
            if (host.offerCountdownController.remainingMillis(key)<=0) continue;
            String type=CallPeerIdentity.first(order.raw.optString("service_name"),order.raw.optString("order_type"),"Order");
            String address=CallPeerIdentity.first(order.raw.optString("pickup_address"),order.raw.optString("address"));
            queueRow(rows,type+(address.isEmpty()?"":" · "+address),()->{
                dialog.dismiss();
                if (liveKeys.contains(key) && host.offerCountdownController.remainingMillis(key)>0) selectOffer(key,true);
                else android.widget.Toast.makeText(host,"Tawaran sudah berakhir.",android.widget.Toast.LENGTH_SHORT).show();
            });
            count++;
        }
        if (count==0) rows.addView(host.text("Belum ada tawaran aktif.",13,"#405975",false));

        TextView title=host.text("Permintaan Trans Simpel & TransSend",15,"#0B3A78",true);
        title.setPadding(0,dp(12),0,0);
        rows.addView(title);
        int pending=0;
        if (contacts!=null) for (int i=0;i<contacts.length();i++) {
            org.json.JSONObject item=contacts.optJSONObject(i);
            if (item==null || !"pending".equals(item.optString("state"))) continue;
            String label="trans_simpel".equals(item.optString("service"))?"Trans Simpel":"TransSend";
            String peer=CallPeerIdentity.first(item.optString("peer_name"),"Customer");
            queueRow(rows,label+" · "+peer,()->{
                dialog.dismiss();
                host.startActivity(new Intent(host,DriverTransSendContactsActivity.class)
                        .putExtra("focus_contact",item.optString("id")));
            });
            pending++;
        }
        if (pending==0) rows.addView(host.text(waiting?"Memuat permintaan…":failed?
                "Permintaan belum bisa dimuat. Buka daftar untuk mencoba lagi.":"Belum ada permintaan aktif.",
                13,"#405975",false));
        queueRow(rows,"Buka daftar permintaan",()->{
            dialog.dismiss();
            host.startActivity(new Intent(host,DriverTransSendContactsActivity.class));
        });
    }

    private void queueRow(LinearLayout rows,String label,Runnable open) {
        TextView row=host.text(label+"  ›",15,"#0B3A78",true);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinHeight(dp(48));
        row.setPadding(dp(10),dp(6),dp(10),dp(6));
        row.setBackground(host.round("#EAF3FF",dp(14)));
        row.setOnClickListener(v->open.run());
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.topMargin=dp(5);
        rows.addView(row,lp);
    }

    private void ready(GoogleMap value) {
        if (destroyed || host.isFinishing()) return;
        map = value;
        map.getUiSettings().setMapToolbarEnabled(false);
        map.getUiSettings().setMyLocationButtonEnabled(false);
        map.setOnMarkerClickListener(marker -> {
            Object tag = marker.getTag();
            if (!(tag instanceof String) || !pins.containsKey(tag)) return false;
            DriverOrder assigned = assignedPickups.get(tag);
            if (assigned != null) host.openTrip(assigned);
            else selectOffer((String) tag, false);
            return true;
        });
        enableLocation();
        updateMapPadding();
        if (focusedOffer.isEmpty()) centerDriver(false);
        syncPins(true);
    }

    void present(DriverDashboardState snapshot) {
        state = snapshot;
        status.setText(snapshot.online ? "● Online" : "● Offline");
        status.setTextColor(snapshot.online
                ? (DriverThemeTokens.isDark(host) ? 0xFF86EFAC : 0xFF08774F)
                : DriverThemeTokens.textSecondary(host));
        boolean visible = host.orderSections.getVisibility() == View.VISIBLE;
        boolean wasVisible = popup.getVisibility() == View.VISIBLE;
        popup.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible && !wasVisible && !DevicePerformanceProfile.get(host).reduceMapMotion) {
            popup.setAlpha(0f);
            popup.setTranslationY(dp(12));
            popup.animate().alpha(1f).translationY(0).setDuration(220).start();
        } else if (!visible) {
            popup.animate().cancel();
            popup.setAlpha(1f);
            popup.setTranslationY(0f);
        }
        restoreAction();
        syncPins(false);
        limitPopup();
    }

    void requestedOffer(Intent intent) {
        if (intent == null) return;
        requestedId = textExtra(intent, "order_db_id");
        if (requestedId.isEmpty()) requestedId = textExtra(intent, "order_id");
        requestedToken = textExtra(intent, "dashboard_offer_token");
        requestedAssignment = textExtra(intent, "simpel_assigned_id");
    }

    private static String textExtra(Intent intent, String key) {
        String value = intent.getStringExtra(key);
        return value == null ? "" : value;
    }

    private void syncPins(boolean mapJustReady) {
        if (state == null) return;
        Map<String, DriverOrder> live = new LinkedHashMap<>();
        String preferred = "";
        if (state.online && state.offers != null) for (DriverOrder order : state.offers) {
            if (order.raw == null) continue;
            String key = host.offerCountdownController.key(order);
            if (host.offerCountdownController.remainingMillis(key) <= 0) continue;
            live.put(key, order);
            if (!requestedId.isEmpty()
                    && (requestedId.equals(order.id) || requestedId.equals(order.raw.optString("id")))
                    && (requestedToken.isEmpty() || requestedToken.equals(order.raw.optString("offer_action_token")))) {
                preferred = key;
            }
        }
        if (!preferred.isEmpty()) { requestedId = ""; requestedToken = ""; }
        java.util.List<String> nextKeys = new java.util.ArrayList<>(live.keySet());
        boolean newOffer = !liveKeys.containsAll(nextKeys);
        liveKeys = nextKeys;
        boolean activeShown = ACTIVE.equals(focusedOffer) && host.activeBox.getChildCount() > 0 && !newOffer;
        String next = !preferred.isEmpty() ? preferred : activeShown || live.containsKey(focusedOffer) ? focusedOffer
                : live.isEmpty() ? "" : live.keySet().iterator().next();
        boolean changed = !next.equals(focusedOffer);
        assignedPickups.clear();
        String assignment = "";
        if (state.activeOrders != null) for (DriverOrder order : state.activeOrders) {
            if (order.raw == null || order.raw.optInt("is_trans_simpel", 0) != 1
                    || !("driver_accepted".equals(order.status) || "taken".equals(order.status))) continue;
            String key = "active-pickup:" + order.source + ":" + order.id;
            assignedPickups.put(key, order);
            live.put(key, order);
            if (assignment.isEmpty() || requestedAssignment.equals(order.id)) assignment = key;
        }
        boolean focusAssignment = !assignment.isEmpty()
                && (next.isEmpty() || ACTIVE.equals(next) || !requestedAssignment.isEmpty())
                && (mapJustReady || changed || !assignment.equals(assignedFocus) || !requestedAssignment.isEmpty());
        if (focusAssignment) {
            next = ACTIVE;
            if (map != null) requestedAssignment = "";
        }
        assignedFocus = assignment;
        if (map != null) {
            pins.entrySet().removeIf(entry -> {
                if (live.containsKey(entry.getKey())) return false;
                entry.getValue().remove();
                return true;
            });
            for (Map.Entry<String, DriverOrder> entry : live.entrySet()) {
                LatLng point = pickup(entry.getValue().raw);
                Pin pin = pins.get(entry.getKey());
                if (point == null) {
                    if (pin != null) { pin.remove(); pins.remove(entry.getKey()); }
                } else if (pin == null) {
                    Pin created = new Pin(point, entry.getKey());
                    if (created.marker != null) pins.put(entry.getKey(), created);
                    else created.remove();
                } else pin.move(point);
            }
        }
        selectOffer(next, changed || mapJustReady || !preferred.isEmpty() || focusAssignment);
        updatePulse();
    }

    private LatLng pickup(org.json.JSONObject raw) {
        double lat = NavigationTargetPolicy.coord(raw, "pickup_lat", "user_lat");
        double lng = NavigationTargetPolicy.coord(raw, "pickup_lng", "user_lng");
        if (!NavigationTargetPolicy.valid(lat, lng)) {
            lat = raw.optDouble("sender_lat", Double.NaN);
            lng = raw.optDouble("sender_lng", Double.NaN);
        }
        return NavigationTargetPolicy.valid(lat, lng) ? new LatLng(lat, lng) : null;
    }

    private TextView tab(String label, Runnable action) {
        TextView tab = host.text(label, 12, "#0B3A78", true);
        tab.setGravity(Gravity.CENTER);
        tab.setOnClickListener(v -> action.run());
        return tab;
    }

    private void nextOffer() {
        if (liveKeys.isEmpty()) return;
        int next = (liveKeys.indexOf(focusedOffer) + 1) % liveKeys.size();
        selectOffer(liveKeys.get(next), true);
    }

    private void selectOffer(String key, boolean moveCamera) {
        boolean changed = !key.equals(focusedOffer);
        if (changed) restoreAction();
        focusedOffer = key;
        View selectedAction = null;
        for (int i = 0; i < host.offerBox.getChildCount(); i++) {
            View card = host.offerBox.getChildAt(i);
            boolean selected = key.equals(card.getTag());
            card.setVisibility(selected ? View.VISIBLE : View.GONE);
            if (selected && card != actionCard) {
                selectedAction = card.findViewWithTag(ACCEPT_ACTION);
                if (selectedAction != null) actionCard = card;
            }
        }
        boolean activeShown = key.isEmpty() || ACTIVE.equals(key);
        boolean hasActive = host.activeBox.getChildCount() > 0;
        host.activeBox.setVisibility(activeShown ? View.VISIBLE : View.GONE);
        tabs.setVisibility(!liveKeys.isEmpty() && (hasActive || liveKeys.size() > 1) ? View.VISIBLE : View.GONE);
        activeTab.setVisibility(hasActive ? View.VISIBLE : View.GONE);
        int offerIndex = liveKeys.indexOf(key);
        offersTab.setText(liveKeys.size() > 1 && offerIndex >= 0
                ? "Tawaran " + (offerIndex + 1) + "/" + liveKeys.size() + " ›"
                : "Tawaran (" + liveKeys.size() + ")");
        offersTab.setBackground(host.round(!activeShown ? "#F2F7FF" : "#FFFFFF", dp(16)));
        activeTab.setBackground(host.round(activeShown ? "#F2F7FF" : "#FFFFFF", dp(16)));
        if (host.priorityOrderTitle != null && host.priorityOrderTitle.getParent() instanceof View) {
            ((View) host.priorityOrderTitle.getParent()).setVisibility(activeShown ? View.VISIBLE : View.GONE);
        }
        if (selectedAction != null) {
            actionHome = (android.view.ViewGroup) selectedAction.getParent();
            actionParams = selectedAction.getLayoutParams();
            actionHome.removeView(selectedAction);
            acceptDock.removeAllViews();
            acceptDock.addView(selectedAction, new FrameLayout.LayoutParams(-1, dp(48)));
        } else if (changed || key.isEmpty() || host.offerBox.getChildCount() == 0) acceptDock.removeAllViews();
        acceptDock.setVisibility(acceptDock.getChildCount() == 0 ? View.GONE : View.VISIBLE);
        popup.setVisibility(key.isEmpty() && host.activeBox.getChildCount() == 0 ? View.GONE : View.VISIBLE);
        if (changed) offers.scrollTo(0, 0);
        limitPopup();
        Pin pin = pins.get(ACTIVE.equals(key) ? assignedFocus : key);
        if (moveCamera && pin != null) {
            centered = true;
            popup.post(() -> {
                if (map == null || !key.equals(focusedOffer)) return;
                updateMapPadding();
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(pin.marker.getPosition(), 15.6f));
            });
        }
    }

    private void restoreAction() {
        if (acceptDock.getChildCount() > 0) {
            View action = acceptDock.getChildAt(0);
            acceptDock.removeView(action);
            if (actionHome != null && actionCard != null && actionCard.getParent() == host.offerBox) {
                actionHome.addView(action, actionParams);
            }
        }
        actionHome = null;
        actionCard = null;
        actionParams = null;
    }

    void expire() {
        if (liveKeys.stream().anyMatch(key -> host.offerCountdownController.remainingMillis(key) <= 0)) syncPins(false);
    }

    private void limitPopup() {
        int available = page.getHeight();
        if (available <= 0) return;
        int cap = Math.max(dp(85), Math.round(available * .54f) - (acceptDock.getVisibility() == View.VISIBLE ? dp(51) : 0)
                - (tabs.getVisibility() == View.VISIBLE ? dp(44) : 0));
        offers.measure(View.MeasureSpec.makeMeasureSpec(Math.max(0, page.getWidth() - dp(20)), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST));
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) offers.getLayoutParams();
        int height = popup.getVisibility() == View.VISIBLE ? Math.min(cap, offers.getMeasuredHeight()) : 0;
        if (lp.height != height) { lp.height = height; offers.setLayoutParams(lp); }
        updateMapPadding();
    }

    private void updateMapPadding() {
        if (map == null) return;
        int bottom = popup.getVisibility() == View.VISIBLE ? popup.getHeight() + dp(21) : dp(20);
        if (bottom == appliedPadding) return;
        appliedPadding = bottom;
        map.setPadding(dp(6), dp(62), dp(6), bottom);
    }

    private boolean hasLocation() {
        return ContextCompat.checkSelfPermission(host, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(host, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @android.annotation.SuppressLint("MissingPermission")
    private void enableLocation() {
        if (map != null && hasLocation()) try { map.setMyLocationEnabled(true); } catch (SecurityException ignored) {}
    }

    @android.annotation.SuppressLint("MissingPermission")
    private void centerDriver(boolean requested) {
        if (map == null || (!requested && centered)) return;
        org.json.JSONObject cached = host.session.getLastLocationJson();
        double lat = cached.optDouble("latitude", Double.NaN), lng = cached.optDouble("longitude", Double.NaN);
        if (NavigationTargetPolicy.valid(lat, lng)) {
            setCenter(new LatLng(lat, lng), requested);
        } else if (!centered) map.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(-2.2, 120.3), 5.5f));
        if (!hasLocation()) {
            if (requested) host.ensureLocationReady();
            return;
        }
        try {
            if (locateRequest != null) locateRequest.cancel();
            locateRequest = new com.google.android.gms.tasks.CancellationTokenSource();
            com.google.android.gms.location.CurrentLocationRequest query =
                    new com.google.android.gms.location.CurrentLocationRequest.Builder()
                            .setPriority(com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                            .setMaxUpdateAgeMillis(30_000L).setDurationMillis(6000L).build();
            com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(host)
                    .getCurrentLocation(query, locateRequest.getToken()).addOnSuccessListener(host, location -> {
                        if (location == null || map == null || destroyed || !resumed
                                || (!requested && pins.containsKey(focusedOffer))) return;
                        setCenter(new LatLng(location.getLatitude(), location.getLongitude()), requested);
                    });
        } catch (SecurityException ignored) {}
    }

    private void setCenter(LatLng point, boolean animate) {
        centered = true;
        if (animate) map.animateCamera(CameraUpdateFactory.newLatLngZoom(point, 15.5f));
        else map.moveCamera(CameraUpdateFactory.newLatLngZoom(point, 15.5f));
    }

    private void showDetails() {
        host.startActivity(new Intent(host, DriverMenuActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    void start() { mapView.onStart(); }
    void resume() {
        mapView.onResume();
        resumed = true;
        enableLocation();
        centerDriver(false);
        updatePulse();
    }
    void pause() {
        resumed = false;
        if (locateRequest != null) locateRequest.cancel();
        updatePulse();
        mapView.onPause();
    }
    void stop() { mapView.onStop(); }
    void lowMemory() { mapView.onLowMemory(); }
    void save(Bundle out) { Bundle mapState = new Bundle(); mapView.onSaveInstanceState(mapState); out.putBundle("dashboard_map", mapState); }
    void destroy() {
        if (queueDialog!=null) queueDialog.dismiss();
        queueScope.close();
        destroyed = true;
        if (pulse != null) pulse.cancel();
        pins.clear();
        map = null;
        mapView.onDestroy();
    }

    private void updatePulse() {
        if (!resumed || pins.isEmpty()) {
            if (pulse != null) { pulse.cancel(); pulse = null; }
            return;
        }
        if (pulse != null) return;
        boolean lowerRate = DevicePerformanceProfile.get(host).reduceMapMotion;
        for (Pin pin : pins.values()) pin.frame(0f);
        pulse = ValueAnimator.ofFloat(0f, 1f);
        pulse.setDuration(2400L);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setInterpolator(new LinearInterpolator());
        pulse.addUpdateListener(animation -> {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastPulseAt < (lowerRate ? 66 : 32)) return;
            lastPulseAt = now;
            float time = (float) animation.getAnimatedValue();
            for (Pin pin : pins.values()) pin.frame(time);
        });
        pulse.start();
    }

    private final class Pin {
        final Marker marker;
        final Circle core;
        final Circle[] rings;
        Pin(LatLng point, String key) {
            core = map.addCircle(new CircleOptions().center(point).radius(28)
                    .fillColor(0x32F27547).strokeColor(0xB3E95635).strokeWidth(dp(2)).zIndex(3));
            rings = new Circle[]{wave(point), wave(point), wave(point)};
            frame(0f);
            boolean assigned = assignedPickups.containsKey(key);
            marker = map.addMarker(new MarkerOptions().position(point)
                    .title(assigned ? "Jemput pesanan Trans Simpel" : "Titik jemput")
                    .icon(BitmapDescriptorFactory.defaultMarker(assigned
                            ? BitmapDescriptorFactory.HUE_AZURE : BitmapDescriptorFactory.HUE_ORANGE)).zIndex(6));
            if (marker != null) marker.setTag(key);
        }
        void move(LatLng point) {
            if (marker.getPosition().equals(point)) return;
            marker.setPosition(point);
            core.setCenter(point);
            for (Circle ring : rings) ring.setCenter(point);
        }
        void frame(float time) {
            for (int i = 0; i < rings.length; i++) waveFrame(rings[i], (time + i / (float) rings.length) % 1f);
        }
        void remove() {
            if (marker != null) marker.remove();
            core.remove();
            for (Circle ring : rings) ring.remove();
        }
    }

    private Circle wave(LatLng point) {
        return map.addCircle(new CircleOptions().center(point).radius(30)
                .fillColor(Color.TRANSPARENT).strokeWidth(dp(2)).zIndex(2));
    }

    private void waveFrame(Circle circle, float time) {
        circle.setRadius(20 + 165 * time);
        float opacity = (1f - time) * (1f - time);
        circle.setStrokeColor(WAVE_COLOR | ((int) (205 * opacity) << 24));
        circle.setFillColor(WAVE_COLOR | ((int) (13 * opacity) << 24));
    }

    private int dp(int value) { return host.dp(value); }
}
