package com.transiva.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
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
    private final Activity activity;
    private final String id;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::refresh;
    private final LinearLayout view;
    private final Button pickup;
    private final Button delivery;
    private final Button quoteRetry;
    private final TextView summary;
    private final DriverUiScope network;
    private final SessionManager session;
    private JSONObject route;
    private JSONObject previous;
    private int pickerRevision = -1;
    private int quotedRevision = -1;
    private boolean active;
    private boolean busy;
    private boolean available=true;

    TransSimpelRoutePanel(Activity activity, String id) {
        this.activity = activity;
        this.id = id;
        network = new DriverUiScope(activity);
        session = new SessionManager(activity);
        view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.VERTICAL);
        int gap = Math.round(activity.getResources().getDisplayMetrics().density * 8);
        view.setPadding(gap,0,gap,0);
        view.setVisibility(View.GONE);
        LinearLayout actions = new LinearLayout(activity);
        pickup = button("Titik Jemput", () -> openPicker(PICKUP));
        delivery = button("Titik Antar", () -> openPicker(DELIVERY));
        actions.addView(pickup,new LinearLayout.LayoutParams(0,-2,1));
        actions.addView(delivery,new LinearLayout.LayoutParams(0,-2,1));
        view.addView(actions);
        summary = new TextView(activity);
        summary.setTextSize(13);
        summary.setTextColor(Color.parseColor("#21547A"));
        summary.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        view.addView(summary);
        quoteRetry = button("Hitung Ulang Ongkir", () -> {
            quotedRevision = -1;
            calculateIfReady();
        });
        view.addView(quoteRetry);
    }

    static String contactId(String orderId,String roomId) {
        if (orderId != null && orderId.matches("PUD[0-9A-F]{24}")) return orderId;
        if (roomId != null && roomId.matches("ROOM-PUD[0-9A-F]{24}")) return roomId.substring(5);
        return "";
    }

    View view() { return view; }

    void resume() { active = true; refresh(); }
    void pause() { active = false; main.removeCallbacks(poll); }
    void close() { pause(); network.close(); }

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
        b.setOnClickListener(v -> click.run());
        return b;
    }

    private void refresh() {
        if (active && !busy && available) send("get",null);
    }

    private void render(JSONObject update) {
        int revision=update.optInt("revision");
        if (route!=null && revision<route.optInt("revision")) return;
        if (route!=null && !route.isNull("total") && update.isNull("total")) quotedRevision=-1;
        route=update;
        String state=route.optString("state");
        boolean editable="accepted".equals(state);
        boolean ordered="ordered".equals(state);
        view.setVisibility(editable || ordered ? View.VISIBLE : View.GONE);
        pickup.setEnabled(editable && !busy);
        delivery.setEnabled(editable && !busy);
        long amount=route.optLong("total",0);
        String text="Jemput · "+address("pickup")+"\nAntar · "+address("delivery");
        if (amount>0) text+="\nTotal · Rp "+NumberFormat.getIntegerInstance(new Locale("id","ID")).format(amount)+" · Tunai";
        if (ordered) text+="\nPesanan dibuat";
        summary.setText(text);
        boolean both=route.optJSONObject("pickup")!=null && route.optJSONObject("delivery")!=null;
        quoteRetry.setVisibility(editable && both && amount==0 && quotedRevision==revision ? View.VISIBLE : View.GONE);
        quoteRetry.setEnabled(!busy);
    }

    private String address(String key) {
        JSONObject point=route.optJSONObject(key);
        return point==null?"Belum diatur":point.optString("address");
    }

    private void calculateIfReady() {
        if (!active || busy || route==null || !"accepted".equals(route.optString("state"))
                || route.optJSONObject("pickup")==null || route.optJSONObject("delivery")==null
                || !route.isNull("total")) return;
        int revision=route.optInt("revision");
        if (revision==quotedRevision) return;
        quotedRevision=revision;
        send("quote",null);
    }

    private void openPicker(int which) {
        if (busy || route==null || !"accepted".equals(route.optString("state"))) return;
        String field=which==PICKUP?"pickup":"delivery";
        pickerRevision=route.optInt("revision");
        previous=route.optJSONObject(field);
        JSONObject seed=previous!=null?previous:route.optJSONObject(which==PICKUP?"delivery":"pickup");
        Intent intent=new Intent(activity,DriverSimpelPointActivity.class)
                .putExtra("title",which==PICKUP?"Atur Titik Jemput":"Atur Titik Antar");
        if (seed!=null) intent.putExtra("lat",seed.optDouble("lat")).putExtra("lng",seed.optDouble("lng"));
        activity.startActivityForResult(intent,which);
    }

    boolean result(int request,int result,Intent data) {
        if (request!=PICKUP && request!=DELIVERY) return false;
        if (result!=Activity.RESULT_OK || data==null || pickerRevision<0) return true;
        try {
            JSONObject payload=new JSONObject()
                    .put("point",request==PICKUP?"pickup":"delivery")
                    .put("revision",pickerRevision)
                    .put("previous",previous==null?JSONObject.NULL:previous)
                    .put("location",new JSONObject().put("lat",data.getDoubleExtra("lat",0))
                            .put("lng",data.getDoubleExtra("lng",0)).put("address",data.getStringExtra("address")));
            pickerRevision=-1;
            if (busy) queuedPoint=payload;
            else send("set",payload);
        } catch (Exception error) { notice("Titik belum dapat disimpan."); }
        return true;
    }

    private JSONObject queuedPoint;

    private void send(String action,JSONObject data) {
        busy=true;
        main.removeCallbacks(poll);
        if (route!=null) render(route);
        if ("quote".equals(action)) summary.append("\nMenghitung ongkir…");
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
        busy=false;
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (reply!=null && !reply.optBoolean("available",true)) {
            available=false;
            view.setVisibility(View.GONE);
            return;
        }
        if (reply != null && reply.optJSONObject("route") != null) render(reply.optJSONObject("route"));
        else if (route!=null) render(route);
        if (failure!=null && !"get".equals(action)) notice(failure);
        if (queuedPoint!=null) {
            JSONObject point=queuedPoint;
            queuedPoint=null;
            send("set",point);
            return;
        }
        if (active) {
            calculateIfReady();
            if (!busy && (route==null || "accepted".equals(route.optString("state")))) main.postDelayed(poll,3000);
        }
    }

    private void notice(String message) {
        if (!activity.isFinishing()) Toast.makeText(activity,message,Toast.LENGTH_LONG).show();
    }

    private JSONObject request(JSONObject data) throws Exception {
        return DriverMessageApi.post(session,DriverApiConfig.BASE_URL+"trans_simpel.php",data,network.owner);
    }
    private boolean submit(Runnable work) { return network.execute(work); }
    private void post(Runnable work) { network.post(work); }
}
