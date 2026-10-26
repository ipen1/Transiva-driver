package com.transiva.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Simple, theme-aware home for secondary Customer features. */
public class AllServicesActivity extends CustomerAdaptiveActivity {
    private boolean dark;
    private boolean checkingSplitBalance;
    private final CustomerLifecycleNetworkScope splitScope = new CustomerLifecycleNetworkScope();
    private final android.os.Handler splitHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b); dark = false; // Source colors stay light; the shared theme engine renders both modes.
        CustomerWindowPolicy.status(getWindow(), Color.parseColor(dark ? "#07111F" : "#075ED1"));
        setContentView(build()); CustomerAppSettings.apply(this);
    }
    private View build() {
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(c("#F4F8FD"));
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(18),dp(18),dp(18),dp(32)); scroll.addView(root);
        TextView back=tx("‹   Semua Layanan",24,"#0B3A78",true); back.setOnClickListener(v->finish()); root.addView(back);
        TextView sub=tx("Pilih fitur yang kamu butuhkan",13,"#64748B",false); LinearLayout.LayoutParams slp=lp(0,16); root.addView(sub,slp);
        add(root,"🚘","TransCar","Pesan mobil untuk perjalanan", PassengerCarActivity.class);
        add(root,"📦","TransSend","Kirim paket dan barang dengan driver", TransPickupActivity.class);
        add(root,"👥","Split Pay","Patungan pembayaran perjalanan", TransRideActivity.class);
        add(root,"👨‍👩‍👧","Family","Kelola anggota keluarga", TransivaFamilyActivity.class);
        add(root,"🎁","Royalti","Lihat poin dan hadiah", CustomerLoyaltyActivity.class);
        add(root,"🤝","Ajak Teman","Bagikan kode referral", CustomerReferralActivity.class);
        add(root,"📍","Tempat Favorit","Simpan rumah, kantor, dan lokasi penting", FavoritePlacesActivity.class);
        add(root,"🛡","Pusat Keamanan","Fitur keamanan perjalanan", SafetyCenterActivity.class);
        add(root,"💳","Transiva Pay","Isi saldo untuk pembayaran", CustomerTopUpActivity.class);
        add(root,"✨","Asisten Transiva","Bantuan pintar untuk aktivitasmu", TransAssistantActivity.class);
        add(root,"🛒","TransMart","Segera hadir", null);
        return scroll;
    }
    private void add(LinearLayout root,String icon,String title,String sub,Class<?> cls){
        LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.HORIZONTAL); card.setGravity(Gravity.CENTER_VERTICAL); card.setPadding(dp(14),dp(13),dp(14),dp(13)); card.setBackground(bg("#FFFFFF","#DFEBF7",16));
        TextView i=tx(icon,25,"#0B3A78",false); card.addView(i,new LinearLayout.LayoutParams(dp(42),dp(42)));
        LinearLayout copy=new LinearLayout(this); copy.setOrientation(LinearLayout.VERTICAL); copy.addView(tx(title,15,"#0B3A78",true)); copy.addView(tx(sub,11,"#64748B",false)); card.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
        TextView arrow=tx("›",28,"#0878F9",false); card.addView(arrow);
        card.setOnClickListener(v->{ if("Split Pay".equals(title)) { showSplitPayInfo(); return; } if(cls!=null) startActivity(new Intent(this,cls)); else Toast.makeText(this,"TransMart segera hadir.",Toast.LENGTH_SHORT).show(); });
        root.addView(card,lp(8,0));
    }
    private void showSplitPayInfo() {
        if (checkingSplitBalance) return;
        new TransivaAlertDialogBuilder(this).setTitle("Split Pay menggunakan TransPay")
                .setMessage("Patungan perjalanan hanya tersedia dengan pembayaran TransPay (Transiva Pay). Pastikan saldo terisi dan cukup untuk bagian pembayaranmu.")
                .setNegativeButton("Batal", null)
                .setNeutralButton("Isi saldo", (d,w) -> startActivity(new Intent(this, CustomerTopUpActivity.class)))
                .setPositiveButton("Lanjut", (d,w) -> checkSplitBalance()).show();
    }
    private void checkSplitBalance() {
        if (checkingSplitBalance) return;
        checkingSplitBalance = true;
        Toast.makeText(this, "Memeriksa saldo TransPay...", Toast.LENGTH_SHORT).show();
        splitScope.newThread(() -> {
            try {
                org.json.JSONObject result = TransivaHttpRepository.getJsonOnce(this, ApiConfig.server("getBalance.php"), 7000);
                if (!result.optBoolean("success") || !result.has("balance") || result.isNull("balance"))
                    throw new IllegalStateException("Saldo belum dapat diverifikasi");
                final double balance = result.optDouble("balance", Double.NaN);
                if (Double.isNaN(balance) || Double.isInfinite(balance)) throw new IllegalStateException("Saldo tidak valid");
                splitScope.post(splitHandler, () -> {
                    checkingSplitBalance = false;
                    if (isFinishing() || isDestroyed()) return;
                    if (balance <= 0) {
                        new TransivaAlertDialogBuilder(this).setTitle("Saldo TransPay kosong")
                                .setMessage("Isi saldo TransPay terlebih dahulu untuk menggunakan Split Pay.")
                                .setNegativeButton("Tutup", null)
                                .setPositiveButton("Isi saldo", (d,w) -> startActivity(new Intent(this, CustomerTopUpActivity.class))).show();
                        return;
                    }
                    new TransivaAlertDialogBuilder(this).setTitle("Pilih perjalanan Split Pay")
                            .setItems(new String[]{"TransRide • Motor", "TransCar • Mobil"}, (d,which) -> {
                                Intent intent = new Intent(this, which == 0 ? TransRideActivity.class : PassengerCarActivity.class);
                                intent.putExtra("split_pay_entry", true);
                                startActivity(intent);
                            }).setNegativeButton("Batal", null).show();
                });
            } catch (Exception e) {
                splitScope.post(splitHandler, () -> {
                    checkingSplitBalance = false;
                    if (isFinishing() || isDestroyed()) return;
                    new TransivaAlertDialogBuilder(this).setTitle("Saldo belum dapat diperiksa")
                            .setMessage("Periksa koneksi lalu coba lagi untuk menggunakan Split Pay.")
                            .setNegativeButton("Tutup", null)
                            .setPositiveButton("Coba lagi", (d,w) -> checkSplitBalance()).show();
                });
            }
        }).start();
    }
    @Override protected void onDestroy() {
        splitScope.destroy();
        splitHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
    private LinearLayout.LayoutParams lp(int top,int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(top),0,dp(bottom));return p;}
    private TextView tx(String s,int z,String color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(z);t.setTextColor(c(color));if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private GradientDrawable bg(String fill,String stroke,int r){GradientDrawable g=new GradientDrawable();g.setColor(c(fill));g.setCornerRadius(dp(r));g.setStroke(dp(1),c(stroke));return g;}
    private int c(String v){String x=v;if(dark){String u=v.toUpperCase();if(u.equals("#F4F8FD"))x="#08111F";else if(u.equals("#FFFFFF"))x="#111C2C";else if(u.equals("#0B3A78"))x="#F1F5F9";else if(u.equals("#64748B"))x="#AFC0D4";else if(u.equals("#DFEBF7"))x="#26384F";else if(u.equals("#0878F9"))x="#66AFFF";}return Color.parseColor(x);}
    private int dp(int v){return(int)(v*getResources().getDisplayMetrics().density+.5f);}
}
