package com.transiva.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
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
import java.util.UUID;

public class DriverTopUpActivity extends Activity {
    private static final String BASE_URL="https://transiva.my.id/";
    private static final int MIN_DEPOSIT=10000;
    private static final int REQ_PAY=7301;
    private static final int TIMEOUT=25000;
    private final Handler main=new Handler(Looper.getMainLooper());
    private SessionManager session;
    private LinearLayout root;
    private ProgressBar progress;
    private EditText amountInput;
    private TextView balanceText,pendingText,statusText;
    private Button payButton;
    private String lastOrderId="";

    @Override protected void onCreate(Bundle b){ super.onCreate(b); session=new SessionManager(this); build(); loadWallet(); }
    @Override protected void onResume(){ super.onResume(); if(!lastOrderId.isEmpty()) refreshAfterPayment(0); else loadWallet(); }

    private void build(){
        try { getWindow().setStatusBarColor(Color.WHITE); getWindow().setNavigationBarColor(Color.WHITE); if(android.os.Build.VERSION.SDK_INT>=23)getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR); }catch(Exception ignored){}
        FrameLayout page=new FrameLayout(this); page.setBackgroundColor(Color.parseColor("#F7FAFF"));
        ScrollView scroll=new ScrollView(this); page.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(16),dp(18),dp(16),dp(30)); scroll.addView(root,new ScrollView.LayoutParams(-1,-2));
        progress=new ProgressBar(this); progress.setVisibility(View.GONE); FrameLayout.LayoutParams plp=new FrameLayout.LayoutParams(dp(52),dp(52)); plp.gravity=Gravity.CENTER; page.addView(progress,plp); setContentView(page); DriverAppSettings.apply(this);
        top(); balanceCard(); depositCard(); infoCard();
    }

    private void top(){ LinearLayout r=new LinearLayout(this);r.setGravity(Gravity.CENTER_VERTICAL);r.setPadding(0,0,0,dp(16));root.addView(r,new LinearLayout.LayoutParams(-1,-2)); TextView back=text("‹",34,"#0B3A78",true);back.setGravity(Gravity.CENTER);back.setBackground(round("#FFFFFF",18));back.setOnClickListener(v->finish());r.addView(back,new LinearLayout.LayoutParams(dp(44),dp(44))); LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(12),0,0,0);r.addView(c,new LinearLayout.LayoutParams(0,-2,1));c.addView(text("Deposit Driver",22,"#0B3A78",true));c.addView(text("Pembayaran otomatis via Midtrans",12,"#64748B",false)); }
    private void balanceCard(){ LinearLayout c=card();c.setPadding(dp(18),dp(16),dp(18),dp(16));c.setBackground(gradient("#086BFF","#2EA2FF",24));add(c,0,0,0,14);c.addView(text("💳 Saldo Driver",13,"#EAF4FF",true));balanceText=text("Memuat saldo...",26,"#FFFFFF",true);balanceText.setPadding(0,dp(6),0,0);c.addView(balanceText);pendingText=text("Pending deposit: ...",12,"#EAF4FF",false);pendingText.setPadding(0,dp(8),0,0);c.addView(pendingText); }
    private void depositCard(){ LinearLayout c=card();c.setPadding(dp(16),dp(16),dp(16),dp(16));add(c,0,0,0,14);c.addView(text("Pilih Nominal",17,"#0B3A78",true));TextView sub=text("Minimal Rp10.000 • saldo masuk otomatis setelah pembayaran terkonfirmasi",12,"#64748B",false);sub.setPadding(0,dp(5),0,dp(12));c.addView(sub);
        amountInput=new EditText(this);amountInput.setSingleLine(true);amountInput.setTextSize(18);amountInput.setTypeface(Typeface.DEFAULT_BOLD);amountInput.setTextColor(Color.parseColor("#0F172A"));amountInput.setHint("Contoh: 50000");amountInput.setHintTextColor(Color.parseColor("#94A3B8"));amountInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);amountInput.setImeOptions(EditorInfo.IME_ACTION_DONE);amountInput.setPadding(dp(16),0,dp(16),0);amountInput.setBackground(stroke("#FFFFFF","#D7E6F8",18,1));c.addView(amountInput,new LinearLayout.LayoutParams(-1,dp(54)));
        LinearLayout q1=new LinearLayout(this);q1.setOrientation(LinearLayout.HORIZONTAL);LinearLayout.LayoutParams qlp=new LinearLayout.LayoutParams(-1,-2);qlp.setMargins(0,dp(12),0,0);c.addView(q1,qlp);quick(q1,20000);quick(q1,50000);quick(q1,100000);
        LinearLayout q2=new LinearLayout(this);q2.setOrientation(LinearLayout.HORIZONTAL);LinearLayout.LayoutParams q2lp=new LinearLayout.LayoutParams(-1,-2);q2lp.setMargins(0,dp(8),0,0);c.addView(q2,q2lp);quick(q2,200000);quick(q2,500000);quick(q2,1000000);
        payButton=button("Lanjut ke Pembayaran");LinearLayout.LayoutParams blp=new LinearLayout.LayoutParams(-1,dp(54));blp.setMargins(0,dp(16),0,0);c.addView(payButton,blp);payButton.setOnClickListener(v->createPayment());
        statusText=text("QRIS, Virtual Account, dan metode lain tampil sesuai channel Midtrans yang aktif.",12,"#64748B",false);statusText.setGravity(Gravity.CENTER);statusText.setPadding(dp(12),dp(12),dp(12),dp(12));LinearLayout.LayoutParams slp=new LinearLayout.LayoutParams(-1,-2);slp.setMargins(0,dp(12),0,0);c.addView(statusText,slp);
    }
    private void infoCard(){ LinearLayout c=card();c.setPadding(dp(16),dp(14),dp(16),dp(14));add(c,0,0,0,0);c.addView(text("🔐 Aman & otomatis",15,"#0B3A78",true));TextView t=text("Aplikasi tidak pernah menambah saldo sendiri. Saldo hanya ditambahkan server Transiva setelah webhook Midtrans tervalidasi, sehingga pembayaran ganda tidak menggandakan saldo.",12,"#64748B",false);t.setPadding(0,dp(6),0,0);c.addView(t); }
    private void quick(LinearLayout p,int a){Button b=outline(rupiah(a));b.setTextSize(11);b.setOnClickListener(v->amountInput.setText(String.valueOf(a)));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(42),1);if(p.getChildCount()>0)lp.setMargins(dp(7),0,0,0);p.addView(b,lp);}

    private void createPayment(){ int amount=parse(amountInput.getText().toString()); if(amount<MIN_DEPOSIT){info("Nominal Tidak Valid","Minimal deposit Rp10.000.");return;} setBusy(true,"Menyiapkan pembayaran..."); DriverNetworkExecutor.execute(()->{try{ JSONObject req=new JSONObject();req.put("amount",amount);JSONObject j=postJson(BASE_URL+"server/driver_midtrans_create.php",req,UUID.randomUUID().toString());boolean ok=j.optBoolean("success",false);String msg=j.optString("message","Gagal membuat pembayaran");String url=j.optString("redirect_url","");String order=j.optString("order_id","");main.post(()->{setBusy(false,msg);if(!ok||url.isEmpty()){info("Deposit",msg);return;}lastOrderId=order;Intent i=new Intent(this,DriverMidtransPaymentActivity.class);i.putExtra("payment_url",url);i.putExtra("order_id",order);i.putExtra("amount",amount);startActivityForResult(i,REQ_PAY);});}catch(Exception e){main.post(()->{setBusy(false,"Koneksi bermasalah");info("Koneksi","Gagal menghubungi server pembayaran.");});}}); }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){super.onActivityResult(requestCode,resultCode,data);if(requestCode==REQ_PAY)refreshAfterPayment(0);}
    private void refreshAfterPayment(int attempt){ if(lastOrderId.isEmpty()){loadWallet();return;} setStatus(attempt==0?"Memeriksa konfirmasi pembayaran...":"Menunggu konfirmasi Midtrans..."); DriverNetworkExecutor.execute(()->{try{JSONObject b=new JSONObject();b.put("order_id",lastOrderId);JSONObject j=postJson(BASE_URL+"server/driver_midtrans_status.php",b,null);JSONObject d=j.optJSONObject("deposit");String st=d==null?"":d.optString("status","");int balance=j.optInt("balance",0);main.post(()->{if(balanceText!=null)balanceText.setText(rupiah(balance));if("PAID".equalsIgnoreCase(st)){setStatus("✅ Deposit berhasil. Saldo sudah masuk otomatis.");lastOrderId="";loadWallet();info("Deposit Berhasil","Pembayaran sudah terkonfirmasi dan saldo driver telah ditambahkan.");}else if(attempt<4){main.postDelayed(()->refreshAfterPayment(attempt+1),3000);}else{setStatus("Pembayaran belum terkonfirmasi. Jika sudah membayar, status akan diperbarui otomatis.");loadWallet();}});}catch(Exception e){main.post(()->{if(attempt<3)main.postDelayed(()->refreshAfterPayment(attempt+1),3000);else loadWallet();});}}); }
    private void loadWallet(){ DriverNetworkExecutor.execute(()->{try{JSONObject j=getJson(BASE_URL+"server/driver_wallet_summary.php");if(j.optBoolean("success",false)){String b=rupiah(j.optDouble("balance",0));String p=rupiah(j.optDouble("pending_deposit",0));main.post(()->{balanceText.setText(b);pendingText.setText("Pending deposit: "+p);});}}catch(Exception ignored){}}); }

    private JSONObject getJson(String u)throws Exception{return request("GET",u,null,null);} private JSONObject postJson(String u,JSONObject body,String idem)throws Exception{return request("POST",u,body,idem);} private JSONObject request(String method,String u,JSONObject body,String idem)throws Exception{HttpURLConnection c=null;try{c=(HttpURLConnection)new URL(u).openConnection();c.setRequestMethod(method);c.setConnectTimeout(TIMEOUT);c.setReadTimeout(TIMEOUT);c.setRequestProperty("Accept","application/json");String token=session==null?"":session.getToken();if(token!=null&&!token.trim().isEmpty())c.setRequestProperty("Authorization","Bearer "+token.trim());c.setRequestProperty("X-Device-UUID",DeviceIdentityManager.getInstallationUuid(this));c.setRequestProperty("X-App-Scope","driver");if(idem!=null&&!idem.isEmpty())c.setRequestProperty("Idempotency-Key",idem);if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);try(OutputStream out=c.getOutputStream()){out.write(bytes);}}int code=c.getResponseCode();InputStream is=(code>=200&&code<300)?c.getInputStream():c.getErrorStream();String raw=read(is);return raw.trim().isEmpty()?new JSONObject():new JSONObject(raw);}finally{if(c!=null)c.disconnect();}}
    private String read(InputStream in)throws Exception{if(in==null)return"";BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();String l;while((l=r.readLine())!=null)s.append(l);r.close();return s.toString();}
    private void setBusy(boolean b,String m){progress.setVisibility(b?View.VISIBLE:View.GONE);payButton.setEnabled(!b);payButton.setAlpha(b?.65f:1f);setStatus(m);} private void setStatus(String s){if(statusText!=null)statusText.setText(s);}
    private int parse(String v){try{return Integer.parseInt(v.replace(".","").replace(",","").trim());}catch(Exception e){return 0;}} private String rupiah(double v){NumberFormat nf=NumberFormat.getCurrencyInstance(new Locale("id","ID"));nf.setMaximumFractionDigits(0);return nf.format(v).replace("Rp","Rp ");}
    private LinearLayout card(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);v.setBackground(stroke("#FFFFFF","#E2ECF8",22,1));v.setElevation(dp(2));return v;} private void add(View v,int l,int t,int r,int b){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(dp(l),dp(t),dp(r),dp(b));root.addView(v,lp);} private TextView text(String s,int sp,String color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(Color.parseColor(color));if(bold)t.setTypeface(Typeface.DEFAULT_BOLD);return t;} private Button button(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setTypeface(Typeface.DEFAULT_BOLD);b.setBackground(gradient("#086BFF","#2EA2FF",18));return b;} private Button outline(String s){Button b=button(s);b.setTextColor(Color.parseColor("#0B7CFF"));b.setBackground(stroke("#FFFFFF","#9DCAFF",18,1));return b;} private GradientDrawable round(String c,int r){GradientDrawable g=new GradientDrawable();g.setColor(Color.parseColor(c));g.setCornerRadius(dp(r));return g;} private GradientDrawable stroke(String c,String s,int r,int w){GradientDrawable g=round(c,r);g.setStroke(dp(w),Color.parseColor(s));return g;} private GradientDrawable gradient(String a,String b,int r){GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{Color.parseColor(a),Color.parseColor(b)});g.setCornerRadius(dp(r));return g;} private int dp(int v){return(int)(v*getResources().getDisplayMetrics().density+.5f);} private void info(String t,String m){try{new AlertDialog.Builder(this).setTitle(t).setMessage(m).setPositiveButton("OK",null).show();}catch(Exception ignored){}}
}
