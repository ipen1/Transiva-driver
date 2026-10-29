package com.transiva.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.Locale;

public class DriverReceiptDetailActivity extends Activity {
    private LinearLayout root;
    private JSONObject data;
    private JSONObject receipt;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setupBars();
        loadReceipt();
        buildUi();
    }

    private void setupBars() {
        try {
            DriverSystemBars.setStatusBarColor(this, DriverThemeTokens.background(this));
            DriverSystemBars.setNavigationBarColor(this, DriverThemeTokens.background(this));
            
        } catch (Exception ignored) {}
    }

    private void loadReceipt() {
        try { data = new JSONObject(getIntent().getStringExtra("receipt_json")); } catch (Exception ignored) {}
        try { receipt = new JSONObject(data != null ? data.optString("receipt_json", "{}") : "{}"); } catch (Exception ignored) {}
        if(data!=null && data.optJSONObject("receipt_json")!=null)receipt=data.optJSONObject("receipt_json");
        if (receipt == null) receipt = new JSONObject();
    }

    private void buildUi() {
        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(DriverThemeTokens.color(this, "#F3F8FF"));
        ScrollView scroll = new ScrollView(this);
        page.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(22), dp(16), dp(30));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));
        render();
        setContentView(page);
        DriverAppSettings.apply(this);
    }

    private void render() {
        root.removeAllViews();
        if (data == null) {
            LinearLayout c = card();
            c.setGravity(Gravity.CENTER);
            c.setPadding(dp(18), dp(28), dp(18), dp(28));
            c.addView(centerText("🧾", 42, "#0B7CFF", true));
            c.addView(centerText("Nota tidak ditemukan", 19, "#0B3A78", true));
            TextView sub = centerText("Silakan kembali ke riwayat transaksi.", 13, "#64748B", false);
            sub.setPadding(0, dp(6), 0, dp(12));
            c.addView(sub);
            Button back = outlineButton("Kembali");
            back.setOnClickListener(v -> finish());
            c.addView(back, new LinearLayout.LayoutParams(-1, dp(52)));
            root.addView(c);
            return;
        }

        if(data.optBoolean("grouped_order_wallet",false)){renderGroupedWallet();return;}
        if ("wallet".equalsIgnoreCase(data.optString("entry_kind"))) {
            renderWalletDetail();
            return;
        }

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
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        header.addView(txt, new LinearLayout.LayoutParams(0, -2, 1));
        txt.addView(text("Detail Nota", 13, "#64748B", true));
        TextView orderReference=text(firstNonEmpty(data.optString("order_code"), data.optString("order_id"), "-"),14,"#0B3A78",true);
        orderReference.setTextIsSelectable(true);txt.addView(orderReference,new LinearLayout.LayoutParams(-1,-2));
        root.addView(header);

        LinearLayout success = card();
        success.setPadding(dp(16), dp(14), dp(16), dp(14));
        success.setBackground(roundStroke("#ECFDF5", "#86EFAC", dp(22), 1));
        success.addView(text("✅ Transaksi Selesai", 18, "#047857", true));
        TextView created = text(firstNonEmpty(data.optString("created_at"), "-"), 13, "#059669", false);
        created.setPadding(0, dp(4), 0, 0);
        success.addView(created);
        root.addView(success);

        String deliveryMode = firstNonEmpty(receipt.optString("delivery_mode"), data.optString("delivery_mode"), "standard").toLowerCase(Locale.US);
        boolean hasDeliveryMode = firstNonEmpty(receipt.optString("delivery_mode"), data.optString("delivery_mode")).length() > 0;
        boolean isFood = data.optInt("is_food", receipt.optInt("is_food", 0)) == 1 || hasDeliveryMode;
        String deliveryLabel = firstNonEmpty(receipt.optString("delivery_label"), data.optString("delivery_label"), deliveryMode.equals("hemat") ? "Pengantaran Hemat" : "Pengantaran Standar");
        String modeLabel = isFood ? " " + deliveryLabel : "";

        LinearLayout income = sectionCard();
        double voucherDiscount = num("voucher_discount");
        double promoSubsidy = num("promo_subsidy");
        double protectedGross = num("driver_gross_protected");
        if (protectedGross <= 0) protectedGross = num("ongkir");
        double customerContribution = num("customer_contribution");

        income.addView(row("🛵 Ongkir Normal" + modeLabel, rupiah(Math.max(0,num("ongkir")-receipt.optDouble("waiting_fee"))), "#0F172A", false));
        if (voucherDiscount > 0) {
            income.addView(row(isFood ? "👤 Total Dibayar Customer" : "👤 Dibayar Customer", rupiah(customerContribution), "#0F172A", false));
            income.addView(row("🏷️ Voucher Customer", "- " + rupiah(voucherDiscount), "#DC2626", false));
            income.addView(row("🛡️ Subsidi Transiva", "+ " + rupiah(promoSubsidy), "#16A34A", true));
        }
        if (num("merchant_order") > 0) income.addView(row("🍔 Orderan Merchant", rupiah(num("merchant_order")), "#0F172A", false));
        income.addView(divider());
        income.addView(row("Nilai Order (termasuk merchant)", rupiah(num("total_pendapatan")), "#16A34A", true));
        root.addView(income);
        SmartWaitingTextView waiting=new SmartWaitingTextView(this);waiting.bind(receipt.optJSONObject("smart_waiting")!=null?receipt.optJSONObject("smart_waiting"):data.optJSONObject("smart_waiting"));root.addView(waiting);

        if (voucherDiscount > 0) {
            LinearLayout protectedBox = sectionCard();
            protectedBox.setBackground(roundStroke("#ECFDF5", "#86EFAC", dp(22), 1));
            protectedBox.addView(text("🛡️ Pendapatan Dilindungi", 16, "#047857", true));
            TextView protectedText = text(isFood
                    ? "Voucher TransFood ditanggung Transiva. Nilai order merchant dan ongkir driver tetap dilindungi."
                    : "Diskon customer ditanggung Transiva dan tidak mengurangi ongkir normal driver.", 13, "#059669", false);
            protectedText.setPadding(0, dp(5), 0, 0);
            protectedBox.addView(protectedText);
            root.addView(protectedBox);
        }

        LinearLayout cut = sectionCard();
        cut.setBackground(roundStroke("#FFF7F7", "#FECACA", dp(22), 1));
        cut.addView(row("⚙️ Fee Aplikasi dari Ongkir" + modeLabel, "- " + rupiah(num("app_fee")), "#DC2626", false));
        cut.addView(row("🏪 Fee Gross Up Merchant", "- " + rupiah(num("merchant_grossup_fee")), "#DC2626", false));
        cut.addView(divider());
        cut.addView(row("Total Potongan", "- " + rupiah(num("total_potongan")), "#DC2626", true));
        if (data.has("driver_net") || receipt.has("driver_net") || num("ongkir") > 0) {
            cut.addView(divider());
            cut.addView(row("Pendapatan Bersih Driver", rupiah(data.has("driver_net") || receipt.has("driver_net") ? num("driver_net") : Math.max(0,num("ongkir")-num("total_potongan"))), "#16A34A", true));
        }
        root.addView(cut);

        LinearLayout bal = sectionCard();
        bal.setBackground(roundStroke("#F8FBFF", "#B9DBFF", dp(22), 1));
        bal.addView(row("💳 Saldo Sebelum", rupiah(num("saldo_sebelum")), "#0F172A", false));
        bal.addView(divider());
        bal.addView(row("Saldo setelah order", rupiah(num("sisa_saldo")), "#0B7CFF", true));
        root.addView(bal);
        addGroupedLedger();

        Button back = outlineButton("Kembali");
        back.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(52));
        blp.setMargins(0, dp(4), 0, 0);
        root.addView(back, blp);
    }

    private void renderWalletDetail() {
        String direction = data.optString("direction", "out").toLowerCase(Locale.US);
        boolean incoming = "in".equals(direction);
        String walletType = data.optString("type", "").toLowerCase(Locale.US);
        boolean promoSubsidy = "promo_subsidy".equals(walletType);
        boolean appFeeEntry = DriverIncomeSummary.isAppFee(walletType,data.optString("reference"));
        boolean escrowRelease = "escrow_release".equals(walletType);
        String counterpart = firstNonEmpty(data.optString("counterparty_username"), "Driver tidak diketahui");
        double amount = data.optDouble("amount", 0);
        double fee = data.optDouble("admin_fee", 0);

        LinearLayout header = card();
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(14), dp(16), dp(14));
        String walletIcon = promoSubsidy ? "🛡️" : (appFeeEntry ? "⚙️" : (escrowRelease ? "💳" : (incoming ? "💰" : "📤")));
        TextView icon = text(walletIcon, 30, "#FFFFFF", true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(roundGradient(incoming ? "#059669" : "#DC2626", incoming ? "#34D399" : "#FB7185", dp(22)));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(54), dp(54));
        ilp.setMargins(0, 0, dp(12), 0);
        header.addView(icon, ilp);
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        header.addView(txt, new LinearLayout.LayoutParams(0, -2, 1));
        String walletTitle = promoSubsidy ? "Subsidi Voucher Transiva" : (appFeeEntry ? "Fee Aplikasi" : (escrowRelease ? "Pembayaran Customer" : (incoming ? "Transfer Masuk" : "Transfer Keluar")));
        txt.addView(text(walletTitle, 13, "#64748B", true));
        TextView reference=text(firstNonEmpty(data.optString("reference"),"TRANSFER"),14,"#0B3A78",true);reference.setTextIsSelectable(true);txt.addView(reference,new LinearLayout.LayoutParams(-1,-2));
        root.addView(header);

        LinearLayout status = sectionCard();
        status.setBackground(roundStroke(incoming ? "#ECFDF5" : "#FFF7F7", incoming ? "#86EFAC" : "#FECACA", dp(22), 1));
        String statusText = promoSubsidy ? "✅ Subsidi Masuk ke Saldo" : (appFeeEntry ? "✅ Fee Aplikasi Tercatat" : (escrowRelease ? "✅ Pembayaran Dicairkan" : (incoming ? "✅ Saldo Diterima" : "✅ Saldo Berhasil Dikirim")));
        status.addView(text(statusText, 18, incoming ? "#047857" : "#B91C1C", true));
        TextView created = text(firstNonEmpty(data.optString("created_at"), "-"), 13, "#64748B", false);
        created.setPadding(0, dp(4), 0, 0);
        status.addView(created);
        root.addView(status);

        LinearLayout party = sectionCard();
        if (!promoSubsidy && !appFeeEntry && !escrowRelease) party.addView(row(incoming ? "👤 Diterima dari" : "👤 Dikirim ke", counterpart, "#0B3A78", true));
        String amountLabel = promoSubsidy ? "Subsidi Promo" : (appFeeEntry ? "Fee Aplikasi" : (escrowRelease ? "Dana Customer" : "Nominal Transfer"));
        party.addView(row(amountLabel, rupiah(amount), incoming ? "#16A34A" : "#DC2626", true));
        if (fee > 0) party.addView(row("Biaya Admin", "- " + rupiah(fee), "#DC2626", false));
        String note = firstNonEmpty(data.optString("note"));
        if (note.length() > 0) party.addView(row("Catatan", note, "#334155", false));
        root.addView(party);

        LinearLayout bal = sectionCard();
        bal.setBackground(roundStroke("#F8FBFF", "#B9DBFF", dp(22), 1));
        bal.addView(row("Saldo Sebelum", rupiah(data.optDouble("balance_before", 0)), "#0F172A", false));
        bal.addView(row("Saldo Setelah", rupiah(data.optDouble("balance_after", 0)), "#0B7CFF", true));
        root.addView(bal);
        addGroupedLedger();

        Button back = outlineButton("Kembali");
        back.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(52));
        blp.setMargins(0, dp(4), 0, 0);
        root.addView(back, blp);
    }

    private double num(String key) {
        if (data != null && data.has(key)) return data.optDouble(key, 0);
        if (receipt != null && receipt.has(key)) return receipt.optDouble(key, 0);
        return 0;
    }

    private LinearLayout sectionCard() {
        LinearLayout c = card();
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        return c;
    }

    private LinearLayout row(String label, String value, String valueColor, boolean total) {
        LinearLayout r = new LinearLayout(this);r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(0,dp(7),0,dp(7));
        r.addView(text(label,total?14:12,"#64748B",total),new LinearLayout.LayoutParams(-1,-2));
        TextView v=text(value,total?18:15,valueColor,true);v.setPadding(0,dp(3),0,0);v.setTextIsSelectable(true);
        r.addView(v,new LinearLayout.LayoutParams(-1,-2));return r;
    }
    private void addGroupedLedger(){
        org.json.JSONArray ledger=data.optJSONArray("grouped_wallet_entries");
        if(ledger==null||ledger.length()==0)return;
        LinearLayout box=sectionCard();box.addView(text("Mutasi saldo dalam order ini",15,"#0B3A78",true));
        box.addView(text("Rincian berikut sudah digabung pada satu kartu order.",12,"#64748B",false));
        for(int i=0;i<ledger.length();i++){
            JSONObject w=ledger.optJSONObject(i);if(w==null)continue;
            boolean incoming="in".equalsIgnoreCase(w.optString("direction"));
            String type=w.optString("type"),title="app_fee".equals(type)?"Fee aplikasi & merchant":"promo_subsidy".equals(type)?"Subsidi voucher":"escrow_release".equals(type)?"Pembayaran customer":"Mutasi saldo";
            box.addView(divider());box.addView(row(title,(incoming?"+ ":"− ")+rupiah(w.optDouble("amount")),incoming?"#16A34A":"#DC2626",false));
            box.addView(row("Referensi mutasi",w.optString("reference","-"),"#334155",false));
            box.addView(row("Saldo setelah mutasi",rupiah(w.optDouble("balance_after")),"#0F172A",false));
        }
        root.addView(box);
    }
    private void renderGroupedWallet(){
        LinearLayout box=sectionCard();box.addView(text("Mutasi saldo order",20,"#0B3A78",true));
        box.addView(row("Nomor order lengkap",data.optString("order_reference"),"#0B3A78",false));
        box.addView(row("Waktu",data.optString("created_at"),"#64748B",false));
        box.addView(row("Dana masuk saldo","+ "+rupiah(data.optDouble("wallet_credit")),"#16A34A",false));
        box.addView(row("Fee aplikasi","− "+rupiah(data.optDouble("wallet_fee")),"#DC2626",false));
        boolean incoming="in".equals(data.optString("direction"));
        box.addView(row("Perubahan saldo",(incoming?"+ ":"− ")+rupiah(data.optDouble("amount")),incoming?"#16A34A":"#DC2626",true));
        box.addView(row("Saldo setelah transaksi",rupiah(data.optDouble("balance_after")),"#0B7CFF",true));
        box.addView(text("Perubahan saldo hanya mencakup dana yang melewati Transiva Pay. Pembayaran tunai diterima langsung dari customer.",12,"#64748B",false));
        root.addView(box);addGroupedLedger();
        Button back=outlineButton("Kembali");back.setOnClickListener(v->finish());root.addView(back,new LinearLayout.LayoutParams(-1,dp(52)));
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(DriverThemeTokens.color(this, "#E2E8F0"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1));
        lp.setMargins(0, dp(6), 0, dp(6));
        v.setLayoutParams(lp);
        return v;
    }

    private LinearLayout card() { LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setBackground(roundStroke("#FFFFFF", "#D7E6F8", dp(24), 1)); c.setElevation(dp(2)); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, 0, 0, dp(10)); c.setLayoutParams(lp); return c; }
    private TextView centerText(String s, int sp, String color, boolean bold) { TextView t = text(s, sp, color, bold); t.setGravity(Gravity.CENTER); return t; }
    private TextView text(String s, int sp, String color, boolean bold) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(DriverThemeTokens.color(this, color)); if (bold) t.setTypeface(Typeface.DEFAULT_BOLD); return t; }
    private Button outlineButton(String s) { Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setTextColor(DriverThemeTokens.color(this, "#0B7CFF")); b.setTextSize(14); b.setTypeface(Typeface.DEFAULT_BOLD); b.setBackground(roundStroke("#FFFFFF", "#9DCAFF", dp(17), 1)); return b; }
    private GradientDrawable roundStroke(String c, String s, int r, int w) { GradientDrawable g = new GradientDrawable(); g.setColor(DriverThemeTokens.color(this, c)); g.setCornerRadius(r); g.setStroke(dp(w), DriverThemeTokens.color(this, s)); return g; }
    private GradientDrawable roundGradient(String a, String b, int r) { GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{DriverThemeTokens.color(this, a), DriverThemeTokens.color(this, b)}); g.setCornerRadius(r); return g; }
    private String rupiah(double v) { return "Rp " + NumberFormat.getNumberInstance(new Locale("id", "ID")).format((long) v); }
    private String firstNonEmpty(String... vals) { if (vals == null) return ""; for (String v: vals) if (v != null && v.trim().length() > 0 && !"null".equalsIgnoreCase(v.trim())) return v.trim(); return ""; }
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }
}
