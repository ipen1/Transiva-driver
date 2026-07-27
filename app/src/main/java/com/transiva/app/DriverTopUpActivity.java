package com.transiva.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.Locale;

public class DriverTopUpActivity extends Activity {

    private static final String BASE_URL = "https://transiva.my.id/";
    private static final String PREF_NAME = "transiva";
    private static final int TIMEOUT_MS = 25000;
    private static final int REQ_PICK_IMAGE = 7201;
    private static final int MIN_DEPOSIT = 10000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private LinearLayout root;
    private ProgressBar progressBar;
    private EditText amountInput;
    private TextView balanceText, pendingText, proofNameText, statusText;
    private ImageView qrisImage;
    private Button submitButton;

    private String username = "";
    private String role = "driver";
    private Uri proofUri = null;
    private int selectedAmount = 0;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            getWindow().setStatusBarColor(Color.WHITE);
            getWindow().setNavigationBarColor(Color.WHITE);
            if (android.os.Build.VERSION.SDK_INT >= 23) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        } catch (Exception ignored) {}
        loadSession();
        buildLayout();
        loadBalanceAndPending();
    }

    private void loadSession() {
        try {
            SessionManager session = new SessionManager(this);
            username = firstNonEmpty(session.getUsername(), session.getName());
            role = firstNonEmpty(session.getRole(), "driver");
        } catch (Exception ignored) {}
        if (username.length() == 0) {
            try {
                SharedPreferences sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
                username = firstNonEmpty(sp.getString("username", ""), sp.getString("player_username", ""), sp.getString("user_username", ""));
                role = firstNonEmpty(sp.getString("role", ""), sp.getString("player_role", ""), "driver");
            } catch (Exception ignored) {}
        }
    }

    private void buildLayout() {
        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(Color.parseColor("#F7FAFF"));
        ScrollView scroll = new ScrollView(this);
        page.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(30));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));
        progressBar = new ProgressBar(this);
        progressBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams pLp = new FrameLayout.LayoutParams(dp(52), dp(52));
        pLp.gravity = Gravity.CENTER;
        page.addView(progressBar, pLp);
        setContentView(page);
        DriverAppSettings.apply(this);
        buildTopBar();
        buildBalanceCard();
        buildAmountCard();
        buildQrisCard(false);
    }

    private void buildTopBar() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, dp(16));
        root.addView(row, new LinearLayout.LayoutParams(-1, -2));
        TextView back = text("‹", 34, "#0B3A78", true);
        back.setGravity(Gravity.CENTER);
        back.setBackground(round("#FFFFFF", dp(18)));
        back.setOnClickListener(v -> finish());
        row.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, 0, 0);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        col.addView(text("Deposit Driver", 22, "#0B3A78", true));
        col.addView(text("Top up saldo driver lewat QRIS", 12, "#64748B", false));
    }

    private void buildBalanceCard() {
        LinearLayout card = card();
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackground(roundGradient("#086BFF", "#2EA2FF", dp(24)));
        addWithMargin(card, 0, 0, 0, dp(14));
        card.addView(text("💳 Saldo Driver", 13, "#EAF4FF", true));
        balanceText = text("Memuat saldo...", 25, "#FFFFFF", true);
        balanceText.setPadding(0, dp(6), 0, 0);
        card.addView(balanceText);
        pendingText = text("Pending: memuat...", 12, "#EAF4FF", false);
        pendingText.setPadding(0, dp(8), 0, 0);
        card.addView(pendingText);
        TextView user = text(username, 12, "#EAF4FF", false);
        user.setPadding(0, dp(7), 0, 0);
        card.addView(user);
    }

    private void buildAmountCard() {
        LinearLayout card = card();
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        addWithMargin(card, 0, 0, 0, dp(14));
        card.addView(text("Nominal Deposit", 16, "#0B3A78", true));
        TextView min = text("Minimal deposit Rp 10.000", 12, "#64748B", false);
        min.setPadding(0, dp(4), 0, dp(12));
        card.addView(min);
        amountInput = new EditText(this);
        amountInput.setSingleLine(true);
        amountInput.setTextSize(18);
        amountInput.setTypeface(Typeface.DEFAULT_BOLD);
        amountInput.setTextColor(Color.parseColor("#0F172A"));
        amountInput.setHintTextColor(Color.parseColor("#94A3B8"));
        amountInput.setHint("Contoh: 50000");
        amountInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        amountInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        amountInput.setPadding(dp(16), 0, dp(16), 0);
        amountInput.setBackground(roundStroke("#FFFFFF", "#D7E6F8", dp(18), 1));
        card.addView(amountInput, new LinearLayout.LayoutParams(-1, dp(54)));
        LinearLayout quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams qLp = new LinearLayout.LayoutParams(-1, -2);
        qLp.setMargins(0, dp(12), 0, 0);
        card.addView(quick, qLp);
        addQuickAmount(quick, 10000); addQuickAmount(quick, 50000); addQuickAmount(quick, 100000);
        Button next = primaryButton("Tampilkan QRIS");
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(-1, dp(52));
        nLp.setMargins(0, dp(14), 0, 0);
        card.addView(next, nLp);
        next.setOnClickListener(v -> showQris());
    }

    private void addQuickAmount(LinearLayout parent, int amount) {
        Button b = outlineButton(rupiah(amount));
        b.setTextSize(12);
        b.setOnClickListener(v -> amountInput.setText(String.valueOf(amount)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1);
        if (parent.getChildCount() > 0) lp.setMargins(dp(8), 0, 0, 0);
        parent.addView(b, lp);
    }

    private void buildQrisCard(boolean visible) {
        LinearLayout card = card();
        card.setTag("qris_card");
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setVisibility(visible ? View.VISIBLE : View.GONE);
        addWithMargin(card, 0, 0, 0, dp(18));
        card.addView(text("Pembayaran QRIS", 17, "#0B3A78", true));
        TextView info = text("Scan QRIS, bayar sesuai nominal, lalu upload bukti pembayaran.", 12, "#64748B", false);
        info.setPadding(0, dp(4), 0, dp(12));
        card.addView(info);
        qrisImage = new ImageView(this);
        qrisImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qrisImage.setAdjustViewBounds(true);
        qrisImage.setBackground(roundStroke("#FFFFFF", "#D7E6F8", dp(22), 1));
        qrisImage.setPadding(dp(10), dp(10), dp(10), dp(10));
        card.addView(qrisImage, new LinearLayout.LayoutParams(-1, dp(320)));
        TextView amountTitle = text("Nominal yang harus dibayar", 12, "#64748B", false);
        amountTitle.setPadding(0, dp(14), 0, 0); card.addView(amountTitle);
        TextView selectedAmountText = text("-", 24, "#0B7CFF", true);
        selectedAmountText.setTag("selected_amount_text"); selectedAmountText.setPadding(0, dp(2), 0, dp(12)); card.addView(selectedAmountText);
        Button pick = outlineButton("Upload Bukti Transfer"); card.addView(pick, new LinearLayout.LayoutParams(-1, dp(52))); pick.setOnClickListener(v -> pickProofImage());
        proofNameText = text("Belum ada bukti dipilih", 12, "#64748B", false); proofNameText.setGravity(Gravity.CENTER); proofNameText.setPadding(0, dp(10), 0, dp(10)); card.addView(proofNameText);
        submitButton = primaryButton("Kirim Bukti Deposit"); card.addView(submitButton, new LinearLayout.LayoutParams(-1, dp(52))); submitButton.setOnClickListener(v -> submitDeposit());
        statusText = text("Menunggu upload bukti pembayaran", 13, "#64748B", false); statusText.setGravity(Gravity.CENTER); statusText.setPadding(dp(12), dp(14), dp(12), dp(14)); statusText.setBackground(roundStroke("#F8FBFF", "#D7E6F8", dp(18), 1));
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(-1, -2); sLp.setMargins(0, dp(12), 0, 0); card.addView(statusText, sLp);
    }

    private void showQris() {
        int amount = parseInt(firstNonEmpty(amountInput.getText().toString(), "0"));
        if (amount < MIN_DEPOSIT) { showInfo("Nominal Tidak Valid", "Minimal deposit adalah Rp 10.000"); return; }
        selectedAmount = amount; proofUri = null;
        if (proofNameText != null) proofNameText.setText("Belum ada bukti dipilih");
        if (statusText != null) setStatus("Menunggu upload bukti pembayaran", false);
        View qrisCard = root.findViewWithTag("qris_card"); if (qrisCard != null) qrisCard.setVisibility(View.VISIBLE);
        TextView amountView = root.findViewWithTag("selected_amount_text"); if (amountView != null) amountView.setText(rupiah(amount));
        loadQrisImage();
    }

    private void loadQrisImage() {
        setLoading(true);
        new Thread(() -> {
            Bitmap bm = null;
            try { HttpURLConnection c = (HttpURLConnection)new URL(BASE_URL + "assets/qris.jpg?v=" + System.currentTimeMillis()).openConnection(); c.setConnectTimeout(10000); c.setReadTimeout(10000); bm = BitmapFactory.decodeStream(c.getInputStream()); c.disconnect(); } catch (Exception ignored) {}
            Bitmap finalBm = bm;
            mainHandler.post(() -> { setLoading(false); if (finalBm != null) qrisImage.setImageBitmap(finalBm); else showInfo("QRIS", "Gagal memuat gambar QRIS."); });
        }).start();
    }

    private void pickProofImage() {
        try { Intent intent = new Intent(Intent.ACTION_GET_CONTENT); intent.setType("image/*"); intent.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(Intent.createChooser(intent, "Pilih bukti transfer"), REQ_PICK_IMAGE); }
        catch (Exception e) { showInfo("Upload Bukti", "Tidak bisa membuka galeri/file manager."); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_IMAGE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            proofUri = data.getData();
            if (proofNameText != null) proofNameText.setText("Bukti dipilih: " + getFileName(proofUri));
            setStatus("Bukti siap dikirim", true);
        }
    }

    private void submitDeposit() {
        if (selectedAmount < MIN_DEPOSIT) { showInfo("Nominal Tidak Valid", "Tekan Tampilkan QRIS setelah mengisi nominal."); return; }
        if (proofUri == null) { showInfo("Bukti Belum Ada", "Upload bukti transfer terlebih dahulu."); return; }
        if (username.length() == 0) { showInfo("Sesi Tidak Valid", "Data username tidak ditemukan. Silakan login ulang."); return; }
        setLoading(true); setSubmitEnabled(false); setStatus("⏳ Mengirim bukti pembayaran...", false);
        new Thread(() -> {
            try {
                JSONObject res = uploadDeposit(proofUri, selectedAmount);
                boolean ok = res.optBoolean("success", false);
                String msg = firstNonEmpty(res.optString("message"), ok ? "Deposit berhasil dikirim" : "Upload gagal");
                mainHandler.post(() -> { setLoading(false); setSubmitEnabled(true); if (ok) { setStatus("✅ Bukti dikirim dan menunggu verifikasi admin", true); loadBalanceAndPending(); showInfo("Deposit Terkirim", "Deposit driver berhasil dikirim. Saldo masuk setelah admin setujui."); } else { setStatus(msg, false); showInfo("Upload Gagal", msg); } });
            } catch (Exception e) { mainHandler.post(() -> { setLoading(false); setSubmitEnabled(true); setStatus("❌ Koneksi bermasalah", false); showInfo("Koneksi Bermasalah", "Gagal mengirim bukti deposit."); }); }
        }).start();
    }

    private JSONObject uploadDeposit(Uri fileUri, int amount) throws Exception {
        String boundary = "----TransivaBoundary" + System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection)new URL(BASE_URL + "server/uploadDeposit.php").openConnection();
            conn.setRequestMethod("POST"); conn.setConnectTimeout(TIMEOUT_MS); conn.setReadTimeout(TIMEOUT_MS); conn.setDoInput(true); conn.setDoOutput(true); conn.setUseCaches(false);
            conn.setRequestProperty("Accept", "application/json"); conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            OutputStream out = conn.getOutputStream();
            writeField(out, boundary, "username", username); writeField(out, boundary, "role", "driver"); writeField(out, boundary, "amount", String.valueOf(amount)); writeFile(out, boundary, "proof", getFileName(fileUri), fileUri);
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8)); out.flush(); out.close();
            int code = conn.getResponseCode(); InputStream is = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream(); String body = readStream(is).trim(); if (body.length() == 0) return new JSONObject(); return new JSONObject(body);
        } finally { if (conn != null) conn.disconnect(); }
    }

    private void loadBalanceAndPending() {
        if (username.length() == 0) return;
        setLoading(true);
        new Thread(() -> {
            String bal = rupiah(0), pending = "Rp 0";
            try { JSONObject json = getJson(BASE_URL + "server/driver_get_dashboard.php?username=" + Uri.encode(username) + "&v=" + System.currentTimeMillis()); if (json.optBoolean("success", false)) { bal = rupiah(json.optDouble("balance", json.optDouble("saldo", 0))); pending = rupiah(json.optDouble("pending_deposit", 0)); } }
            catch (Exception e) { try { JSONObject json = getJson(BASE_URL + "server/getBalance.php?username=" + Uri.encode(username)); if (json.optBoolean("success", false)) bal = rupiah(json.optDouble("balance", 0)); } catch (Exception ignored) {} }
            String finalBal = bal, finalPending = pending;
            mainHandler.post(() -> { setLoading(false); if (balanceText != null) balanceText.setText(finalBal); if (pendingText != null) pendingText.setText("Pending deposit: " + finalPending); });
        }).start();
    }

    private JSONObject getJson(String urlText) throws Exception { HttpURLConnection conn = null; try { conn = (HttpURLConnection)new URL(urlText).openConnection(); conn.setRequestMethod("GET"); conn.setConnectTimeout(TIMEOUT_MS); conn.setReadTimeout(TIMEOUT_MS); conn.setRequestProperty("Accept", "application/json"); int code = conn.getResponseCode(); InputStream is = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(); String body = readStream(is).trim(); return body.length() == 0 ? new JSONObject() : new JSONObject(body); } finally { if (conn != null) conn.disconnect(); } }
    private void writeField(OutputStream out, String boundary, String name, String value) throws Exception { String part = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + firstNonEmpty(value, "") + "\r\n"; out.write(part.getBytes(StandardCharsets.UTF_8)); }
    private void writeFile(OutputStream out, String boundary, String fieldName, String fileName, Uri uri) throws Exception { String mime = firstNonEmpty(getContentResolver().getType(uri), "image/jpeg"); if (!mime.startsWith("image/")) mime = "image/jpeg"; String header = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + sanitizeFileName(fileName) + "\"\r\nContent-Type: " + mime + "\r\n\r\n"; out.write(header.getBytes(StandardCharsets.UTF_8)); InputStream in = getContentResolver().openInputStream(uri); if (in == null) throw new Exception("File tidak bisa dibaca"); byte[] buffer = new byte[8192]; int len; long total = 0; while ((len = in.read(buffer)) != -1) { total += len; if (total > 5L * 1024L * 1024L) { in.close(); throw new Exception("Ukuran maksimal 5MB"); } out.write(buffer, 0, len); } in.close(); out.write("\r\n".getBytes(StandardCharsets.UTF_8)); }
    private String readStream(InputStream stream) throws Exception { if (stream == null) return ""; BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8)); StringBuilder sb = new StringBuilder(); String line; while ((line = reader.readLine()) != null) sb.append(line); reader.close(); return sb.toString(); }
    private String getFileName(Uri uri) { String name = "bukti_deposit_driver.jpg"; try { Cursor cursor = getContentResolver().query(uri, null, null, null, null); if (cursor != null) { int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (idx >= 0 && cursor.moveToFirst()) name = firstNonEmpty(cursor.getString(idx), name); cursor.close(); } } catch (Exception ignored) {} if (!name.contains(".")) name += ".jpg"; return name; }
    private String sanitizeFileName(String name) { return firstNonEmpty(name, "bukti_deposit_driver.jpg").replaceAll("[^a-zA-Z0-9._-]", "_"); }
    private void setStatus(String message, boolean success) { if (statusText == null) return; statusText.setText(message); statusText.setTextColor(Color.parseColor(success ? "#0B7C55" : "#64748B")); statusText.setBackground(roundStroke(success ? "#ECFDF5" : "#F8FBFF", success ? "#A7F3D0" : "#D7E6F8", dp(18), 1)); }
    private void setSubmitEnabled(boolean enabled) { if (submitButton != null) { submitButton.setEnabled(enabled); submitButton.setText(enabled ? "Kirim Bukti Deposit" : "Mengirim..."); submitButton.setAlpha(enabled ? 1f : 0.65f); } }
    private void setLoading(boolean value) { if (progressBar != null) progressBar.setVisibility(value ? View.VISIBLE : View.GONE); }
    private int parseInt(String value) { try { return Integer.parseInt(firstNonEmpty(value, "0").replace(".", "").replace(",", "")); } catch (Exception e) { return 0; } }
    private LinearLayout card() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setBackground(roundStroke("#FFFFFF", "#E2ECF8", dp(22), 1)); v.setElevation(dp(2)); return v; }
    private void addWithMargin(View v, int l, int t, int r, int b) { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(l, t, r, b); root.addView(v, lp); }
    private TextView text(String value, int sp, String color, boolean bold) { TextView tv = new TextView(this); tv.setText(value); tv.setTextSize(sp); tv.setTextColor(Color.parseColor(color)); if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD); return tv; }
    private Button primaryButton(String value) { Button b = new Button(this); b.setText(value); b.setAllCaps(false); b.setTextSize(14); b.setTypeface(Typeface.DEFAULT_BOLD); b.setTextColor(Color.WHITE); b.setBackground(roundGradient("#086BFF", "#2EA2FF", dp(18))); return b; }
    private Button outlineButton(String value) { Button b = primaryButton(value); b.setTextColor(Color.parseColor("#0B7CFF")); b.setBackground(roundStroke("#FFFFFF", "#9DCAFF", dp(18), 1)); return b; }
    private GradientDrawable round(String color, int radius) { GradientDrawable gd = new GradientDrawable(); gd.setColor(Color.parseColor(color)); gd.setCornerRadius(radius); return gd; }
    private GradientDrawable roundStroke(String color, String stroke, int radius, int width) { GradientDrawable gd = round(color, radius); gd.setStroke(dp(width), Color.parseColor(stroke)); return gd; }
    private GradientDrawable roundGradient(String start, String end, int radius) { GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{Color.parseColor(start), Color.parseColor(end)}); gd.setCornerRadius(radius); return gd; }
    private String rupiah(double value) { try { NumberFormat nf = NumberFormat.getCurrencyInstance(new Locale("id", "ID")); nf.setMaximumFractionDigits(0); return nf.format(value).replace("Rp", "Rp "); } catch (Exception e) { return "Rp " + Math.round(value); } }
    private String firstNonEmpty(String... values) { if (values == null) return ""; for (String v: values) if (v != null && v.trim().length() > 0 && !"null".equalsIgnoreCase(v.trim())) return v.trim(); return ""; }
    private void showInfo(String title, String message) { try { new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show(); } catch (Exception ignored) {} }
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }
}
