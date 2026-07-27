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
    private static final String API_URL = "https://transiva.my.id/server/getDriverReceipts.php";
    private static final int TIMEOUT_MS = 20000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private LinearLayout listBox;
    private ProgressBar progressBar;
    private SessionManager session;
    private String username = "";
    private JSONArray receipts = new JSONArray();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setupBars();
        session = new SessionManager(this);
        try { username = firstNonEmpty(session.getUsername(), session.getName()); } catch (Exception ignored) {}
        buildUi();
        loadReceipts();
    }

    private void setupBars() {
        try {
            getWindow().setStatusBarColor(Color.WHITE);
            getWindow().setNavigationBarColor(Color.WHITE);
            if (Build.VERSION.SDK_INT >= 23) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        } catch (Exception ignored) {}
    }

    private void buildUi() {
        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(Color.parseColor("#F3F8FF"));
        ScrollView scroll = new ScrollView(this);
        page.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(22), dp(16), dp(30));
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
        htxt.addView(text("Riwayat Transaksi", 23, "#0B3A78", true));
        Button refresh = outlineButton("Refresh");
        refresh.setOnClickListener(v -> loadReceipts());
        header.addView(refresh, new LinearLayout.LayoutParams(dp(104), dp(46)));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(14), 0, 0);
        root.addView(listBox, lp);

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

    private void loadReceipts() {
        if (username.length() == 0) {
            showInfo("Data Driver", "Data driver tidak ditemukan. Silakan login ulang.");
            renderEmpty("🧾", "Data driver tidak ditemukan", "Silakan kembali dan login ulang.");
            return;
        }
        progressBar.setVisibility(View.VISIBLE);
        listBox.removeAllViews();
        listBox.addView(emptyBox("🧾", "Memuat riwayat transaksi...", ""));
        new Thread(() -> {
            String body = "";
            try { body = get(API_URL + "?driver=" + enc(username) + "&v=" + System.currentTimeMillis()); } catch (Exception ignored) {}
            String finalBody = body;
            mainHandler.post(() -> {
                progressBar.setVisibility(View.GONE);
                renderList(finalBody);
            });
        }).start();
    }

    private void renderList(String body) {
        listBox.removeAllViews();
        try {
            JSONObject res = new JSONObject(body);
            if (!res.optBoolean("success", false)) {
                renderEmpty("⚠️", "Gagal Memuat Riwayat", res.optString("message", "Periksa koneksi internet lalu coba lagi."));
                return;
            }
            receipts = res.optJSONArray("receipts");
            if (receipts == null || receipts.length() == 0) {
                renderEmpty("🧾", "Belum ada nota", "Nota transaksi akan muncul setelah order selesai.");
                return;
            }
            for (int i = 0; i < receipts.length(); i++) {
                JSONObject item = receipts.optJSONObject(i);
                if (item != null) listBox.addView(receiptCard(item));
            }
        } catch (Exception e) {
            renderEmpty("⚠️", "Response tidak valid", "Server belum mengirim data nota yang benar.");
        }
    }

    private View receiptCard(JSONObject item) {
        LinearLayout c = card();
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setOnClickListener(v -> openDetail(item));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        c.addView(top, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        top.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        left.addView(text(firstNonEmpty(item.optString("order_code"), item.optString("order_id"), "-"), 18, "#0B3A78", true));
        TextView date = text("🕒 " + firstNonEmpty(item.optString("created_at"), "-"), 12, "#64748B", false);
        date.setPadding(0, dp(4), 0, 0);
        left.addView(date);

        TextView price = text(rupiah(item.optDouble("total_pendapatan", 0)), 16, "#16A34A", true);
        price.setGravity(Gravity.RIGHT);
        top.addView(price, new LinearLayout.LayoutParams(-2, -2));

        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#E2E8F0"));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, dp(1));
        dlp.setMargins(0, dp(12), 0, dp(10));
        c.addView(divider, dlp);

        c.addView(row("💸 Potongan", "- " + rupiah(item.optDouble("total_potongan", 0)), "#DC2626", false));
        c.addView(row("💳 Sisa Saldo", rupiah(item.optDouble("sisa_saldo", 0)), "#0F172A", false));

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

    private void openDetail(JSONObject item) {
        try {
            Intent i = new Intent(this, DriverReceiptDetailActivity.class);
            i.putExtra("receipt_json", item.toString());
            startActivity(i);
        } catch (Exception e) {
            showInfo("Nota", "Nota tidak ditemukan.");
        }
    }

    private LinearLayout row(String label, String value, String valueColor, boolean big) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = text(label, big ? 16 : 14, "#334155", false);
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
        TextView v = text(value, big ? 17 : 14, valueColor, true);
        v.setGravity(Gravity.RIGHT);
        r.addView(v, new LinearLayout.LayoutParams(-2, -2));
        r.setPadding(0, dp(5), 0, dp(5));
        return r;
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
        HttpURLConnection c = (HttpURLConnection) new URL(link).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setRequestMethod("GET");
        c.setRequestProperty("Accept", "application/json");
        InputStream is = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        String r = read(is);
        c.disconnect();
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
    private void showInfo(String title, String msg) { try { new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show(); } catch (Exception ignored) {} }
    private LinearLayout card() { LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setBackground(roundStroke("#FFFFFF", "#D7E6F8", dp(24), 1)); c.setElevation(dp(2)); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, 0, 0, dp(10)); c.setLayoutParams(lp); return c; }
    private TextView text(String s, int sp, String color, boolean bold) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(Color.parseColor(color)); if (bold) t.setTypeface(Typeface.DEFAULT_BOLD); return t; }
    private Button outlineButton(String s) { Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setTextColor(Color.parseColor("#0B7CFF")); b.setTextSize(14); b.setTypeface(Typeface.DEFAULT_BOLD); b.setBackground(roundStroke("#FFFFFF", "#9DCAFF", dp(17), 1)); return b; }
    private GradientDrawable roundStroke(String c, String s, int r, int w) { GradientDrawable g = new GradientDrawable(); g.setColor(Color.parseColor(c)); g.setCornerRadius(r); g.setStroke(dp(w), Color.parseColor(s)); return g; }
    private GradientDrawable roundGradient(String a, String b, int r) { GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{Color.parseColor(a), Color.parseColor(b)}); g.setCornerRadius(r); return g; }
    private String rupiah(double v) { return "Rp " + NumberFormat.getNumberInstance(new Locale("id", "ID")).format((long) v); }
    private String firstNonEmpty(String... vals) { if (vals == null) return ""; for (String v: vals) if (v != null && v.trim().length() > 0 && !"null".equalsIgnoreCase(v.trim())) return v.trim(); return ""; }
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }
}
