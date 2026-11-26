package com.transiva.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import java.text.NumberFormat;
import java.util.Locale;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

public final class DriverTransSendContactsActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::refresh;
    private DriverUiScope scope;
    private SessionManager session;
    private LinearLayout rows;
    private TextView state;
    private boolean visible;
    private boolean busy;
    private String rendered = "";

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        scope = new DriverUiScope(this);
        session = new SessionManager(this);
        if (!scope.alive()) { finish(); return; }
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(16));
        content.setBackgroundColor(Color.parseColor("#F3F8FF"));
        scroll.addView(content);
        TextView heading=label("Permintaan Trans Simpel",22);
        heading.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        content.addView(heading);
        state = label("Memuat permintaan…", 14);
        content.addView(state);
        LinearLayout actions = new LinearLayout(this);
        addAction(actions,"‹ Kembali",this::finish,false);
        addAction(actions,"Perbarui",this::refresh,false);
        content.addView(actions);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        content.addView(rows);
        setContentView(scroll);
        DriverAppSettings.apply(this);
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        refresh();
    }

    @Override protected void onPause() {
        visible = false;
        main.removeCallbacks(poll);
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (scope != null) scope.close();
        super.onDestroy();
    }

    private void refresh() {
        String contact = getIntent().getStringExtra("open_contact");
        if (TextUtils.isEmpty(contact)) request("list", "");
        else request("get", contact);
    }

    private void request(String action, String id) {
        if (!visible || busy || !scope.alive()) return;
        busy = true;
        if (!scope.execute(() -> {
            try {
                JSONObject data = new JSONObject().put("action", action).put("contact_id", id);
                JSONObject reply = DriverMessageApi.post(session,
                        DriverApiConfig.BASE_URL + "transsend_contact.php", data, scope.owner);
                scope.post(() -> {
                    busy = false;
                    if (!visible) return;
                    if (!reply.optBoolean("success")) {
                        if ("get".equals(action)) getIntent().removeExtra("open_contact");
                        failure(reply.optString("message", "Permintaan belum dapat dimuat."));
                    } else if ("list".equals(action)) {
                        render(reply.optJSONArray("contacts"));
                    } else if ("get".equals(action)) {
                        getIntent().removeExtra("open_contact");
                        JSONObject contact = reply.optJSONObject("contact");
                        if (contact != null && hasChat(contact)) {
                            openChat(contact);
                            finish();
                        } else {
                            refresh();
                        }
                    } else if ("accept".equals(action)) {
                        JSONObject contact = reply.optJSONObject("contact");
                        if (contact != null && hasChat(contact)) openChat(contact);
                        else failure("Permintaan sudah berubah. Perbarui daftar.");
                    } else {
                        refresh();
                    }
                    schedule();
                });
            } catch (Exception error) {
                scope.post(() -> failure("Koneksi terputus. Coba lagi."));
            }
        })) failure("Sistem sedang sibuk. Coba lagi.");
    }

    private void render(JSONArray entries) {
        int count = entries == null ? 0 : entries.length();
        state.setText(count == 0 ? "Belum ada permintaan aktif." : count + " permintaan aktif");
        String snapshot=entries==null?"":entries.toString();
        if (rendered.equals(snapshot)) return;
        rendered=snapshot;
        rows.removeAllViews();
        String focus=getIntent().getStringExtra("focus_contact");
        java.util.ArrayList<JSONObject> ordered=new java.util.ArrayList<>();
        for (int i=0;i<count;i++) {
            JSONObject item=entries.optJSONObject(i);
            if (item==null) continue;
            if (item.optString("id").equals(focus)) ordered.add(0,item);
            else ordered.add(item);
        }
        for (JSONObject entry:ordered) {
            boolean accepted = hasChat(entry);
            String name = entry.optString("peer_name", "Customer");
            String service = "trans_simpel".equals(entry.optString("service")) ? "Trans Simpel" : "TransSend";
            LinearLayout card=new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12),dp(11),dp(12),dp(8));
            card.setBackground(surface(0xFFFFFFFF,0xFFD8E5F3,16));
            LinearLayout.LayoutParams cardParams=new LinearLayout.LayoutParams(-1,-2);
            cardParams.topMargin=dp(8);
            rows.addView(card,cardParams);

            LinearLayout identity=new LinearLayout(this);
            identity.setGravity(Gravity.CENTER_VERTICAL);
            card.addView(identity);
            FrameLayout avatar=new FrameLayout(this);
            avatar.setBackground(surface(0xFFE7F0FF,0xFFE7F0FF,23));
            avatar.setClipToOutline(true);
            identity.addView(avatar,new LinearLayout.LayoutParams(dp(46),dp(46)));
            TextView initial=label(name.isEmpty()?"C":name.substring(0,name.offsetByCodePoints(0,1)).toUpperCase(Locale.ROOT),18);
            initial.setGravity(Gravity.CENTER);
            avatar.addView(initial,new FrameLayout.LayoutParams(-1,-1));
            ImageView photo=new ImageView(this);
            photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
            avatar.addView(photo,new FrameLayout.LayoutParams(-1,-1));
            String photoUrl=CallPeerIdentity.photoUrl(entry.optString("peer_photo"));
            if (photoUrl.startsWith("https://")) RemoteImageLoader.loadCenterCrop(photo,photoUrl,0);

            LinearLayout details=new LinearLayout(this);
            details.setOrientation(LinearLayout.VERTICAL);
            details.setPadding(dp(11),0,0,0);
            identity.addView(details,new LinearLayout.LayoutParams(0,-2,1));
            TextView title=label(name.isEmpty()?"Customer":name,16);
            title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            details.addView(title);
            int reviews=entry.optInt("peer_rating_count");
            String rating="Belum ada rating";
            if (reviews>0&&!entry.isNull("peer_rating")) {
                NumberFormat format=NumberFormat.getNumberInstance(new Locale("id","ID"));
                format.setMinimumFractionDigits(1);
                format.setMaximumFractionDigits(2);
                rating="★ "+format.format(entry.optDouble("peer_rating"))+" ("+reviews+")";
            }
            details.addView(label(rating,12));
            TextView status=label(accepted?"Chat aktif":"Menunggu jawaban",12);
            status.setTextColor(accepted?0xFF096749:0xFF164C9C);
            details.addView(status);

            JSONObject brief=entry.optJSONObject("tiva_brief");
            if (brief!=null) {
                String detail=brief.optString("detail");
                TextView request=label("Tiva AI · "+brief.optString("title")+(detail.isEmpty()?"":"\n"+detail),13);
                request.setPadding(0,dp(8),0,0);
                request.setMaxLines(3);
                request.setEllipsize(TextUtils.TruncateAt.END);
                card.addView(request);
                LinearLayout history=new LinearLayout(this);
                card.addView(history);
                addAction(history,"Lihat percakapan",()->TivaSimpelBrief.show(this,brief),false);
            }
            LinearLayout actions=new LinearLayout(this);
            actions.setGravity(Gravity.END);
            card.addView(actions);
            if (accepted) {
                addAction(actions,"Buka Chat",()->openChat(entry),true);
            } else {
                String id=entry.optString("id");
                addAction(actions,"Tolak",()->request("reject",id),false);
                addAction(actions,"Terima",()->new AlertDialog.Builder(this)
                        .setTitle("Terima permintaan "+service+"?")
                        .setMessage("Jika customer memesan saat Anda tersedia, pesanan langsung masuk ke pesanan aktif.")
                        .setNegativeButton("Kembali",null)
                        .setPositiveButton("Terima & Buka Chat",(d,w)->request("accept",id)).show(),true);
            }
        }
        DriverAppSettings.apply(this);
    }

    private void openChat(JSONObject entry) {
        startActivity(chatIntent(this, entry));
    }

    static boolean hasChat(JSONObject entry) {
        String state = entry.optString("state");
        return ("accepted".equals(state) || "ordered".equals(state)) && !entry.optString("room_id").isEmpty();
    }

    static Intent chatIntent(android.content.Context context, JSONObject entry) {
        return new Intent(context, DriverChatRoomActivity.class)
                .putExtra("order_source", "pickup_orders")
                .putExtra("order_type", "trans_simpel".equals(entry.optString("service")) ? "Trans Simpel" : "TransSend")
                .putExtra("order_status", "contact_accepted")
                .putExtra("order_id", entry.optString("order_id"))
                .putExtra("room_id", entry.optString("room_id"))
                .putExtra("participant_name", entry.optString("peer_name"))
                .putExtra("participant_photo", entry.optString("peer_photo"));
    }

    private void failure(String message) {
        busy = false;
        if (!visible) return;
        state.setText(message);
        schedule();
    }

    private void schedule() {
        main.removeCallbacks(poll);
        if (visible) main.postDelayed(poll, 5000);
    }

    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }

    private TextView label(String value, int size) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(Color.parseColor("#103769"));
        return label;
    }

    private GradientDrawable surface(int fill,int stroke,int radius) {
        GradientDrawable background=new GradientDrawable();
        background.setColor(fill);
        background.setCornerRadius(dp(radius));
        background.setStroke(dp(1),stroke);
        return background;
    }

    private void addAction(LinearLayout row,String label,Runnable click,boolean primary) {
        Button button=new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        button.setTextColor(primary?0xFFFFFFFF:0xFF164C9C);
        int fill=primary?0xFF075DCF:0xFFEAF2FE;
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33087ADF),surface(fill,fill,11),null));
        button.setOnClickListener(v->click.run());
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-2,dp(44));
        params.setMargins(dp(6),dp(6),0,0);
        row.addView(button,params);
    }
}
