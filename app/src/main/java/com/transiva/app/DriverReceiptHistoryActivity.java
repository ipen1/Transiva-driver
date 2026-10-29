package com.transiva.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.text.TextUtils;
import java.util.ArrayList;
import java.util.List;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.Locale;

public class DriverReceiptHistoryActivity extends Activity {
    private DriverUiScope uiScope;
    private static final String API_URL = "https://transiva.my.id/server/getDriverReceipts.php";
    private static final int TIMEOUT_MS = 20000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private LinearLayout listBox;
    private ProgressBar progressBar;
    private SessionManager session;
    private String username = "";
    private JSONArray receipts = new JSONArray();
    private String filter="Semua";
    private int visibleCount=10;
    private boolean loading=false;
    private LinearLayout filters;
    private TextView loadMore;
    private ScrollView mainScroll;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setupBars();
        session = new SessionManager(this);
        try { username = firstNonEmpty(session.getUsername(), session.getName()); } catch (Exception ignored) {}
        uiScope=new DriverUiScope(this);
        buildUi();
        loadReceipts();
    }

    private void setupBars() {
        try {
            DriverSystemBars.setStatusBarColor(this, DriverThemeTokens.background(this));
            DriverSystemBars.setNavigationBarColor(this, DriverThemeTokens.background(this));
            
        } catch (Exception ignored) {}
    }

    private void buildUi() {
        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(DriverThemeTokens.color(this, "#F3F8FF"));
        ScrollView scroll = new ScrollView(this); mainScroll=scroll;
        page.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(22));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        LinearLayout header = card();
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView icon = text("🧾", 30, "#FFFFFF", true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(roundGradient("#086BFF", "#2EA2FF", dp(22)));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(54), dp(54));
        ilp.setMargins(0, 0, dp(12), 0);
        header.addView(icon, ilp);
        LinearLayout htxt = new LinearLayout(this);
        htxt.setOrientation(LinearLayout.VERTICAL);
        header.addView(htxt, new LinearLayout.LayoutParams(0, -2, 1));
        htxt.addView(text("Driver", 13, "#64748B", true));
        htxt.addView(text("Riwayat transaksi", 16, "#0B3A78", true));
        Button refresh = outlineButton("↻");
        refresh.setContentDescription("Muat ulang riwayat transaksi");
        refresh.setOnClickListener(v -> loadReceipts());
        header.addView(refresh, new LinearLayout.LayoutParams(dp(48), dp(42)));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        filters=new LinearLayout(this);filters.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(-1,-2);fp.topMargin=dp(12);
        root.addView(filters,fp);buildFilters();
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(14), 0, 0);
        root.addView(listBox, lp);

        loadMore=text("Muat 10 transaksi berikutnya ↓",13,"#0B7CFF",true);
        loadMore.setGravity(Gravity.CENTER);loadMore.setPadding(dp(8),dp(12),dp(8),dp(12));
        loadMore.setVisibility(View.GONE);
        loadMore.setOnClickListener(v->{visibleCount+=10;renderCached();});
        root.addView(loadMore);
        Button back = outlineButton("Kembali");
        back.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(52));
        blp.setMargins(0, dp(8), 0, 0);
        root.addView(back, blp);

        progressBar = new ProgressBar(this);
        progressBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(52), dp(52));
        plp.gravity = Gravity.CENTER;
        page.addView(progressBar, plp);
        setContentView(page);
        DriverAppSettings.apply(this);
    }

    private void buildFilters(){
        filters.removeAllViews();
        for(String f:new String[]{"Semua","Masuk","Keluar"}){
            TextView t=text(f,12,f.equals(filter)?"#FFFFFF":"#0B3A78",true);
            t.setGravity(Gravity.CENTER);t.setPadding(dp(10),dp(9),dp(10),dp(9));
            t.setBackground(roundStroke(f.equals(filter)?"#0B7CFF":"#EAF4FF","#B9DBFF",dp(14),1));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);
            lp.rightMargin=dp(6);filters.addView(t,lp);
            t.setOnClickListener(v->{filter=f;visibleCount=10;buildFilters();renderCached();});
        }
    }
    private void loadReceipts(){
        if(loading)return;
        if(username.length()==0){
            showInfo("Data Driver","Data driver tidak ditemukan. Silakan login ulang.");
            if(receipts.length()==0)renderEmpty("🧾","Data driver tidak ditemukan","Silakan login ulang.");
            return;
        }
        loading=true;
        if(receipts.length()==0)progressBar.setVisibility(View.VISIBLE);
        uiScope.execute(()->{
            String body="";
            try{body=get(API_URL+"?v="+System.currentTimeMillis());}catch(Exception ignored){}
            final String result=body;
            uiScope.post(()->{
                loading=false;progressBar.setVisibility(View.GONE);
                if(result.isEmpty()){
                    if(receipts.length()==0)renderEmpty("⚠️","Gagal Memuat Riwayat","Periksa koneksi lalu coba lagi.");
                    else android.widget.Toast.makeText(this,"Sinkronisasi gagal, data terakhir tetap ditampilkan",android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                try{
                    JSONObject res=new JSONObject(result);
                    if(!res.optBoolean("success",false)){
                        if(receipts.length()==0)renderEmpty("⚠️","Gagal Memuat Riwayat",res.optString("message","Coba lagi."));
                        return;
                    }
                    JSONArray next=res.optJSONArray("receipts");if(next==null)next=new JSONArray();
                    if(!next.toString().equals(receipts.toString())){
                        receipts=next;renderCached();
                    }else if(listBox.getChildCount()==0)renderCached();
                }catch(Exception e){
                    if(receipts.length()==0)renderEmpty("⚠️","Response tidak valid","Periksa koneksi server.");
                }
            });
        });
    }
    private boolean matchesFilter(JSONObject item){
        if("Semua".equals(filter))return true;
        if(!"wallet".equalsIgnoreCase(item.optString("entry_kind")))
            return "Masuk".equals(filter) || value(item,"total_potongan")>0;
        return "Masuk".equals(filter)=="in".equalsIgnoreCase(item.optString("direction"));
    }

    private JSONArray groupedReceipts(){
        List<DriverTransactionGrouping.Entry> rows=new ArrayList<>();
        for(int i=0;i<receipts.length();i++){
            JSONObject item=receipts.optJSONObject(i);if(item==null)item=new JSONObject();
            JSONObject nested=nestedReceipt(item),order=nested.optJSONObject("order");
            rows.add(new DriverTransactionGrouping.Entry(!"wallet".equalsIgnoreCase(item.optString("entry_kind")),
                    item.optString("type"),item.optString("reference"),item.optString("order_code"),
                    item.optString("order_id"),order==null?"":order.optString("order_id")));
        }
        JSONArray result=new JSONArray();
        for(DriverTransactionGrouping.Group group:DriverTransactionGrouping.group(rows)){
            try{
                JSONObject original=receipts.optJSONObject(group.primary);if(original==null)continue;
                JSONObject item=new JSONObject(original.toString());
                JSONArray ledger=new JSONArray();
                double credit=0,debit=0,fee=0;long newestId=Long.MIN_VALUE;
                JSONObject newest=null;
                for(int index:group.members){
                    JSONObject w=receipts.optJSONObject(index);if(w==null)continue;
                    ledger.put(w);
                    double amount=Math.max(0,w.optDouble("amount",0));
                    if("in".equalsIgnoreCase(w.optString("direction")))credit+=amount;else debit+=amount;
                    if("app_fee".equalsIgnoreCase(w.optString("type")))fee+=amount;
                    if(newest==null||w.optLong("id",0)>newestId){newest=w;newestId=w.optLong("id",0);}
                }
                if(ledger.length()>0)item.put("grouped_wallet_entries",ledger);
                if("wallet".equalsIgnoreCase(item.optString("entry_kind")) && !DriverTransactionGrouping.orderKey(item.optString("type"),item.optString("reference")).isEmpty()){
                    item.put("grouped_order_wallet",true);
                    item.put("order_reference",DriverTransactionGrouping.orderKey(item.optString("type"),item.optString("reference")));
                    item.put("wallet_credit",credit).put("wallet_debit",debit).put("wallet_fee",fee);
                    item.put("amount",Math.abs(credit-debit)).put("direction",credit>=debit?"in":"out");
                    if(newest!=null)item.put("balance_after",newest.optDouble("balance_after",0));
                }
                result.put(item);
            }catch(Exception ignored){
                result.put(receipts.optJSONObject(group.primary));
                for(int index:group.members)if(index!=group.primary)result.put(receipts.optJSONObject(index));
            }
        }
        return result;
    }
    private JSONObject nestedReceipt(JSONObject item){
        JSONObject nested=item.optJSONObject("receipt_json");if(nested!=null)return nested;
        try{return new JSONObject(item.optString("receipt_json","{}"));}catch(Exception ignored){return new JSONObject();}
    }
    private double value(JSONObject item,String key){
        if(item.has(key)&&!item.isNull(key))return item.optDouble(key,0);
        return nestedReceipt(item).optDouble(key,0);
    }
    private TextView referenceText(String full){
        TextView t=text("No. order · "+DriverTransactionGrouping.shortReference(full),12,"#64748B",false);
        t.setSingleLine(true);t.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        t.setContentDescription("Nomor order lengkap: "+full);return t;
    }
    private void renderCached(){
        int scrollY=mainScroll.getScrollY();listBox.removeAllViews();
        int shown=0,total=0;
        JSONArray display=groupedReceipts();
        for(int i=0;i<display.length();i++){
            JSONObject item=display.optJSONObject(i);
            if(item==null||!matchesFilter(item))continue;
            total++;
            if(shown<visibleCount){listBox.addView(receiptCard(item));shown++;}
        }
        if(total==0)renderEmpty("🧾","Tidak ada transaksi","Belum ada transaksi untuk filter ini.");
        loadMore.setVisibility(total>shown?View.VISIBLE:View.GONE);
        mainScroll.post(()->mainScroll.scrollTo(0,scrollY));
    }

    private View receiptCard(JSONObject item) {
        boolean walletEntry = "wallet".equalsIgnoreCase(item.optString("entry_kind"));
        if (walletEntry) return walletCard(item);

        LinearLayout c = card();
        c.setPadding(dp(12), dp(10), dp(12), dp(10));
        c.setOnClickListener(v -> openDetail(item));

        String service=item.optInt("is_food",0)==1?"TransFood / TransShop":"TransRide";
        c.addView(text(service+" · Selesai",15,"#0B3A78",true));
        c.addView(referenceText(firstNonEmpty(item.optString("order_code"),item.optString("order_id"),"-")));
        c.addView(text(firstNonEmpty(item.optString("created_at"),"-"),11,"#64748B",false));
        c.addView(transactionDivider());
        double gross=value(item,"total_pendapatan"),cuts=value(item,"total_potongan");
        JSONArray mutations=item.optJSONArray("grouped_wallet_entries");
        double recordedFee=0;boolean hasRecordedFee=false;
        if(mutations!=null)for(int i=0;i<mutations.length();i++){
            JSONObject w=mutations.optJSONObject(i);
            if(w!=null&&"app_fee".equalsIgnoreCase(w.optString("type"))){hasRecordedFee=true;recordedFee+=w.optDouble("amount",0);}
        }
        if(hasRecordedFee&&Math.abs(recordedFee-cuts)>0.5){
            c.addView(text("Nominal fee di mutasi berbeda dari nota. Periksa rincian.",12,"#DC2626",true));
            c.addView(row("Fee tercatat di saldo","− "+rupiah(recordedFee),"#DC2626",true));
        }
        c.addView(row("Nilai order",rupiah(gross),"#0F172A",false));
        c.addView(row("Potongan aplikasi & merchant","− "+rupiah(cuts),"#DC2626",false));
        JSONObject detail=nestedReceipt(item);
        double net=item.has("driver_net")?item.optDouble("driver_net"):detail.has("driver_net")?detail.optDouble("driver_net"):Math.max(0,value(item,"ongkir")-cuts);
        c.addView(row("Pendapatan driver",rupiah(net),"#16A34A",true));
        c.addView(row("Saldo setelah order",rupiah(value(item,"sisa_saldo")),"#0F172A",false));
        if(item.optJSONArray("grouped_wallet_entries")!=null){
            TextView note=text("Biaya sudah termasuk dalam rincian order ini.",11,"#64748B",false);
            note.setPadding(0,dp(6),0,0);c.addView(note);
        }

        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
        flp.setMargins(0, dp(12), 0, 0);
        c.addView(footer, flp);
        String badgeText = item.optInt("is_food", 0) == 1 ? "🍔 Food Order" : "🛵 Kurir Order";
        TextView badge = text(badgeText, 12, "#0B7CFF", true);
        badge.setPadding(dp(10), dp(6), dp(10), dp(6));
        badge.setBackground(roundStroke("#EAF4FF", "#B9DBFF", dp(16), 1));
        footer.addView(badge, new LinearLayout.LayoutParams(0, -2, 1));
        footer.addView(text("→", 24, "#0B7CFF", true));
        return c;
    }

    private View walletCard(JSONObject item) {
        String direction = item.optString("direction", "out").toLowerCase(Locale.US);
        boolean incoming = "in".equals(direction);
        String counterpart = firstNonEmpty(item.optString("counterparty_username"), "Driver tidak diketahui");
        double amount = item.optDouble("amount", 0);
        double fee = item.optDouble("admin_fee", 0);

        LinearLayout c = card();
        c.setPadding(dp(12), dp(10), dp(12), dp(10));
        c.setOnClickListener(v -> openDetail(item));

        String reference=firstNonEmpty(item.optString("reference"),"TRANSFER");
        boolean appFee=DriverIncomeSummary.isAppFee(item.optString("type"),reference);
        boolean grouped=item.optBoolean("grouped_order_wallet",false);
        String type=item.optString("type");
        String title=grouped?"Mutasi saldo order":appFee?"Biaya layanan aplikasi":"promo_subsidy".equals(type)?"Subsidi promo":"escrow_release".equals(type)?"Pembayaran customer":incoming?"Transfer masuk":"Transfer keluar";
        c.addView(text(title,15,"#0B3A78",true));
        c.addView(referenceText(grouped?item.optString("order_reference"):reference));
        c.addView(text(firstNonEmpty(item.optString("created_at"),"-"),11,"#64748B",false));
        c.addView(transactionDivider());
        if(grouped){
            c.addView(row("Dana masuk saldo","+ "+rupiah(item.optDouble("wallet_credit")),"#16A34A",false));
            c.addView(row("Fee aplikasi","− "+rupiah(item.optDouble("wallet_fee")),"#DC2626",false));
            c.addView(row("Perubahan saldo",(incoming?"+ ":"− ")+rupiah(amount),incoming?"#16A34A":"#DC2626",true));
        }else{
            c.addView(row("Nominal",(incoming?"+ ":"− ")+rupiah(amount),incoming?"#16A34A":"#DC2626",true));
            if(!appFee&&!"promo_subsidy".equals(type)&&!"escrow_release".equals(type))c.addView(stackedRow(incoming?"Diterima dari":"Dikirim ke",counterpart));
            if(fee>0)c.addView(row("Biaya admin","− "+rupiah(fee),"#DC2626",false));
        }
        c.addView(row("Saldo setelah transaksi",rupiah(item.optDouble("balance_after",0)),"#0F172A",false));

        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
        flp.setMargins(0, dp(12), 0, 0);
        c.addView(footer, flp);
        String badgeText = grouped ? "Rincian satu order" : appFee ? "Fee Aplikasi" : title;
        String fill = incoming ? "#ECFDF5" : "#FFF7F7";
        String stroke = incoming ? "#86EFAC" : "#FECACA";
        String color = incoming ? "#047857" : "#DC2626";
        TextView badge = text(badgeText, 12, color, true);
        badge.setPadding(dp(10), dp(6), dp(10), dp(6));
        badge.setBackground(roundStroke(fill, stroke, dp(16), 1));
        footer.addView(badge, new LinearLayout.LayoutParams(0, -2, 1));
        footer.addView(text("→", 24, "#0B7CFF", true));
        return c;
    }

    private View transactionDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(DriverThemeTokens.color(this, "#E2E8F0"));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, dp(1));
        dlp.setMargins(0, dp(12), 0, dp(10));
        divider.setLayoutParams(dlp);
        return divider;
    }

    private void openDetail(JSONObject item) {
        try {
            Intent i = new Intent(this, DriverReceiptDetailActivity.class);
            i.putExtra("receipt_json", item.toString());
            startActivity(i);
        } catch (Exception e) {
            showInfo("Nota", "Nota tidak ditemukan.");
        }
    }

    private LinearLayout stackedRow(String label,String value){
        LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.VERTICAL);r.setPadding(0,dp(5),0,dp(5));
        r.addView(text(label,12,"#64748B",false));
        TextView v=text(value,14,"#0B3A78",true);v.setTextIsSelectable(true);r.addView(v,new LinearLayout.LayoutParams(-1,-2));return r;
    }
    private LinearLayout row(String label, String value, String valueColor, boolean big) {
        LinearLayout r = new LinearLayout(this);r.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = text(label,big?14:12,"#334155",false);
        r.addView(l,new LinearLayout.LayoutParams(0,-2,1));
        TextView v=text(value,big?16:14,valueColor,true);v.setGravity(Gravity.RIGHT);v.setPadding(dp(8),0,0,0);
        r.addView(v,new LinearLayout.LayoutParams(0,-2,1));
        r.setPadding(0,dp(5),0,dp(5));return r;
    }

    private void renderEmpty(String icon, String title, String sub) {
        listBox.removeAllViews();
        listBox.addView(emptyBox(icon, title, sub));
    }

    private View emptyBox(String icon, String title, String sub) {
        LinearLayout c = card();
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(18), dp(26), dp(18), dp(26));
        TextView ic = text(icon, 38, "#0B7CFF", true);
        ic.setGravity(Gravity.CENTER);
        c.addView(ic);
        TextView t = text(title, 18, "#0B3A78", true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(8), 0, 0);
        c.addView(t);
        if (sub != null && sub.length() > 0) {
            TextView s = text(sub, 13, "#64748B", false);
            s.setGravity(Gravity.CENTER);
            s.setPadding(0, dp(5), 0, 0);
            c.addView(s);
        }
        return c;
    }

    private String get(String link) throws Exception {
        HttpURLConnection c = com.transiva.app.driver.data.DriverConnectionRepository.open(link);
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setRequestMethod("GET");
        c.setRequestProperty("Accept", "application/json");
        uiScope.owner.pin(c);
        InputStream is = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        String r = read(is);
        uiScope.owner.check();uiScope.owner.release(c);c.disconnect();
        return r;
    }
    private String read(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }
    private String enc(String v) { try { return URLEncoder.encode(v == null ? "" : v, "UTF-8"); } catch (Exception e) { return ""; } }
    private void showInfo(String title, String msg) { try { PremiumDialogs.builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show(); } catch (Exception ignored) {} }
    private LinearLayout card() { LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setBackground(roundStroke("#FFFFFF", "#D7E6F8", dp(24), 1)); c.setElevation(dp(2)); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, 0, 0, dp(10)); c.setLayoutParams(lp); return c; }
    private TextView text(String s, int sp, String color, boolean bold) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(DriverThemeTokens.color(this, color)); if (bold) t.setTypeface(Typeface.DEFAULT_BOLD); return t; }
    private Button outlineButton(String s) { Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setTextColor(DriverThemeTokens.color(this, "#0B7CFF")); b.setTextSize(14); b.setTypeface(Typeface.DEFAULT_BOLD); b.setBackground(roundStroke("#FFFFFF", "#9DCAFF", dp(17), 1)); return b; }
    private GradientDrawable roundStroke(String c, String s, int r, int w) { GradientDrawable g = new GradientDrawable(); g.setColor(DriverThemeTokens.color(this, c)); g.setCornerRadius(r); g.setStroke(dp(w), DriverThemeTokens.color(this, s)); return g; }
    private GradientDrawable roundGradient(String a, String b, int r) { GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{DriverThemeTokens.color(this, a), DriverThemeTokens.color(this, b)}); g.setCornerRadius(r); return g; }
    private String rupiah(double v) { return "Rp " + NumberFormat.getNumberInstance(new Locale("id", "ID")).format((long) v); }
    private String firstNonEmpty(String... vals) { if (vals == null) return ""; for (String v: vals) if (v != null && v.trim().length() > 0 && !"null".equalsIgnoreCase(v.trim())) return v.trim(); return ""; }
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }
    @Override protected void onDestroy(){if(uiScope!=null)uiScope.close();super.onDestroy();}
}
