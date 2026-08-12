package com.transiva.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class DriverMerchantChatActivity extends Activity {
    private static final String BASE="https://transiva.my.id/server/";
    private static final long REFRESH_MS=15000L;
    private final Handler main=new Handler(Looper.getMainLooper());
    private LinearLayout messages;
    private ScrollView scroll;
    private EditText input;
    private Button send;
    private TextView status;
    private String orderId="", orderDbId="", merchantName="Merchant";
    private int lastId=0;
    private boolean loading=false,sending=false,stopped=false;
    private SessionManager session;

    private final Runnable refresh=new Runnable(){@Override public void run(){if(!stopped){load(false);main.postDelayed(this,WaveLoadGuard.jitter(REFRESH_MS));}}};

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        session=new SessionManager(this);
        orderId=safe(getIntent().getStringExtra("order_id"));
        orderDbId=safe(getIntent().getStringExtra("order_db_id"));
        merchantName=first(getIntent().getStringExtra("merchant_name"),"Merchant");
        getWindow().setStatusBarColor(Color.parseColor("#0B7CFF"));
        build();
        if(orderId.isEmpty()&&orderDbId.isEmpty()){toast("ID order tidak ditemukan.");send.setEnabled(false);return;}
        load(true);
    }
    @Override protected void onResume(){super.onResume();stopped=false;main.removeCallbacks(refresh);main.postDelayed(refresh,WaveLoadGuard.jitter(REFRESH_MS));}
    @Override protected void onPause(){super.onPause();stopped=true;main.removeCallbacks(refresh);}

    private void build(){
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(14),dp(14),dp(14),dp(12));page.setBackgroundColor(Color.parseColor("#F4F8FD"));setContentView(page);
        LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);head.setPadding(dp(12),dp(10),dp(12),dp(10));head.setBackground(round("#FFFFFF",18));
        TextView back=text("←",26,"#0B7CFF",true);back.setGravity(Gravity.CENTER);back.setOnClickListener(v->finish());head.addView(back,new LinearLayout.LayoutParams(dp(44),dp(44)));
        LinearLayout info=new LinearLayout(this);info.setOrientation(LinearLayout.VERTICAL);info.setPadding(dp(8),0,0,0);info.addView(text(merchantName,17,"#0B3A78",true));status=text("Chat Merchant • Order #"+(orderId.isEmpty()?orderDbId:orderId),11,"#64748B",false);info.addView(status);head.addView(info,new LinearLayout.LayoutParams(0,-2,1));page.addView(head);

        LinearLayout q1=new LinearLayout(this);q1.setOrientation(LinearLayout.HORIZONTAL);q1.setPadding(0,dp(10),0,dp(4));addQuick(q1,"Saya sudah sampai di resto");addQuick(q1,"Pesanan sudah siap?");page.addView(q1);
        LinearLayout q2=new LinearLayout(this);q2.setOrientation(LinearLayout.HORIZONTAL);q2.setPadding(0,0,0,dp(8));addQuick(q2,"Saya tunggu di depan");addQuick(q2,"Terima kasih");page.addView(q2);

        scroll=new ScrollView(this);scroll.setFillViewport(true);messages=new LinearLayout(this);messages.setOrientation(LinearLayout.VERTICAL);messages.setPadding(dp(2),dp(6),dp(2),dp(8));scroll.addView(messages,new ScrollView.LayoutParams(-1,-2));page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout composer=new LinearLayout(this);composer.setGravity(Gravity.CENTER_VERTICAL);composer.setPadding(dp(8),dp(7),dp(8),dp(7));composer.setBackground(round("#FFFFFF",18));
        input=new EditText(this);input.setHint("Ketik pesan ke merchant...");input.setSingleLine(false);input.setMaxLines(3);input.setTextSize(14);input.setTextColor(Color.parseColor("#0F172A"));input.setHintTextColor(Color.parseColor("#94A3B8"));input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES|InputType.TYPE_TEXT_FLAG_MULTI_LINE);input.setBackground(stroke("#F8FBFF","#D8E4F2",14,1));input.setPadding(dp(12),0,dp(12),0);composer.addView(input,new LinearLayout.LayoutParams(0,dp(50),1));
        send=button("Kirim",true);LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(dp(86),dp(50));sp.setMargins(dp(8),0,0,0);composer.addView(send,sp);send.setOnClickListener(v->sendText(input.getText().toString()));page.addView(composer);
    }
    private void addQuick(LinearLayout parent,String s){Button b=button(s,false);b.setTextSize(11);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(44),1);lp.setMargins(dp(3),0,dp(3),0);parent.addView(b,lp);b.setOnClickListener(v->sendText(s));}

    private void load(boolean initial){
        if(loading)return;loading=true;
        DriverNetworkExecutor.execute(()->{try{
            String url=BASE+"getMerchantDriverChat.php?order_id="+enc(orderId)+"&order_db_id="+enc(orderDbId)+"&last_id="+lastId+"&_="+System.currentTimeMillis();
            JSONObject r=DriverMessageApi.get(session,url);runOnUiThread(()->apply(r,initial));
        }catch(Exception e){if(initial)runOnUiThread(()->status.setText("Koneksi chat belum tersedia • akan mencoba lagi"));}finally{loading=false;}});
    }
    private void apply(JSONObject r,boolean initial){
        if(!r.optBoolean("success",false)){status.setText(r.optString("message","Chat belum tersedia"));return;}
        String rn=r.optString("restaurant_name","");if(!rn.isEmpty())merchantName=rn;
        status.setText(r.optBoolean("ended",false)?"Riwayat chat • order selesai":"Online • chat driver ↔ merchant");
        JSONArray a=r.optJSONArray("messages");if(a!=null)for(int i=0;i<a.length();i++){JSONObject m=a.optJSONObject(i);if(m==null)continue;addMessage(m);lastId=Math.max(lastId,m.optInt("id",0));}
        if(initial&&messages.getChildCount()==0){TextView e=text("Belum ada pesan. Gunakan Quick Chat atau ketik pesan ke merchant.",12,"#64748B",false);e.setGravity(Gravity.CENTER);e.setPadding(dp(20),dp(40),dp(20),dp(20));messages.addView(e);}
        if(r.optBoolean("ended",false)){input.setEnabled(false);send.setEnabled(false);}scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));
    }
    private void addMessage(JSONObject m){
        if(messages.getChildCount()==1&&messages.getChildAt(0) instanceof TextView&&((TextView)messages.getChildAt(0)).getText().toString().startsWith("Belum ada pesan"))messages.removeAllViews();
        boolean mine="driver".equalsIgnoreCase(m.optString("sender_type",""));LinearLayout line=new LinearLayout(this);line.setGravity(mine?Gravity.END:Gravity.START);TextView bubble=text(m.optString("message",""),14,mine?"#FFFFFF":"#0F172A",false);bubble.setPadding(dp(12),dp(9),dp(12),dp(9));bubble.setBackground(round(mine?"#0B7CFF":"#FFFFFF",16));bubble.setMaxWidth(dp(285));line.addView(bubble);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(3),0,dp(3));messages.addView(line,lp);
    }
    private void sendText(String raw){
        String msg=safe(raw).trim();if(msg.isEmpty()||sending)return;if(msg.length()>500){toast("Pesan maksimal 500 karakter.");return;}sending=true;send.setEnabled(false);
        DriverNetworkExecutor.execute(()->{try{JSONObject p=new JSONObject();p.put("order_id",orderId);p.put("order_db_id",orderDbId);p.put("message",msg);JSONObject r=DriverMessageApi.post(session,BASE+"sendMerchantDriverChat.php",p);runOnUiThread(()->{if(r.optBoolean("success",false)){input.setText("");load(false);}else toast(r.optString("message","Pesan gagal dikirim"));});}catch(Exception e){runOnUiThread(()->toast("Koneksi gagal. Pesan belum dikirim."));}finally{sending=false;runOnUiThread(()->send.setEnabled(true));}});
    }

    private Button button(String s,boolean primary){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(13);b.setTypeface(Typeface.DEFAULT_BOLD);b.setTextColor(Color.parseColor(primary?"#FFFFFF":"#0B7CFF"));b.setBackground(primary?round("#0B7CFF",14):stroke("#FFFFFF","#CFE2FF",14,1));return b;}
    private TextView text(String s,int sp,String c,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setTextColor(Color.parseColor(c));if(bold)v.setTypeface(Typeface.DEFAULT_BOLD);return v;}
    private GradientDrawable round(String c,int r){GradientDrawable g=new GradientDrawable();g.setColor(Color.parseColor(c));g.setCornerRadius(dp(r));return g;}
    private GradientDrawable stroke(String c,String sc,int r,int w){GradientDrawable g=round(c,r);g.setStroke(dp(w),Color.parseColor(sc));return g;}
    private int dp(int v){return(int)(v*getResources().getDisplayMetrics().density+.5f);}private String safe(String s){return s==null?"":s;}private String first(String...v){if(v!=null)for(String s:v)if(s!=null&&!s.trim().isEmpty())return s.trim();return"";}private String enc(String s){try{return URLEncoder.encode(safe(s),StandardCharsets.UTF_8.name());}catch(Exception e){return"";}}private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
}
