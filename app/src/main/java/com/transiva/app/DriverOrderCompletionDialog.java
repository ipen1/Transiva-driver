package com.transiva.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.Locale;

/** Premium post-trip summary + customer rating sheet. */
public final class DriverOrderCompletionDialog {
    public interface SaveHandler {
        void save(int rating, String review, SaveCallback callback);
    }
    public interface SaveCallback {
        void complete(boolean success, String message);
    }

    private DriverOrderCompletionDialog() {}

    public static void show(Activity activity, JSONObject response, JSONObject order,
                            SaveHandler saveHandler, Runnable onDone) {
        if (activity == null || activity.isFinishing()) {
            if (onDone != null) onDone.run();
            return;
        }

        final boolean dark = DriverAppSettings.isDarkMode(activity);
        final int bg = Color.parseColor(dark ? "#101D2D" : "#FFFFFF");
        final int text = Color.parseColor(dark ? "#F8FAFC" : "#0F172A");
        final int muted = Color.parseColor(dark ? "#A9B7C8" : "#64748B");
        final int line = Color.parseColor(dark ? "#263A50" : "#E2E8F0");
        final int accent = Color.parseColor("#1477FF");
        final int green = Color.parseColor("#16A34A");
        final int gold = Color.parseColor("#F6C500");

        JSONObject receipt = response == null ? null : response.optJSONObject("receipt");
        if (receipt == null) receipt = new JSONObject();
        JSONObject responseOrder = response == null ? null : response.optJSONObject("order");
        JSONObject receiptOrder = receipt.optJSONObject("order");
        JSONObject sourceOrder = responseOrder != null ? responseOrder : (receiptOrder != null ? receiptOrder : order);
        if (sourceOrder == null) sourceOrder = new JSONObject();

        String paymentRaw = first(response == null ? "" : response.optString("payment_method"),
                receipt.optString("payment_method"), sourceOrder.optString("payment_method"));
        String payment = paymentLabel(paymentRaw);
        String customer = first(sourceOrder.optString("customer_name"), sourceOrder.optString("customer_username"),
                sourceOrder.optString("username"), sourceOrder.optString("user_name"), "Customer Transiva");

        double totalPaid = firstPositive(receipt.optDouble("customer_contribution", 0),
                sourceOrder.optDouble("price", 0), sourceOrder.optDouble("total", 0));
        double gross = firstPositive(receipt.optDouble("ongkir", 0),
                sourceOrder.optDouble("driver_earning", 0), sourceOrder.optDouble("price", 0));
        double fee = Math.max(0, receipt.optDouble("app_fee", receipt.optDouble("total_potongan", 0)));
        double earning = firstPositive(receipt.optDouble("driver_net", 0),
                sourceOrder.optDouble("driver_earning", 0), Math.max(0, gross - fee));
        if (earning <= 0) earning = Math.max(0, gross - fee);

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(activity, 24), dp(activity, 22), dp(activity, 24), dp(activity, 20));
        card.setBackground(round(bg, dp(activity, 28), line, dp(activity, 1)));
        scroll.addView(card, new ScrollView.LayoutParams(-1, -2));

        TextView eyebrow = label(activity, "ORDER SELESAI", 12, accent, true);
        eyebrow.setLetterSpacing(Build.VERSION.SDK_INT >= 21 ? 0.12f : 0f);
        card.addView(eyebrow);
        TextView title = label(activity, "Perjalanan berhasil diselesaikan", 24, text, true);
        title.setPadding(0, dp(activity, 5), 0, dp(activity, 18));
        card.addView(title);

        LinearLayout earningCard = new LinearLayout(activity);
        earningCard.setOrientation(LinearLayout.VERTICAL);
        earningCard.setPadding(dp(activity, 18), dp(activity, 16), dp(activity, 18), dp(activity, 16));
        earningCard.setBackground(round(dark ? Color.parseColor("#13283A") : Color.parseColor("#F0FDF4"), dp(activity, 20), dark ? Color.parseColor("#1E4652") : Color.parseColor("#BBF7D0"), dp(activity, 1)));
        earningCard.addView(label(activity, "Pendapatan bersih Anda", 13, muted, false));
        TextView amount = label(activity, rupiah(earning), 30, green, true);
        amount.setPadding(0, dp(activity, 3), 0, dp(activity, 7));
        earningCard.addView(amount);
        earningCard.addView(label(activity, "Pembayaran: " + payment + (totalPaid > 0 ? "  •  Dibayar customer " + rupiah(totalPaid) : ""), 12, muted, false));
        if (fee > 0) {
            TextView feeText = label(activity, "Biaya layanan: " + rupiah(fee), 12, muted, false);
            feeText.setPadding(0, dp(activity, 4), 0, 0);
            earningCard.addView(feeText);
        }
        card.addView(earningCard, lp(-1, -2, 0, 0, 0, 18));

        View divider = new View(activity);
        divider.setBackgroundColor(line);
        card.addView(divider, new LinearLayout.LayoutParams(-1, dp(activity, 1)));

        TextView rateTitle = label(activity, "Bagaimana customer Anda?", 19, text, true);
        rateTitle.setPadding(0, dp(activity, 18), 0, dp(activity, 4));
        card.addView(rateTitle);
        TextView customerLabel = label(activity, customer + " • Nilai pengalaman Anda dengan customer", 12, muted, false);
        card.addView(customerLabel);

        LinearLayout stars = new LinearLayout(activity);
        stars.setOrientation(LinearLayout.HORIZONTAL);
        stars.setGravity(Gravity.CENTER);
        stars.setPadding(0, dp(activity, 12), 0, dp(activity, 10));
        final int[] selected = {0};
        final TextView[] starViews = new TextView[5];
        for (int i = 0; i < 5; i++) {
            final int value = i + 1;
            TextView star = label(activity, "★", 42, dark ? Color.parseColor("#425367") : Color.parseColor("#CBD5E1"), true);
            star.setGravity(Gravity.CENTER);
            star.setContentDescription(value + " bintang");
            star.setOnClickListener(v -> {
                selected[0] = value;
                for (int j = 0; j < starViews.length; j++) {
                    if (starViews[j] != null) starViews[j].setTextColor(j < value ? gold : (dark ? Color.parseColor("#425367") : Color.parseColor("#CBD5E1")));
                }
            });
            starViews[i] = star;
            stars.addView(star, new LinearLayout.LayoutParams(0, dp(activity, 58), 1f));
        }
        card.addView(stars);

        EditText review = new EditText(activity);
        review.setHint("Catatan opsional, misalnya: customer ramah dan mudah dihubungi");
        review.setHintTextColor(dark ? Color.parseColor("#73859A") : Color.parseColor("#94A3B8"));
        review.setTextColor(text);
        review.setTextSize(14);
        review.setGravity(Gravity.TOP | Gravity.START);
        review.setMinLines(3);
        review.setMaxLines(5);
        review.setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12));
        review.setBackground(round(dark ? Color.parseColor("#0C1724") : Color.parseColor("#F8FAFC"), dp(activity, 16), line, dp(activity, 1)));
        card.addView(review, lp(-1, -2, 0, 0, 0, 16));

        TextView status = label(activity, "Pilih 1–5 bintang, atau Lewati jika tidak ingin menilai.", 11, muted, false);
        status.setGravity(Gravity.CENTER);
        card.addView(status, lp(-1, -2, 0, 0, 0, 12));

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        Button skip = button(activity, "Lewati", dark ? Color.parseColor("#17283B") : Color.parseColor("#EEF2F7"), text);
        Button save = button(activity, "Selesai", accent, Color.WHITE);
        actions.addView(skip, new LinearLayout.LayoutParams(0, dp(activity, 52), 1f));
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(0, dp(activity, 52), 1.35f);
        saveLp.setMargins(dp(activity, 10), 0, 0, 0);
        actions.addView(save, saveLp);
        card.addView(actions);

        Runnable finishDialog = () -> {
            try { dialog.dismiss(); } catch (Exception ignored) {}
            if (onDone != null) onDone.run();
        };
        skip.setOnClickListener(v -> finishDialog.run());
        save.setOnClickListener(v -> {
            if (selected[0] < 1) {
                status.setText("Pilih jumlah bintang terlebih dahulu, atau tekan Lewati.");
                status.setTextColor(Color.parseColor("#DC2626"));
                return;
            }
            String note = review.getText() == null ? "" : review.getText().toString().trim();
            if (note.length() > 300) note = note.substring(0, 300);
            skip.setEnabled(false);
            save.setEnabled(false);
            save.setText("Menyimpan…");
            status.setTextColor(muted);
            status.setText("Menyimpan penilaian customer…");
            final String finalNote = note;
            if (saveHandler == null) {
                finishDialog.run();
                return;
            }
            saveHandler.save(selected[0], finalNote, (success, message) -> activity.runOnUiThread(() -> {
                if (success) {
                    status.setTextColor(green);
                    status.setText(first(message, "Penilaian customer tersimpan."));
                    activity.getWindow().getDecorView().postDelayed(finishDialog, 450L);
                } else {
                    skip.setEnabled(true);
                    save.setEnabled(true);
                    save.setText("Coba lagi");
                    status.setTextColor(Color.parseColor("#DC2626"));
                    status.setText(first(message, "Penilaian belum tersimpan. Coba lagi atau Lewati."));
                }
            }));
        });

        dialog.setContentView(scroll);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams p = window.getAttributes();
            p.dimAmount = 0.60f;
            p.gravity = Gravity.CENTER;
            window.setAttributes(p);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.setOnShowListener(d -> {
            Window w = dialog.getWindow();
            if (w != null) {
                int width = activity.getResources().getDisplayMetrics().widthPixels - dp(activity, 28);
                w.setLayout(Math.min(width, dp(activity, 520)), WindowManager.LayoutParams.WRAP_CONTENT);
            }
        });
        dialog.show();
    }

    private static Button button(Activity a, String text, int bg, int fg) {
        Button b = new Button(a);
        b.setText(text);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(fg);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(a, 10), 0, dp(a, 10), 0);
        b.setBackground(round(bg, dp(a, 16), bg, 0));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        return b;
    }

    private static TextView label(Activity a, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private static GradientDrawable round(int color, int radius, int strokeColor, int strokeWidth) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        if (strokeWidth > 0) g.setStroke(strokeWidth, strokeColor);
        return g;
    }

    private static LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static int dp(Activity a, int value) {
        return Math.round(value * a.getResources().getDisplayMetrics().density);
    }

    private static String rupiah(double value) {
        return "Rp " + NumberFormat.getNumberInstance(new Locale("id", "ID")).format((long)Math.max(0, value));
    }

    private static double firstPositive(double... values) {
        if (values != null) for (double v : values) if (v > 0) return v;
        return 0;
    }

    private static String paymentLabel(String raw) {
        String x = raw == null ? "" : raw.trim().toLowerCase(Locale.US);
        if (x.contains("balance") || x.contains("saldo") || x.contains("wallet") || x.contains("transpay")) return "TRANSPAY";
        return "TUNAI";
    }

    private static String first(String... values) {
        if (values != null) for (String s : values) if (s != null && !s.trim().isEmpty() && !"null".equalsIgnoreCase(s.trim())) return s.trim();
        return "";
    }
}
