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
        final int bg = DriverThemeTokens.background(activity);
        final int text = DriverThemeTokens.textPrimary(activity);
        final int muted = DriverThemeTokens.textSecondary(activity);
        final int line = DriverThemeTokens.border(activity);
        final int accent = DriverThemeTokens.color(activity, "#1477FF");
        final int green = DriverThemeTokens.color(activity, "#16A34A");
        final int gold = DriverThemeTokens.color(activity, "#F6C500");

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
                sourceOrder.optString("username"), sourceOrder.optString("user_name"), order==null?"":order.optString("customer_name"), "Customer Transiva");

        double totalPaid=optionalNumber(receipt,"customer_contribution");
        if(Double.isNaN(totalPaid))totalPaid=optionalNumber(sourceOrder,"price");
        if(Double.isNaN(totalPaid))totalPaid=optionalNumber(sourceOrder,"total");
        double gross = firstPositive(receipt.optDouble("ongkir", 0),
                sourceOrder.optDouble("driver_earning", 0), sourceOrder.optDouble("price", 0));
        double explicitNet = optionalNumber(receipt, "driver_net");
        if (Double.isNaN(explicitNet)) explicitNet = optionalNumber(sourceOrder, "driver_net");
        DriverIncomeSummary.Result income = DriverIncomeSummary.resolve(gross, explicitNet,
                optionalNumber(receipt, "app_fee"), optionalNumber(receipt, "total_potongan"));
        double fee = income.fee;
        double earning = income.net;

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);

        LinearLayout shell=new LinearLayout(activity);shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(activity,16),dp(activity,16),dp(activity,16),dp(activity,12));
        shell.setBackground(round(bg,dp(activity,22),line,dp(activity,1)));
        ScrollView scroll = new ScrollView(activity){
            @Override protected void onMeasure(int widthSpec,int heightSpec){
                android.graphics.Rect visible=new android.graphics.Rect();getWindowVisibleDisplayFrame(visible);
                int available=visible.height()>0?visible.height():activity.getResources().getDisplayMetrics().heightPixels;
                int limit=Math.max(dp(activity,96),Math.min((int)(available*.70f),available-dp(activity,100)));
                super.onMeasure(widthSpec,View.MeasureSpec.makeMeasureSpec(limit,View.MeasureSpec.AT_MOST));
            }
        };
        scroll.setFillViewport(false);scroll.setVerticalScrollBarEnabled(false);
        LinearLayout card = new LinearLayout(activity);card.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(card,new ScrollView.LayoutParams(-1,-2));shell.addView(scroll,new LinearLayout.LayoutParams(-1,-2));
        card.addView(DriverKdiUi.header(activity,"Order selesai","Terima kasih, perjalanan sudah selesai."));

        LinearLayout earningCard = new LinearLayout(activity);
        earningCard.setOrientation(LinearLayout.VERTICAL);
        earningCard.setPadding(dp(activity,12),dp(activity,12),dp(activity,12),dp(activity,10));
        earningCard.setBackground(round(DriverThemeTokens.surfaceAlt(activity),dp(activity,14),line,dp(activity,1)));
        earningCard.addView(label(activity,"Pendapatan bersih Anda",12,muted,false));
        TextView amount=label(activity,income.known?rupiah(earning):"Belum tersedia",28,green,true);
        amount.setPadding(0,dp(activity,3),0,dp(activity,7));earningCard.addView(amount);
        double waitingFee=optionalNumber(receipt,"waiting_fee");
        JSONObject waitingState=receipt.optJSONObject("smart_waiting");
        if(Double.isNaN(waitingFee)&&waitingState!=null)waitingFee=optionalNumber(waitingState,"applied_fee");
        if(Double.isNaN(waitingFee))waitingFee=0;
        if(income.known){
            earningCard.addView(summaryRow(activity,"Ongkir & tambahan",rupiah(Math.max(0,gross-waitingFee)),text,muted));
            if(waitingFee>0)earningCard.addView(summaryRow(activity,"Biaya tunggu · 100% Anda",rupiah(waitingFee),green,muted));
            earningCard.addView(summaryRow(activity,"Potongan aplikasi","− "+rupiah(fee),text,muted));
        }else earningCard.addView(label(activity,"Lihat riwayat transaksi untuk rincian pendapatan.",11,muted,false));
        String paidLabel=receipt.optBoolean("is_food",false)?"Total customer, termasuk belanja":"Dibayar customer";
        if(!Double.isNaN(totalPaid))earningCard.addView(summaryRow(activity,paidLabel+" · "+payment,rupiah(totalPaid),text,muted));
        if(income.known&&fee>0){
            String paymentNote="Tunai".equals(payment)?"Tunai diterima langsung. Potongan aplikasi lewat saldo.":"TransPay".equals(payment)?"Pendapatan diproses melalui saldo driver.":"";
            if(!paymentNote.isEmpty()){TextView note=label(activity,paymentNote,10,muted,false);note.setPadding(0,dp(activity,5),0,0);earningCard.addView(note);}
        }
        card.addView(earningCard,lp(-1,-2,0,dp(activity,10),0,0));
        card.addView(DriverKdiUi.appreciation(activity));
        if(waitingFee>0&&waitingState!=null){
            TextView waitingDetails=label(activity,"Lihat rincian waktu tunggu ›",11,accent,true);
            waitingDetails.setPadding(0,dp(activity,8),0,dp(activity,8));waitingDetails.setMinHeight(dp(activity,44));waitingDetails.setGravity(Gravity.CENTER_VERTICAL);
            final JSONObject finalWaiting=waitingState;
            waitingDetails.setOnClickListener(v -> {
                StringBuilder details=new StringBuilder();org.json.JSONArray sessions=finalWaiting.optJSONArray("sessions");
                if(sessions!=null)for(int i=0;i<sessions.length();i++){JSONObject entry=sessions.optJSONObject(i);if(entry!=null&&entry.optDouble("fee")>0){if(details.length()>0)details.append("\n");details.append(entry.optString("label","Titik tunggu")).append(": ").append(entry.optInt("paid_minutes")).append(" menit berbayar · ").append(rupiah(entry.optDouble("fee")));}}
                if(details.length()==0)details.append("Biaya tunggu mengikuti perhitungan server.");
                new android.app.AlertDialog.Builder(activity).setTitle("Rincian waktu tunggu").setMessage(details.toString()).setPositiveButton("Tutup",null).show();
            });card.addView(waitingDetails);
        }

        View divider = new View(activity);
        divider.setBackgroundColor(line);
        card.addView(divider, new LinearLayout.LayoutParams(-1, dp(activity, 1)));

        TextView rateTitle = label(activity, "Beri nilai customer", 15, text, true);
        rateTitle.setPadding(0, dp(activity, 12), 0, dp(activity, 3));
        card.addView(rateTitle);
        TextView customerLabel = label(activity, customer + " · Penilaian opsional", 12, muted, false);
        card.addView(customerLabel);

        LinearLayout stars = new LinearLayout(activity);
        stars.setOrientation(LinearLayout.HORIZONTAL);
        stars.setGravity(Gravity.CENTER);
        stars.setPadding(0, dp(activity, 7), 0, dp(activity, 5));
        final int[] selected = {0};
        final TextView[] starViews = new TextView[5];
        for (int i = 0; i < 5; i++) {
            final int value = i + 1;
            TextView star = label(activity, "★", 30, dark ? DriverThemeTokens.color(activity, "#425367") : DriverThemeTokens.color(activity, "#CBD5E1"), true);
            star.setGravity(Gravity.CENTER);
            star.setContentDescription(value + " bintang");
            star.setOnClickListener(v -> {
                selected[0] = value;
                for (int j = 0; j < starViews.length; j++) {
                    if (starViews[j] != null) starViews[j].setTextColor(j < value ? gold : (dark ? DriverThemeTokens.color(activity, "#425367") : DriverThemeTokens.color(activity, "#CBD5E1")));
                }
            });
            starViews[i] = star;
            stars.addView(star, new LinearLayout.LayoutParams(0, dp(activity, 44), 1f));
        }
        card.addView(stars);

        EditText review = new EditText(activity);
        review.setHint("Tulis catatan singkat (opsional)");
        review.setHintTextColor(dark ? DriverThemeTokens.color(activity, "#73859A") : DriverThemeTokens.color(activity, "#94A3B8"));
        review.setTextColor(text);
        review.setTextSize(14);
        review.setGravity(Gravity.TOP | Gravity.START);
        review.setMinLines(1);
        review.setMaxLines(3);
        review.setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12));
        review.setBackground(round(dark ? DriverThemeTokens.color(activity, "#0C1724") : DriverThemeTokens.color(activity, "#F8FAFC"), dp(activity, 16), line, dp(activity, 1)));
        review.setVisibility(View.GONE);
        TextView reviewToggle=label(activity,"＋ Tambah catatan",11,accent,true);reviewToggle.setMinHeight(dp(activity,44));reviewToggle.setGravity(Gravity.CENTER_VERTICAL);
        reviewToggle.setOnClickListener(v -> {boolean open=review.getVisibility()!=View.VISIBLE;review.setVisibility(open?View.VISIBLE:View.GONE);reviewToggle.setText(open?"− Tutup catatan":"＋ Tambah catatan");});
        card.addView(reviewToggle);card.addView(review,lp(-1,-2,0,0,0,dp(activity,8)));

        TextView status = label(activity, "Pilih bintang, atau Lewati untuk kembali.", 11, muted, false);
        status.setGravity(Gravity.CENTER);
        card.addView(status, lp(-1, -2, 0, 0, 0, dp(activity,5)));

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        Button skip = button(activity, "Lewati", dark ? DriverThemeTokens.color(activity, "#17283B") : DriverThemeTokens.color(activity, "#EEF2F7"), text);
        Button save = button(activity, "Kirim nilai", accent, DriverThemeTokens.onAccent(activity));
        actions.addView(skip, new LinearLayout.LayoutParams(0, dp(activity, 46), 1f));
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(0, dp(activity, 46), 1.35f);
        saveLp.setMargins(dp(activity, 10), 0, 0, 0);
        actions.addView(save, saveLp);
        shell.addView(actions,lp(-1,-2,0,dp(activity,10),0,0));

        Runnable finishDialog = () -> {
            try { dialog.dismiss(); } catch (Exception ignored) {}
            if (onDone != null) onDone.run();
        };
        skip.setOnClickListener(v -> finishDialog.run());
        save.setOnClickListener(v -> {
            if (selected[0] < 1) {
                status.setText("Pilih jumlah bintang terlebih dahulu, atau tekan Lewati.");
                status.setTextColor(DriverThemeTokens.color(activity, "#DC2626"));
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
                    status.setTextColor(DriverThemeTokens.color(activity, "#DC2626"));
                    status.setText(first(message, "Penilaian belum tersimpan. Coba lagi atau Lewati."));
                }
            }));
        });

        dialog.setContentView(shell);
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
                int width = DriverResponsiveUi.availableWidth(activity) - dp(activity, 28);
                w.setLayout(Math.min(width, dp(activity, 520)), WindowManager.LayoutParams.WRAP_CONTENT);
            }
        });
        dialog.show();
    }

    private static LinearLayout summaryRow(Activity a,String title,String value,int color,int muted) {
        LinearLayout row=new LinearLayout(a);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,dp(a,4),0,dp(a,4));
        TextView name=label(a,title,11,muted,false);row.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        TextView amount=label(a,value,12,color,true);amount.setGravity(Gravity.END);amount.setPadding(dp(a,8),0,0,0);row.addView(amount,new LinearLayout.LayoutParams(-2,-2));return row;
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
        b.setMinimumWidth(0);b.setMinHeight(0);b.setMinimumHeight(0);
        return b;
    }

    private static TextView label(Activity a, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);t.setIncludeFontPadding(false);
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
        if (x.contains("balance") || x.contains("saldo") || x.contains("wallet") || x.contains("transpay") || x.contains("transiva_pay") || x.contains("transivapay")) return "TransPay";
        if(x.equals("cash")||x.equals("tunai"))return "Tunai";
        return x.isEmpty()?"Belum tersedia":raw;
    }

    private static String first(String... values) {
        if (values != null) for (String s : values) if (s != null && !s.trim().isEmpty() && !"null".equalsIgnoreCase(s.trim())) return s.trim();
        return "";
    }
    private static double optionalNumber(JSONObject json, String key) {
        if (json == null || !json.has(key) || json.isNull(key)) return Double.NaN;
        double n=json.optDouble(key,Double.NaN);
        return Double.isNaN(n)||Double.isInfinite(n)||n<0 ? Double.NaN : n;
    }

}
