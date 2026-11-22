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
    private final Button pickup;
    private final Button delivery;
    private final Button stop;
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
    private boolean available=true;

    TransSimpelRoutePanel(Activity activity, String id) {
        this.activity = activity;
        this.id = id;
        network = new DriverUiScope(activity);
        session = new SessionManager(activity);
        view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(12),dp(5),dp(12),dp(5));
        view.setBackground(surface("#FFFFFF", "#DCE8F5"));
        view.setVisibility(View.GONE);
        LinearLayout actions = new LinearLayout(activity);
        pickup = button("Jemput", () -> openPicker(PICKUP));
        delivery = button("Antar", () -> openPicker(DELIVERY));
        stop = button("+ Singgah", () -> openPicker(STOP));
        clearStop = button("Hapus", this::removeStop);
        style(pickup,"#E7F7F0","#096749");
        style(delivery,"#EAF1FF","#164C9C");
        style(stop,"#FFF4E1","#774508");
        style(clearStop,"#F3F6FA","#41556C");
        for (Button point : new Button[]{pickup, stop, delivery}) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,dp(44),1);
            params.setMargins(dp(2),0,dp(2),0);
            actions.addView(point,params);
        }
        briefButton=button("Percakapan Tiva AI",()->TivaSimpelBrief.show(activity,tivaBrief));
        briefButton.setVisibility(View.GONE);
        view.addView(briefButton,new LinearLayout.LayoutParams(-1,dp(44)));
        view.addView(actions);
        pickupAddress = routeLine();
        stopAddress = routeLine();
        deliveryAddress = routeLine();
        summary = routeLine();
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
        if (busy || route==null || !"accepted".equals(route.optString("state"))) return;
        JSONObject selected=route.optJSONObject("stop");
        if (selected==null) return;
        try {
            send("set",new JSONObject().put("point","stop").put("revision",route.optInt("revision"))
                    .put("previous",selected).put("location",JSONObject.NULL));
        } catch (org.json.JSONException error) {
            notice("Singgah belum dapat dihapus.");
        }
    }

    private TextView routeLine() {
        TextView line = new TextView(activity);
        line.setTextColor(0xFF21547A);
        line.setTextSize(13);
        line.setSingleLine(true);
        line.setEllipsize(TextUtils.TruncateAt.END);
        line.setPadding(dp(2), dp(2), dp(2), dp(2));
        view.addView(line);
        return line;
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
        boolean food="food".equals(kind());
        boolean send="send".equals(kind());
        pickup.setEnabled(editable && !busy && !food);
        stop.setVisibility(kind().isEmpty() || "ride".equals(kind())?View.VISIBLE:View.GONE);
        delivery.setEnabled(editable && !busy);
        stop.setEnabled(editable && !busy);
        boolean hasStop=route.optJSONObject("stop")!=null;
        stop.setText(hasStop?"Singgah":"+ Singgah");
        stop.setContentDescription(hasStop?"Ubah titik singgah":"Tambah titik singgah opsional");
        clearStop.setVisibility(editable && hasStop?View.VISIBLE:View.GONE);
        clearStop.setEnabled(!busy);
        clearStop.setContentDescription("Hapus titik singgah");
        long amount=route.optLong("total",0);
        pickupAddress.setText("Jemput  ·  "+address("pickup"));
        stopAddress.setText("Singgah  ·  "+address("stop"));
        stopAddress.setVisibility(hasStop?View.VISIBLE:View.GONE);
        deliveryAddress.setText("Antar  ·  "+address("delivery"));
        summary.setText(amount>0?(food?"Total makanan + ongkir":ordered?"Total":"Estimasi")+"  Rp "
                +NumberFormat.getIntegerInstance(new Locale("id","ID")).format(amount)+" · Tunai":"");
        boolean assistedPurchase=tivaBrief!=null
                && "shop".equals(kind());
        purchaseHint.setVisibility(assistedPurchase?View.VISIBLE:View.GONE);
        if (amount>0 && assistedPurchase) {
            summary.setText("Ongkir  Rp "+NumberFormat.getIntegerInstance(new Locale("id","ID")).format(amount)+" · Tunai");
        }
        summary.setVisibility(amount>0?View.VISIBLE:View.GONE);
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
        if (busy || route==null || !"accepted".equals(route.optString("state"))) return;
        String field=field(which);
        pickerRevision=route.optInt("revision");
        previous=route.optJSONObject(field);
        JSONObject seed=previous!=null?previous:route.optJSONObject(which==PICKUP?"delivery":"pickup");
        Intent intent=new Intent(activity,DriverSimpelPointActivity.class)
                .putExtra("point_kind",field)
                .putExtra("title",which==PICKUP?"Atur Titik Jemput":which==STOP?"Atur Titik Singgah":"Atur Titik Antar");
        if (seed!=null) intent.putExtra("lat",seed.optDouble("lat")).putExtra("lng",seed.optDouble("lng"));
        activity.startActivityForResult(intent,which);
    }

    boolean result(int request,int result,Intent data) {
        if (request!=PICKUP && request!=DELIVERY && request!=STOP) return false;
        if (result!=Activity.RESULT_OK || data==null || pickerRevision<0) return true;
        try {
            JSONObject payload=new JSONObject()
                    .put("point",field(request))
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
        busy=false;
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (reply!=null && !reply.optBoolean("available",true)) {
            available=false;
            view.setVisibility(View.GONE);
            return;
        }
        if (reply!=null && reply.has("tiva_brief")) {
            tivaBrief=reply.optJSONObject("tiva_brief");
            briefButton.setVisibility(tivaBrief!=null?View.VISIBLE:View.GONE);
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
