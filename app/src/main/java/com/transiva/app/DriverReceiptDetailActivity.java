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
            getWindow().setStatusBarColor(Color.WHITE);
            getWindow().setNavigationBarColor(Color.WHITE);
            if (Build.VERSION.SDK_INT >= 23) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        } catch (Exception ignored) {}
    }

    private void loadReceipt() {
        try { data = new JSONObject(getIntent().getStringExtra("receipt_json")); } catch (Exception ignored) {}
        try { receipt = new JSONObject(data != null ? data.optString("receipt_json", "{}") : "{}"); } catch (Exception ignored) {}
        if (receipt == null) receipt = new JSONObject();
    }

    private void buildUi() {
        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(Color.parseColor("#F3F8FF"));
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
        txt.addView(text(firstNonEmpty(data.optString("order_code"), data.optString("order_id"), "-"), 22, "#0B3A78", true));
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
        income.addView(row("🛵 Pendapatan Ongkir" + modeLabel, rupiah(num("ongkir")), "#0F172A", false));
        income.addView(row("🍔 Orderan Merchant", rupiah(num("merchant_order")), "#0F172A", false));
        income.addView(divider());
        income.addView(row("Total Pendapatan", rupiah(num("total_pendapatan")), "#16A34A", true));
        root.addView(income);

        LinearLayout cut = sectionCard();
        cut.setBackground(roundStroke("#FFF7F7", "#FECACA", dp(22), 1));
        cut.addView(row("⚙️ Fee Aplikasi dari Ongkir" + modeLabel, "- " + rupiah(num("app_fee")), "#DC2626", false));
        cut.addView(row("🏪 Fee Gross Up Merchant", "- " + rupiah(num("merchant_grossup_fee")), "#DC2626", false));
        cut.addView(divider());
        cut.addView(row("Total Potongan", "- " + rupiah(num("total_potongan")), "#DC2626", true));
        root.addView(cut);

        LinearLayout bal = sectionCard();
        bal.setBackground(roundStroke("#F8FBFF", "#B9DBFF", dp(22), 1));
        bal.addView(row("💳 Saldo Sebelum", rupiah(num("saldo_sebelum")), "#0F172A", false));
        bal.addView(row("💰 Saldo Saat Ini", rupiah(num("saldo_saat_ini")), "#0F172A", false));
        bal.addView(divider());
        bal.addView(row("Sisa Saldo", rupiah(num("sisa_saldo")), "#0B7CFF", true));
        root.addView(bal);

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
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(7), 0, dp(7));
        TextView l = text(label, total ? 16 : 14, "#334155", total);
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
        TextView v = text(value, total ? 18 : 15, valueColor, true);
        v.setGravity(Gravity.RIGHT);
        r.addView(v, new LinearLayout.LayoutParams(-2, -2));
        return r;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(Color.parseColor("#E2E8F0"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1));
        lp.setMargins(0, dp(6), 0, dp(6));
        v.setLayoutParams(lp);
        return v;
    }

    private LinearLayout card() { LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setBackground(roundStroke("#FFFFFF", "#D7E6F8", dp(24), 1)); c.setElevation(dp(2)); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, 0, 0, dp(10)); c.setLayoutParams(lp); return c; }
    private TextView centerText(String s, int sp, String color, boolean bold) { TextView t = text(s, sp, color, bold); t.setGravity(Gravity.CENTER); return t; }
    private TextView text(String s, int sp, String color, boolean bold) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(Color.parseColor(color)); if (bold) t.setTypeface(Typeface.DEFAULT_BOLD); return t; }
    private Button outlineButton(String s) { Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setTextColor(Color.parseColor("#0B7CFF")); b.setTextSize(14); b.setTypeface(Typeface.DEFAULT_BOLD); b.setBackground(roundStroke("#FFFFFF", "#9DCAFF", dp(17), 1)); return b; }
    private GradientDrawable roundStroke(String c, String s, int r, int w) { GradientDrawable g = new GradientDrawable(); g.setColor(Color.parseColor(c)); g.setCornerRadius(r); g.setStroke(dp(w), Color.parseColor(s)); return g; }
    private GradientDrawable roundGradient(String a, String b, int r) { GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{Color.parseColor(a), Color.parseColor(b)}); g.setCornerRadius(r); return g; }
    private String rupiah(double v) { return "Rp " + NumberFormat.getNumberInstance(new Locale("id", "ID")).format((long) v); }
    private String firstNonEmpty(String... vals) { if (vals == null) return ""; for (String v: vals) if (v != null && v.trim().length() > 0 && !"null".equalsIgnoreCase(v.trim())) return v.trim(); return ""; }
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }
}
