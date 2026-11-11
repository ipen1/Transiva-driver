package com.transiva.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class OrderChatStyle {
    interface Fetch { JSONObject get(String query) throws Exception; }
    private static final ThreadPoolExecutor LOOKUPS = new ThreadPoolExecutor(1, 2, 20,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(12));
    private final Activity activity;
    private final Set<Long> callIds = new HashSet<>();
    private CallVisuals.Avatar avatar;
    private String photo = "";

    OrderChatStyle(Activity activity) { this.activity = activity; }
    private boolean dark() { return DriverAppSettings.isDarkMode(activity); }
    private int dp(int n) { return Math.round(n * activity.getResources().getDisplayMetrics().density); }

    void backdrop(View view) {
        view.setBackground(gradient(dark() ? 0xff0a1425 : 0xffeaf3ff,
                dark() ? 0xff192337 : 0xfff3f5fa, 0));
    }

    void addAvatar(LinearLayout header, String name, String initialPhoto) {
        photo = "";
        avatar = new CallVisuals.Avatar(activity);
        avatar.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
        lp.setMarginStart(dp(3));
        header.addView(avatar, lp);
        profile(null, name, initialPhoto);
    }

    void profile(JSONObject response, String fallbackName, String fallbackPhoto) {
        if (avatar == null) return;
        JSONObject peer = response == null ? null : response.optJSONObject("peer_profile");
        String name = peer == null ? fallbackName : CallPeerIdentity.first(peer.optString("name"), fallbackName);
        avatar.name(name);
        String supplied = peer == null ? fallbackPhoto : CallPeerIdentity.first(peer.optString("photo"), fallbackPhoto);
        String url = CallPeerIdentity.photoUrl(supplied);
        if (url == null || !url.startsWith("https://")) url = "";
        if (url.equals(photo)) return;
        photo = url;
        avatar.setImageDrawable(null);
        if(!url.isEmpty())RemoteImageLoader.loadCenterCrop(avatar,url,0);
    }

    static String time(String raw) { return raw.length() >= 16 ? raw.substring(11, 16) : ""; }
    private static boolean stamped(JSONObject item, String key) {
        String value = item.optString(key, "").trim();
        return !value.isEmpty() && !"null".equalsIgnoreCase(value);
    }

    void receipt(TextView view, JSONObject item, boolean mine) {
        String time = time(item.optString("created_at", ""));
        boolean read = mine && (item.optInt("is_read", 0) == 1 || stamped(item,"read_at"));
        boolean delivered = mine && (read || item.optInt("is_delivered", 0) == 1);
        String marks = !mine ? "" : delivered ? "  ✓✓" : "  ✓";
        SpannableString text = new SpannableString(time + marks);
        if (mine && !marks.isEmpty()) {
            text.setSpan(new ForegroundColorSpan(read ? 0xff9cf3ff : 0xffdae4f0),
                    time.length(), text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        view.setTextColor(mine ? 0xffe1ebfa : dark() ? 0xffbbc6d4 : 0xff516078);
        view.setText(text);
        view.setTag(read);
        view.setContentDescription(time + (!mine ? "" : read ? " Dibaca" : delivered ? " Notifikasi diterima" : " Terkirim"));
    }

    void decorate(LinearLayout wrapper, JSONObject item, boolean mine) {
        LinearLayout bubble = new LinearLayout(activity);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(10), dp(6), dp(10), dp(5));
        GradientDrawable fill = mine ? gradient(0xff1754b9, 0xff124384, 0)
                : gradient(dark() ? 0xff2e3948 : 0xfff6f7fa, dark() ? 0xff222c39 : 0xffe1e6ed, 0);
        float r = dp(15), tail = dp(4);
        fill.setCornerRadii(mine ? new float[]{r,r,tail,tail,r,r,r,r}
                : new float[]{tail,tail,r,r,r,r,r,r});
        bubble.setBackground(fill);
        while (wrapper.getChildCount() > 0) {
            View child = wrapper.getChildAt(0);
            android.view.ViewGroup.LayoutParams previous = child.getLayoutParams();
            wrapper.removeViewAt(0);
            bubble.addView(child, previous);
        }
        wrapper.addView(bubble, new LinearLayout.LayoutParams(-2, -2));
        wrapper.setTag(new Item(item.optString("created_at"), item.optInt("id"), 0));
    }

    void appendMeta(LinearLayout wrapper, TextView view) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = Gravity.END;
        view.setPadding(dp(3), dp(2), 0, 0);
        wrapper.addView(view, lp);
    }

    static String receiptIds(android.util.SparseArray<TextView> views){
        StringBuilder ids=new StringBuilder();
        int added=0;
        for(int i=views.size()-1;i>=0&&added<100;i--){
            if(Boolean.TRUE.equals(views.valueAt(i).getTag()))continue;
            added++;
            if(ids.length()>0)ids.append(',');
            ids.append(views.keyAt(i));
        }
        return ids.toString();
    }

    void reset() { callIds.clear(); }

    boolean mergeCalls(LinearLayout box, JSONObject response) {
        JSONArray events = response.optJSONArray("call_events");
        boolean added = false;
        if (events != null) for (int n=0; n<events.length(); n++) {
            JSONObject call = events.optJSONObject(n);
            if (call == null || call.optLong("id") <= 0 || !callIds.add(call.optLong("id"))) continue;
            if(box.getChildCount()==1&&box.getChildAt(0) instanceof TextView
                    &&((TextView)box.getChildAt(0)).getText().toString().startsWith("Belum ada pesan"))
                box.removeViewAt(0);
            boolean mine = call.optInt("outgoing", 0) == 1;
            LinearLayout wrapper = new LinearLayout(activity);
            wrapper.setOrientation(LinearLayout.VERTICAL);
            wrapper.setGravity(mine ? Gravity.END : Gravity.START);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(3), 0, dp(3));
            wrapper.addView(label(callTitle(call), 14, mine, true));
            wrapper.addView(label(callDetail(call), 12, mine, false));
            TextView timestamp = label("", 10, mine, false);
            receipt(timestamp, call, false);
            if (mine) timestamp.setTextColor(0xffdae8ff);
            appendMeta(wrapper, timestamp);
            decorate(wrapper, call, mine);
            wrapper.setTag(new Item(call.optString("created_at"), 0, call.optLong("id")));
            box.addView(wrapper, lp);
            added = true;
        }
        // Also re-sort when a message page arrives after earlier call cards.
        if (!callIds.isEmpty()) {
            ArrayList<View> rows = new ArrayList<>();
            int first = -1;
            for (int i=0; i<box.getChildCount(); i++) {
                View child=box.getChildAt(i);
                if (!(child.getTag() instanceof Item)) continue;
                if (first<0) first=i;
                rows.add(child);
            }
            rows.sort(Comparator.comparing((View row)->((Item)row.getTag()).time)
                    .thenComparingLong(row->((Item)row.getTag()).callId)
                    .thenComparingInt(row->((Item)row.getTag()).messageId));
            for (int i=0; i<rows.size(); i++) {
                View row=rows.get(i);
                int index=first+i;
                if (box.getChildAt(index)==row) continue;
                android.view.ViewGroup.LayoutParams lp=row.getLayoutParams();
                box.removeView(row);
                box.addView(row,index,lp);
            }
        }
        return added;
    }

    private String callTitle(JSONObject call) {
        return (call.optInt("outgoing",0)==1 ? "↗  " : "↙  ")
                + ("video".equals(call.optString("call_type")) ? "Panggilan video" : "Panggilan suara");
    }

    private String callDetail(JSONObject call) {
        if ("rejected".equals(call.optString("status"))) return "Ditolak";
        if (!stamped(call,"accepted_at")) return "Tidak terjawab";
        long seconds = Math.max(0, call.optLong("duration_seconds"));
        String duration = seconds < 60 ? seconds + " dtk" : seconds/60 + " mnt " + seconds%60 + " dtk";
        return (call.optInt("outgoing",0)==1 ? "Keluar" : "Masuk") + " · " + duration;
    }

    void overflow(LinearLayout header, Runnable search, Fetch fetch, LinearLayout messages) {
        TextView more = label("⋮", 24, false, true);
        more.setGravity(Gravity.CENTER);
        more.setContentDescription("Opsi percakapan");
        header.addView(more, new LinearLayout.LayoutParams(dp(42), dp(48)));
        more.setOnClickListener(v -> {
            PopupMenu menu = new PopupMenu(activity, more);
            menu.getMenu().add("Cari pesan").setOnMenuItemClickListener(item -> {
                if (search != null) search.run(); else lookup(fetch, messages, false);
                return true;
            });
            menu.getMenu().add("Riwayat panggilan").setOnMenuItemClickListener(item -> {
                lookup(fetch, messages, true);
                return true;
            });
            menu.show();
        });
    }

    private void lookup(Fetch fetch, LinearLayout messages, boolean calls) {
        LinearLayout pane = new LinearLayout(activity);
        pane.setOrientation(LinearLayout.VERTICAL);
        pane.setPadding(dp(16), dp(4), dp(16), dp(8));
        EditText query = new EditText(activity);
        query.setSingleLine(true);
        query.setHint("Kata dalam pesan");
        query.setTextColor(dark() ? Color.WHITE : 0xff162337);
        if (!calls) pane.addView(query);
        ScrollView scroll = new ScrollView(activity);
        LinearLayout results = new LinearLayout(activity);
        results.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(results);
        pane.addView(scroll,new LinearLayout.LayoutParams(-1,dp(290)));
        AlertDialog dialog = PremiumDialogs.builder(activity).setTitle(calls ? "Riwayat panggilan" : "Cari pesan")
                .setView(pane).setNegativeButton("Tutup",null)
                .setPositiveButton(calls ? "Muat ulang" : "Cari",null).create();
        dialog.show();
        PremiumDialogs.applyPremiumStyle(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String term=query.getText().toString().trim();
            if (!calls && term.length()<2) {query.setError("Minimal 2 karakter");return;}
            loadLookup(fetch, messages, dialog, results, calls, term, 0);
        });
        if (calls) loadLookup(fetch, messages, dialog, results, true, "", 0);
    }

    private void loadLookup(Fetch fetch, LinearLayout messages, AlertDialog dialog, LinearLayout rows,
                            boolean calls, String term, long before) {
        if (before==0) rows.removeAllViews();
        Button action = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        action.setEnabled(false);
        try {
            LOOKUPS.execute(() -> {
                JSONObject payload=null;
                try {
                    String params=calls ? "&calls_only=1&calls_before="+before
                            : "&chat_query="+URLEncoder.encode(term,"UTF-8")+"&search_before="+before;
                    payload=fetch.get(params);
                } catch (Exception ignored) {}
                final JSONObject result=payload;
                new Handler(Looper.getMainLooper()).post(()->{
                    if(activity.isFinishing()||activity.isDestroyed()||!dialog.isShowing())return;
                    action.setEnabled(true);
                    JSONArray list=result==null?null:result.optJSONArray(calls?"call_events":"search_results");
                    if(list==null){rows.addView(label("Belum dapat memuat. Coba lagi.",13,false,false));return;}
                    if(list.length()==0&&before==0)rows.addView(label(calls?"Belum ada riwayat panggilan.":"Pesan tidak ditemukan.",13,false,false));
                    long cursor=0;
                    for(int i=0;i<list.length();i++){
                        JSONObject item=list.optJSONObject(i);if(item==null)continue;
                        cursor=item.optLong("id");
                        String created=item.optString("created_at");
                        String content=calls?callTitle(item)+"\n"+callDetail(item):preview(item.optString("message"));
                        TextView row=label(content+"\n"+created,13,false,false);
                        row.setPadding(0,dp(8),0,dp(8));rows.addView(row);
                        if(!calls) row.setOnClickListener(v->{
                            if(messages==null)return;
                            for(int n=0;n<messages.getChildCount();n++){
                                View target=messages.getChildAt(n);
                                if(target.getTag() instanceof Item&&((Item)target.getTag()).messageId==item.optInt("id")
                                        &&messages.getParent() instanceof ScrollView){
                                    dialog.dismiss();
                                    ((ScrollView)messages.getParent()).smoothScrollTo(0,target.getTop());
                                    break;
                                }
                            }
                        });
                    }
                    if(cursor>0&&result.optBoolean(calls?"call_has_more":"search_has_more")){
                        final long next=cursor;
                        Button more=new Button(activity);more.setText("Muat sebelumnya");more.setAllCaps(false);
                        rows.addView(more);more.setOnClickListener(v->{rows.removeView(more);loadLookup(fetch,messages,dialog,rows,calls,term,next);});
                    }
                });
            });
        }catch(java.util.concurrent.RejectedExecutionException busy){
            action.setEnabled(true);rows.addView(label("Antrean sibuk. Coba lagi.",13,false,false));
        }
    }

    private static String preview(String value){
        if(value.startsWith("[[IMAGE"))return "Foto";
        if(value.startsWith("[[VOICE"))return "Pesan suara";
        if(value.startsWith("[[STICKER"))return "Stiker";
        return value;
    }

    private TextView label(String value,int size,boolean mine,boolean bold){
        TextView view=new TextView(activity);view.setText(value);view.setTextSize(size);
        view.setTextColor(mine?Color.WHITE:dark()?0xfff0f4fb:0xff162337);
        if(bold)view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }
    private GradientDrawable gradient(int a,int b,int radius){
        GradientDrawable bg=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{a,b});
        bg.setCornerRadius(dp(radius));return bg;
    }

    private static final class Item {
        final String time;
        final int messageId;
        final long callId;
        Item(String time,int messageId,long callId){this.time=time;this.messageId=messageId;this.callId=callId;}
    }
}
