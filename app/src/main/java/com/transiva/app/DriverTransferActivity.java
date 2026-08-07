package com.transiva.app;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.*;

public class DriverTransferActivity extends Activity {
    private static final String BASE="https://transiva.my.id/server/";
    private SessionManager session;
    private EditText recipient, amount, note;
    private Button submit;
    private TextView quotaInfo, balanceInfo;
    private final Handler main=new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b){ super.onCreate(b); session=new SessionManager(this); if(!session.isLoggedIn()){finish();return;} setContentView(screen()); DriverAppSettings.apply(this); }

    private View screen(){
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(Color.parseColor("#F6F9FE"));
        LinearLayout c=new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(dp(18),dp(18),dp(18),dp(28)); scroll.addView(c,new ScrollView.LayoutParams(-1,-2));
        TextView back=text("‹  Transfer Antar Driver",24,"#0B3A78",true); back.setOnClickListener(v->finish()); c.addView(back);
        c.addView(text("Kirim saldo Transpay ke driver lain. Gratis 5 kali setiap bulan, transfer berikutnya dikenakan biaya admin sesuai aturan customer.",13,"#64748B",false),lp(0,8,0,16));
        LinearLayout card=card(); balanceInfo=text("Saldo: "+money(parse(session.getBalance())),18,"#0B3A78",true); card.addView(balanceInfo);
        quotaInfo=text("Kuota gratis dihitung saat detail transfer diperiksa.",12,"#64748B",false); card.addView(quotaInfo,lp(0,5,0,14));
        recipient=input("Username driver penerima",InputType.TYPE_CLASS_TEXT); card.addView(recipient,lp(0,0,0,10));
        amount=input("Nominal minimal Rp10.000",InputType.TYPE_CLASS_NUMBER); card.addView(amount,lp(0,0,0,10));
        note=input("Catatan (opsional)",InputType.TYPE_CLASS_TEXT); card.addView(note,lp(0,0,0,14));
        submit=button("Periksa & Transfer"); submit.setOnClickListener(v->quote()); card.addView(submit,new LinearLayout.LayoutParams(-1,dp(48))); c.addView(card);
        return scroll;
    }

    private void quote(){
        String to=recipient.getText().toString().trim(); long val=parse(amount.getText().toString());
        if(to.isEmpty()){info("Penerima belum diisi");return;} if(val<10000){info("Minimal transfer Rp10.000");return;}
        loading(true); new Thread(()->{ try{
            String requestId="DRV-"+System.currentTimeMillis()+"-"+UUID.randomUUID().toString().substring(0,8);
            JSONObject req=baseRequest(); req.put("recipient",to); req.put("amount",val); req.put("request_id",requestId);
            JSONObject q=post("driver_wallet_quote.php",req);
            if(!q.optBoolean("success")) throw new Exception(q.optString("message","Gagal memeriksa transfer"));
            JSONObject d=q.optJSONObject("data"); if(d==null)d=q;
            JSONObject finalD=d; main.post(()->confirm(finalD,to,val,requestId));
        }catch(Exception e){main.post(()->{loading(false);info(e.getMessage());});}}).start();
    }

    private void confirm(JSONObject d,String to,long val,String requestId){
        loading(false); long fee=d.optLong("fee",0), total=d.optLong("total_debit",val+fee); int remain=d.optInt("free_remaining_after",0);
        quotaInfo.setText("Sisa transfer gratis setelah transaksi: "+remain+"x");
        String msg="Penerima: "+d.optString("receiver_username",to)+"\nNominal: "+money(val)+"\nBiaya admin: "+money(fee)+"\nTotal saldo keluar: "+money(total);
        new AlertDialog.Builder(this).setTitle("Konfirmasi Transfer").setMessage(msg).setNegativeButton("Batal",null).setPositiveButton("Transfer",(x,w)->execute(d,to,val,requestId)).show();
    }

    private void execute(JSONObject q,String to,long val,String requestId){
        loading(true); new Thread(()->{try{
            JSONObject req=baseRequest(); req.put("recipient",to); req.put("amount",val); req.put("note",note.getText().toString().trim()); req.put("request_id",requestId); req.put("quote_token",q.optString("quote_token"));
            JSONObject r=post("driver_wallet_transfer.php",req); if(!r.optBoolean("success"))throw new Exception(r.optString("message","Transfer gagal"));
            JSONObject d=r.optJSONObject("data"); if(d==null)d=r; long bal=d.optLong("balance_after",d.optLong("sender_balance",0)); session.put("balance",String.valueOf(bal));
            main.post(()->{loading(false);balanceInfo.setText("Saldo: "+money(bal)); new AlertDialog.Builder(this).setTitle("Transfer Berhasil").setMessage(r.optString("message","Dana berhasil dikirim")).setPositiveButton("Selesai",(a,b)->finish()).show();});
        }catch(Exception e){main.post(()->{loading(false);info(e.getMessage());});}}).start();
    }

    private JSONObject baseRequest() throws Exception { JSONObject o=new JSONObject(); o.put("user_id",parse(session.getUserId())); o.put("username",session.getUsername()); return o; }
    private JSONObject post(String path,JSONObject body)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(BASE+path).openConnection();
        c.setConnectTimeout(15000);c.setReadTimeout(20000);c.setRequestMethod("POST");c.setDoOutput(true);
        c.setRequestProperty("Content-Type","application/json; charset=UTF-8");
        c.setRequestProperty("Accept","application/json");
        String token=session.getToken();
        if(token!=null && !token.trim().isEmpty()) c.setRequestProperty("Authorization","Bearer "+token.trim());
        c.setRequestProperty("X-Device-UUID",DeviceIdentityManager.getInstallationUuid(this));
        c.setRequestProperty("X-App-Scope","driver");
        byte[] b=body.toString().getBytes(StandardCharsets.UTF_8);
        try(OutputStream os=c.getOutputStream()){os.write(b);}
        InputStream in=c.getResponseCode()>=400?c.getErrorStream():c.getInputStream();
        StringBuilder response=new StringBuilder();
        if(in!=null){try(BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){String l;while((l=r.readLine())!=null)response.append(l);}}
        c.disconnect();
        if(response.length()==0) throw new IOException("Respons server kosong");
        return new JSONObject(response.toString());
    }
    private void loading(boolean x){submit.setEnabled(!x);submit.setText(x?"Memproses...":"Periksa & Transfer");}
    private void info(String m){new AlertDialog.Builder(this).setTitle("Informasi").setMessage(m==null?"Terjadi kesalahan":m).setPositiveButton("OK",null).show();}
    private LinearLayout card(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);v.setPadding(dp(16),dp(16),dp(16),dp(16));GradientDrawable g=new GradientDrawable();g.setColor(Color.WHITE);g.setCornerRadius(dp(18));v.setBackground(g);v.setElevation(dp(2));return v;}
    private EditText input(String h,int type){EditText e=new EditText(this);e.setHint(h);e.setTextSize(14);e.setInputType(type);e.setPadding(dp(13),0,dp(13),0);GradientDrawable g=new GradientDrawable();g.setColor(Color.parseColor("#F8FAFC"));g.setStroke(dp(1),Color.parseColor("#DCE6F2"));g.setCornerRadius(dp(12));e.setBackground(g);e.setSingleLine(true);e.setMinHeight(dp(48));return e;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextColor(Color.WHITE);b.setTextSize(14);b.setAllCaps(false);b.setTypeface(Typeface.DEFAULT_BOLD);GradientDrawable g=new GradientDrawable();g.setColor(Color.parseColor("#0B7CFF"));g.setCornerRadius(dp(14));b.setBackground(g);return b;}
    private TextView text(String s,int z,String color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(z);t.setTextColor(Color.parseColor(color));t.setTypeface(Typeface.DEFAULT,bold?Typeface.BOLD:Typeface.NORMAL);return t;}
    private LinearLayout.LayoutParams lp(int l,int t,int r,int b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(dp(l),dp(t),dp(r),dp(b));return p;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);} private long parse(String s){try{return Long.parseLong(s.replaceAll("[^0-9]",""));}catch(Exception e){return 0;}} private String money(long n){return NumberFormat.getCurrencyInstance(new Locale("id","ID")).format(n).replace(",00","");}
}
