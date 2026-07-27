package com.transiva.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.Locale;

public class DriverWithdrawActivity extends Activity {

    private static final String BASE_URL = "https://transiva.my.id/";
    private static final String SERVER = BASE_URL + "server/";
    private static final int TIMEOUT_MS = 20000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SessionManager sessionManager;

    private TextView balanceText;
    private LinearLayout historyBox;
    private EditText amountInput, otherBankInput, accountNumberInput, accountNameInput, noteInput;
    private Spinner bankSpinner;
    private Button submitBtn, backBtn;
    private ProgressBar progressBar;

    private String username = "";
    private long balance = 0;
    private boolean loading = false;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            getWindow().setStatusBarColor(Color.WHITE);
            getWindow().setNavigationBarColor(Color.WHITE);
            if (Build.VERSION.SDK_INT >= 23) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        } catch (Exception ignored) {}

        sessionManager = new SessionManager(this);
        try { username = safe(sessionManager.getUsername()); } catch (Exception ignored) {}
        if (username.length() == 0) username = "Driver";

        buildUi();
        loadAll(true);
    }

    @Override protected void onResume() {
        super.onResume();
        loadAll(false);
    }

    private void buildUi() {
        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(Color.parseColor("#F3F8FF"));

        ScrollView scroll = new ScrollView(this);
        page.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(18), dp(14), dp(24));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(12), dp(14), dp(12));
        header.setBackground(roundStroke("#FFFFFF", "#D7E6F8", dp(22), 1));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        TextView icon = text("💸", 24, "#FFFFFF", true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(roundGradient("#086BFF", "#2EA2FF", dp(20)));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(46), dp(46));
        ilp.setMargins(0, 0, dp(12), 0);
        header.addView(icon, ilp);

        LinearLayout ht = new LinearLayout(this);
        ht.setOrientation(LinearLayout.VERTICAL);
        header.addView(ht, new LinearLayout.LayoutParams(0, -2, 1));
        ht.addView(text("Withdraw Driver", 18, "#0B3A78", true));
        add(ht, text("Tarik saldo penghasilan", 12, "#64748B", false), 0, dp(2), 0, 0);

        TextView badge = text("WD", 12, "#0B7CFF", true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(11), dp(6), dp(11), dp(6));
        badge.setBackground(roundStroke("#EAF4FF", "#B9DBFF", dp(18), 1));
        header.addView(badge);

        LinearLayout balCard = card();
        balCard.setBackground(roundGradient("#086BFF", "#2EA2FF", dp(22)));
        add(root, balCard, 0, dp(12), 0, dp(12));
        balCard.addView(text("Saldo tersedia", 13, "#EAF4FF", true));
        balanceText = text("Rp 0", 28, "#FFFFFF", true);
        add(balCard, balanceText, 0, dp(4), 0, 0);
        TextView note = text("Saldo akan ditahan setelah WD diajukan, lalu kembali otomatis jika admin menolak.", 12, "#EAF4FF", false);
        add(balCard, note, 0, dp(8), 0, 0);

        LinearLayout form = card();
        add(root, form, 0, 0, 0, dp(12));
        form.addView(text("🏦 Data Penarikan", 17, "#0B3A78", true));

        form.addView(label("Nominal WD"));
        amountInput = input("Minimal 10.000", InputType.TYPE_CLASS_NUMBER);
        form.addView(amountInput, fieldLp());

        LinearLayout quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        quick.addView(chip("50rb", 50000), new LinearLayout.LayoutParams(0, dp(42), 1));
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(0, dp(42), 1);
        qlp.setMargins(dp(8), 0, 0, 0);
        quick.addView(chip("100rb", 100000), qlp);
        LinearLayout.LayoutParams qlp2 = new LinearLayout.LayoutParams(0, dp(42), 1);
        qlp2.setMargins(dp(8), 0, 0, 0);
        quick.addView(chip("200rb", 200000), qlp2);
        add(form, quick, 0, 0, 0, dp(12));

        form.addView(label("Pilih Bank / E-Wallet"));

        final String[] bankOptions = new String[]{
                "Pilih Bank / E-Wallet",
                "OVO",
                "DANA",
                "GoPay",
                "BRI",
                "BCA",
                "BNI",
                "Mandiri",
                "Sinarmas",
                "Bank Sulteng",
                "Others"
        };

        bankSpinner = new Spinner(this);
        ArrayAdapter<String> bankAdapter = new ArrayAdapter<String>(
                this,
                android.R.layout.simple_spinner_item,
                bankOptions
        ) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextSize(14);
                view.setTextColor(Color.parseColor(
                        position == 0 ? "#94A3B8" : "#0F172A"
                ));
                view.setPadding(dp(13), 0, dp(13), 0);
                return view;
            }

            @Override
            public View getDropDownView(int position, View convertView, android.view.ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                view.setTextSize(14);
                view.setTextColor(Color.parseColor("#0F172A"));
                view.setPadding(dp(14), dp(12), dp(14), dp(12));
                return view;
            }
        };

        bankAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item
        );
        bankSpinner.setAdapter(bankAdapter);
        bankSpinner.setBackground(
                roundStroke("#FFFFFF", "#D8E4F2", dp(15), 1)
        );
        form.addView(bankSpinner, fieldLp());

        otherBankInput = input(
                "Tulis nama bank / e-wallet lainnya",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS
        );
        otherBankInput.setVisibility(View.GONE);
        form.addView(otherBankInput, fieldLp());

        bankSpinner.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            AdapterView<?> parent,
                            View view,
                            int position,
                            long id
                    ) {
                        boolean isOther =
                                position == bankOptions.length - 1;

                        otherBankInput.setVisibility(
                                isOther ? View.VISIBLE : View.GONE
                        );

                        if (!isOther) {
                            otherBankInput.setText("");
                        }
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                        otherBankInput.setVisibility(View.GONE);
                    }
                }
        );

        form.addView(label("Nomor Rekening / E-Wallet"));
        accountNumberInput = input("Nomor tujuan", InputType.TYPE_CLASS_NUMBER);
        form.addView(accountNumberInput, fieldLp());

        form.addView(label("Nama Pemilik"));
        accountNameInput = input("Nama sesuai rekening", InputType.TYPE_CLASS_TEXT);
        form.addView(accountNameInput, fieldLp());

        form.addView(label("Catatan"));
        noteInput = input("Opsional", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        noteInput.setSingleLine(false);
        noteInput.setMinLines(2);
        noteInput.setGravity(Gravity.TOP | Gravity.START);
        form.addView(noteInput, new LinearLayout.LayoutParams(-1, dp(82)));

        submitBtn = primaryButton("Ajukan Withdraw  →");
        submitBtn.setOnClickListener(v -> confirmSubmit());
        add(form, submitBtn, 0, dp(12), 0, 0);

        root.addView(section("Riwayat WD"));
        historyBox = new LinearLayout(this);
        historyBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(historyBox);

        backBtn = outlineButton("Kembali");
        backBtn.setOnClickListener(v -> finish());
        add(root, backBtn, 0, dp(10), 0, 0);

        progressBar = new ProgressBar(this);
        progressBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(52), dp(52));
        plp.gravity = Gravity.CENTER;
        page.addView(progressBar, plp);

        setContentView(page);
        DriverAppSettings.apply(this);
    }

    private Button chip(String label, long amount) {
        Button b = outlineButton(label);
        b.setTextSize(13);
        b.setOnClickListener(v -> amountInput.setText(String.valueOf(amount)));
        return b;
    }

    private void loadAll(boolean show) {
        if (loading) return;
        setBusy(true, show);
        new Thread(() -> {
            String balanceJson = "", wdJson = "";
            try { balanceJson = get(SERVER + "getBalance.php?username=" + enc(username) + "&v=" + System.currentTimeMillis()); } catch (Exception ignored) {}
            try { wdJson = get(SERVER + "getDriverWithdrawals.php?username=" + enc(username) + "&v=" + System.currentTimeMillis()); } catch (Exception ignored) {}
            final String fb = balanceJson, fw = wdJson;
            mainHandler.post(() -> {
                setBusy(false, false);
                showBalance(fb);
                showHistory(fw);
            });
        }).start();
    }

    private void showBalance(String json) {
        try {
            JSONObject o = new JSONObject(json);
            balance = o.optBoolean("success", false) ? o.optLong("balance", 0) : 0;
        } catch (Exception e) { balance = 0; }
        balanceText.setText(rupiah(balance));
    }

    private void showHistory(String json) {
        historyBox.removeAllViews();
        try {
            JSONObject o = new JSONObject(json);
            JSONArray arr = o.optJSONArray("withdrawals");
            if (!o.optBoolean("success", false) || arr == null || arr.length() == 0) {
                historyBox.addView(empty("Belum ada riwayat WD."));
                return;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item != null) historyBox.addView(historyCard(item));
            }
        } catch (Exception e) {
            historyBox.addView(empty("Gagal memuat riwayat WD."));
        }
    }

    private View historyCard(JSONObject item) {
        LinearLayout c = card();
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        c.addView(top, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        top.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        left.addView(text(rupiah(item.optLong("amount", 0)), 17, "#0B3A78", true));
        add(left, text(firstNonEmpty(item.optString("bank_name"), "-") + " • " + firstNonEmpty(item.optString("account_number"), "-"), 12, "#64748B", false), 0, dp(2), 0, 0);
        add(left, text(firstNonEmpty(item.optString("requested_at"), item.optString("created_at"), ""), 11, "#94A3B8", false), 0, dp(2), 0, 0);

        String status = safe(item.optString("status", "pending")).toLowerCase(Locale.US);
        TextView st = text(statusIcon(status) + " " + statusLabel(status), 12, statusColor(status), true);
        st.setPadding(dp(9), dp(5), dp(9), dp(5));
        st.setBackground(roundStroke(statusBg(status), statusStroke(status), dp(16), 1));
        top.addView(st);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(7), 0, dp(7));
        c.setLayoutParams(lp);
        return c;
    }

    private void confirmSubmit() {
        final long amount = parseLong(amountInput.getText().toString());
        final String selectedBank = bankSpinner == null
                || bankSpinner.getSelectedItem() == null
                ? ""
                : safe(String.valueOf(bankSpinner.getSelectedItem()));

        final boolean otherSelected =
                "Others".equalsIgnoreCase(selectedBank);

        final String bank = otherSelected
                ? safe(otherBankInput.getText().toString())
                : selectedBank;
        final String accNo = safe(accountNumberInput.getText().toString());
        final String accName = safe(accountNameInput.getText().toString());
        final String note = safe(noteInput.getText().toString());

        if (amount < 10000) { showInfo("Nominal Tidak Valid", "Minimal WD Rp 10.000"); return; }
        if (amount > balance) { showInfo("Saldo Tidak Cukup", "Saldo tersedia hanya " + rupiah(balance)); return; }
        if (selectedBank.length() == 0
                || "Pilih Bank / E-Wallet".equalsIgnoreCase(selectedBank)) {
            showInfo(
                    "Bank Belum Dipilih",
                    "Silakan pilih bank atau e-wallet tujuan."
            );
            return;
        }

        if (otherSelected && bank.length() == 0) {
            showInfo(
                    "Nama Bank Belum Diisi",
                    "Silakan tulis nama bank atau e-wallet lainnya."
            );
            otherBankInput.requestFocus();
            return;
        }

        if (accNo.length() == 0 || accName.length() == 0) {
            showInfo(
                    "Data Belum Lengkap",
                    "Nomor rekening/e-wallet dan nama pemilik wajib diisi."
            );
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Ajukan Withdraw")
                .setMessage("Ajukan WD " + rupiah(amount) + "?\n\nSaldo akan ditahan sampai admin proses.")
                .setPositiveButton("Ajukan", (d, w) -> submitWithdraw(amount, bank, accNo, accName, note))
                .setNegativeButton("Batal", null)
                .show();
    }

    private void submitWithdraw(long amount, String bank, String accNo, String accName, String note) {
        if (loading) return;
        setBusy(true, true);
        submitBtn.setText("Memproses...");
        new Thread(() -> {
            try {
                JSONObject p = new JSONObject();
                p.put("username", username);
                p.put("amount", amount);
                String method = isEwallet(bank)
                        ? "ewallet"
                        : "bank";
                p.put("method", method);
                p.put("bank_name", bank);
                p.put("account_number", accNo);
                p.put("account_name", accName);
                p.put("note", note);
                JSONObject res = post(SERVER + "requestDriverWithdraw.php", p);
                boolean ok = res.optBoolean("success", false);
                String msg = firstNonEmpty(res.optString("message"), ok ? "WD berhasil diajukan" : "WD gagal");
                mainHandler.post(() -> {
                    setBusy(false, false);
                    submitBtn.setText("Ajukan Withdraw  →");
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                    if (ok) {
                        amountInput.setText("");
                        noteInput.setText("");
                        accountNumberInput.setText("");
                        accountNameInput.setText("");
                        otherBankInput.setText("");
                        bankSpinner.setSelection(0);
                    }
                    loadAll(false);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    setBusy(false, false);
                    submitBtn.setText("Ajukan Withdraw  →");
                    showInfo("Gagal", "Koneksi gagal mengajukan WD.");
                });
            }
        }).start();
    }

    private void setBusy(boolean b, boolean show) {
        loading = b;
        if (progressBar != null) progressBar.setVisibility(b && show ? View.VISIBLE : View.GONE);
        if (submitBtn != null) submitBtn.setEnabled(!b);
        if (backBtn != null) backBtn.setEnabled(!b);
    }

    private String get(String link) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(link).openConnection();
        c.setConnectTimeout(TIMEOUT_MS); c.setReadTimeout(TIMEOUT_MS);
        c.setRequestMethod("GET"); c.setRequestProperty("Accept", "application/json");
        InputStream is = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        String body = read(is); c.disconnect(); return body;
    }

    private JSONObject post(String link, JSONObject payload) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(link).openConnection();
        c.setConnectTimeout(TIMEOUT_MS); c.setReadTimeout(TIMEOUT_MS);
        c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        c.setRequestProperty("Accept", "application/json");
        OutputStream os = c.getOutputStream();
        BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8));
        bw.write(payload == null ? "{}" : payload.toString()); bw.flush(); bw.close(); os.close();
        InputStream is = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        String body = read(is); c.disconnect();
        if (body == null || body.trim().length() == 0) return new JSONObject();
        return new JSONObject(body);
    }

    private String read(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line; while ((line = br.readLine()) != null) sb.append(line);
        br.close(); return sb.toString();
    }

    private LinearLayout card() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(dp(14), dp(14), dp(14), dp(14)); l.setBackground(roundStroke("#FFFFFF", "#D7E6F8", dp(22), 1)); return l; }
    private TextView label(String v) { TextView t = text(v, 12, "#0B3A78", true); t.setPadding(0, dp(12), 0, dp(6)); return t; }
    private EditText input(String hint, int type) { EditText e = new EditText(this); e.setSingleLine(true); e.setTextSize(14); e.setTextColor(Color.parseColor("#0F172A")); e.setHintTextColor(Color.parseColor("#94A3B8")); e.setHint(hint); e.setInputType(type); e.setImeOptions(EditorInfo.IME_ACTION_DONE); e.setPadding(dp(13), 0, dp(13), 0); e.setBackground(roundStroke("#FFFFFF", "#D8E4F2", dp(15), 1)); return e; }
    private LinearLayout.LayoutParams fieldLp() { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48)); lp.setMargins(0, 0, 0, dp(2)); return lp; }
    private TextView section(String v) { TextView t = text(v, 18, "#0B3A78", true); t.setPadding(0, dp(8), 0, dp(6)); return t; }
    private TextView empty(String msg) { TextView t = text(msg, 14, "#64748B", false); t.setGravity(Gravity.CENTER); t.setPadding(dp(14), dp(18), dp(14), dp(18)); t.setBackground(roundStroke("#FFFFFF", "#D7E6F8", dp(22), 1)); return t; }
    private Button primaryButton(String s) { Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setTextSize(14); b.setTypeface(Typeface.DEFAULT_BOLD); b.setTextColor(Color.WHITE); b.setBackground(roundGradient("#086BFF", "#2EA2FF", dp(16))); return b; }
    private Button outlineButton(String s) { Button b = primaryButton(s); b.setTextColor(Color.parseColor("#0B7CFF")); b.setBackground(roundStroke("#FFFFFF", "#9DCAFF", dp(16), 1)); return b; }
    private TextView text(String s, int sp, String color, boolean bold) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(Color.parseColor(color)); if (bold) t.setTypeface(Typeface.DEFAULT_BOLD); return t; }
    private void add(LinearLayout p, View v, int l, int t, int r, int b) { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(l, t, r, b); p.addView(v, lp); }
    private GradientDrawable round(String c, int r) { GradientDrawable g = new GradientDrawable(); g.setColor(Color.parseColor(c)); g.setCornerRadius(r); return g; }
    private GradientDrawable roundStroke(String c, String s, int r, int w) { GradientDrawable g = round(c, r); g.setStroke(dp(w), Color.parseColor(s)); return g; }
    private GradientDrawable roundGradient(String a, String b, int r) { GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{Color.parseColor(a), Color.parseColor(b)}); g.setCornerRadius(r); return g; }
    private String rupiah(long v) { return "Rp " + NumberFormat.getNumberInstance(new Locale("id", "ID")).format(v); }
    private String enc(String v) { try { return URLEncoder.encode(v == null ? "" : v, "UTF-8"); } catch (Exception e) { return ""; } }
    private String safe(String v) { return v == null ? "" : v.trim(); }
    private String firstNonEmpty(String... vals) { if (vals == null) return ""; for (String v : vals) if (v != null && v.trim().length() > 0 && !"null".equalsIgnoreCase(v.trim())) return v.trim(); return ""; }
    private long parseLong(String v) { try { return Long.parseLong(safe(v).replace(".", "").replace(",", "")); } catch (Exception e) { return 0; } }
    private boolean isEwallet(String bank) {
        String value = safe(bank).toLowerCase(Locale.US);
        return "ovo".equals(value)
                || "dana".equals(value)
                || "gopay".equals(value);
    }

    private String statusLabel(String s) { if ("approved".equals(s)) return "Berhasil"; if ("rejected".equals(s)) return "Ditolak"; return "Pending"; }
    private String statusIcon(String s) { if ("approved".equals(s)) return "✓"; if ("rejected".equals(s)) return "×"; return "⏳"; }
    private String statusColor(String s) { if ("approved".equals(s)) return "#059669"; if ("rejected".equals(s)) return "#DC2626"; return "#B45309"; }
    private String statusBg(String s) { if ("approved".equals(s)) return "#ECFDF5"; if ("rejected".equals(s)) return "#FEF2F2"; return "#FFFBEB"; }
    private String statusStroke(String s) { if ("approved".equals(s)) return "#A7F3D0"; if ("rejected".equals(s)) return "#FECACA"; return "#FDE68A"; }
    private void showInfo(String title, String msg) { try { new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show(); } catch (Exception ignored) {} }
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }
}
