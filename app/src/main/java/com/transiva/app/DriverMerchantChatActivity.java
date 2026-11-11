package com.transiva.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.text.TextUtils;
import androidx.core.content.ContextCompat;
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
    private static final long REFRESH_MS=20000L;
    private final Handler main=new Handler(Looper.getMainLooper());
    private LinearLayout messages;
    private ScrollView scroll;
    private EditText input;
    private final OrderChatStyle chatStyle=new OrderChatStyle(this);
    private final android.util.SparseArray<TextView> receiptViews=new android.util.SparseArray<>();
    private long focusedSince, latestShownAt;
    private int readThrough;
    private final Runnable reportVisibleRead=()->{
        if(canRead()&&this.lastId>readThrough)load(false);
    };
    private Button send, call, quick;
    private TextView title;
    private boolean chatOpen;
    private TextView status;
    private String orderId="", orderDbId="", merchantName="Merchant";
    private int lastId=0;
    private boolean loading=false,sending=false,stopped=false;
    private SessionManager session;

    private final BroadcastReceiver chatPush = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String id = safe(intent.getStringExtra("order_id"));
            if ("merchant_driver_chat".equalsIgnoreCase(intent.getStringExtra("type"))
                    && !id.isEmpty() && (id.equals(orderId) || id.equals(orderDbId))) load(false);
        }
    };

    private final Runnable refresh=new Runnable(){@Override public void run(){if(!stopped){load(false);main.postDelayed(this,WaveLoadGuard.jitter(DriverPollingCoordinator.interval(DriverMerchantChatActivity.this, REFRESH_MS)));}}};

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        session=new SessionManager(this);
        orderId=safe(getIntent().getStringExtra("order_id"));
        orderDbId=safe(getIntent().getStringExtra("order_db_id"));
        merchantName=first(getIntent().getStringExtra("merchant_name"),"Merchant");
        DriverSystemBars.setStatusBarColor(this, Color.parseColor("#0B7CFF"));
        build();
        if(orderId.isEmpty()&&orderDbId.isEmpty()){toast("ID order tidak ditemukan.");send.setEnabled(false);return;}
        load(true);
    }
    @Override protected void onResume(){
        super.onResume();
        stopped=false;
        if(hasWindowFocus()){focusedSince=android.os.SystemClock.elapsedRealtime();main.postDelayed(reportVisibleRead,1250);}
        ContextCompat.registerReceiver(this,chatPush,new IntentFilter(TransivaFirebaseService.ACTION_DRIVER_DATA_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        if(!orderId.isEmpty()||!orderDbId.isEmpty()) load(false);
        main.removeCallbacks(refresh);
        main.postDelayed(refresh,WaveLoadGuard.jitter(DriverPollingCoordinator.interval(this, REFRESH_MS)));
    }
    @Override protected void onPause(){
        stopped=true;focusedSince=0;main.removeCallbacks(reportVisibleRead);
        main.removeCallbacks(refresh);
        unregisterReceiver(chatPush);
        super.onPause();
    }

    private void build(){
        LinearLayout page=new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(12),dp(8),dp(12),dp(8));
        page.setBackgroundColor(DriverThemeTokens.color(this,"#F4F8FD"));
        chatStyle.backdrop(page);
        setContentView(page);
        DriverAppSettings.apply(this);

        LinearLayout head=new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(3),dp(2),dp(6),dp(2));
        head.setBackground(round("#FFFFFF",16));
        Button back=button("‹",false);
        back.setTextSize(23);back.setContentDescription("Kembali");
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setOnClickListener(v->finish());
        head.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));

        chatStyle.addAvatar(head,merchantName,getIntent().getStringExtra("participant_photo"));
        LinearLayout info=new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        title=text(merchantName,16,"#0B3A78",true);
        title.setMaxLines(1);title.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(title);
        status=text("Pesanan #"+(orderId.isEmpty()?orderDbId:orderId),11,"#64748B",false);
        status.setMaxLines(2);
        info.addView(status);
        head.addView(info,new LinearLayout.LayoutParams(0,-2,1));

        call=button("☎",false);
        call.setContentDescription("Panggil Merchant");
        call.setEnabled(false);
        call.setOnClickListener(v->chooseCall());
        head.addView(call,new LinearLayout.LayoutParams(dp(48),dp(48)));
        page.addView(head);

        String[] templates={"Saya sudah sampai di resto","Pesanan sudah siap?","Saya tunggu di depan","Terima kasih"};
        quick=button("Pesan cepat  ▾",false);
        quick.setBackgroundColor(Color.TRANSPARENT);
        quick.setEnabled(false);
        quick.setOnClickListener(v->PremiumDialogs.builder(this).setTitle("Pesan cepat")
                .setItems(templates,(dialog,which)->sendText(templates[which]))
                .setNegativeButton("Tutup",null).show());
        page.addView(quick,new LinearLayout.LayoutParams(dp(118),dp(48)));

        scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        messages=new LinearLayout(this);
        messages.setOrientation(LinearLayout.VERTICAL);
        messages.setPadding(dp(2),0,dp(2),dp(6));
        scroll.addView(messages,new ScrollView.LayoutParams(-1,-2));
        scroll.setOnScrollChangeListener((v,x,y,oldX,oldY)->{
            if(canRead()&&lastId>readThrough){
                main.removeCallbacks(reportVisibleRead);
                main.postDelayed(reportVisibleRead,1250);
            }
        });
        chatStyle.overflow(head,null,query->DriverMessageApi.get(session,chatEndpoint()+query),messages);
        page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout composer=new LinearLayout(this);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        composer.setPadding(dp(5),dp(4),dp(5),dp(4));
        composer.setBackground(round("#FFFFFF",16));
        input=new EditText(this);
        input.setHint("Pesan ke merchant…");
        input.setMaxLines(3);
        input.setTextSize(14);
        input.setTextColor(DriverThemeTokens.color(this,"#0F172A"));
        input.setHintTextColor(DriverThemeTokens.color(this,"#64748B"));
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setBackground(stroke("#F8FBFF","#D8E4F2",12,1));
        input.setPadding(dp(10),dp(7),dp(10),dp(7));
        input.setMinHeight(dp(48));
        input.setEnabled(false);
        composer.addView(input,new LinearLayout.LayoutParams(0,-2,1));
        send=button("Kirim",true);
        send.setEnabled(false);
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(dp(64),dp(48));
        sp.setMarginStart(dp(6));
        composer.addView(send,sp);
        send.setOnClickListener(v->sendText(input.getText().toString()));
        page.addView(composer);
    }

    private void chooseCall(){
        if(!chatOpen)return;
        PremiumDialogs.builder(this).setTitle("Panggil Merchant")
                .setItems(new String[]{"Panggilan suara","Panggilan video"},(dialog,which)->{
                    if(!chatOpen)return;
                    Intent intent=new Intent(this,WebRtcCallActivity.class)
                            .putExtra("order_id",first(orderId,orderDbId))
                            .putExtra("order_db_id",orderDbId.matches("[0-9]+")?orderDbId:"")
                            .putExtra("source","orders")
                            .putExtra("peer_role","merchant")
                            .putExtra("peer_name",merchantName)
                            .putExtra("call_type",which==0?"audio":"video");
                    startActivity(intent);
                })
                .setNegativeButton("Tutup",null).show();
    }

    @Override public void onWindowFocusChanged(boolean focused){
        super.onWindowFocusChanged(focused);
        focusedSince=focused?android.os.SystemClock.elapsedRealtime():0;
        main.removeCallbacks(reportVisibleRead);
        if(focused&&lastId>readThrough)main.postDelayed(reportVisibleRead,1250);
    }

    private boolean nearBottom(){
        return scroll!=null&&messages!=null&&messages.getHeight()-scroll.getScrollY()-scroll.getHeight()<dp(80);
    }
    private boolean canRead(){
        android.os.PowerManager power=(android.os.PowerManager)getSystemService(POWER_SERVICE);
        return !stopped&&hasWindowFocus()&&nearBottom()&&power!=null&&power.isInteractive();
    }

    private String chatEndpoint(){
        return BASE+"getMerchantDriverChat.php?chat_ui=1&order_id="+enc(orderId)+"&order_db_id="+enc(orderDbId);
    }

    private String readParams(int through,long visible){
        return through<=0?"":"&mark_read=1&read_through_id="+through+"&visible_ms="+visible;
    }

    private void load(boolean initial){
        if(loading)return;loading=true;
        int cursor=lastId;
        String receiptIds=OrderChatStyle.receiptIds(receiptViews);
        long visible=focusedSince==0?0:android.os.SystemClock.elapsedRealtime()-Math.max(focusedSince,latestShownAt);
        int mark=canRead()&&visible>=1200&&cursor>readThrough?cursor:0;
        boolean queued=DriverNetworkExecutor.execute(()->{try{
            String url=chatEndpoint()+"&last_id="+cursor+"&receipt_ids="+receiptIds+readParams(mark,visible);
            JSONObject r=DriverMessageApi.get(session,url);runOnUiThread(()->{if(r.optBoolean("success"))readThrough=Math.max(readThrough,mark);apply(r,initial);});
        }catch(Exception e){if(initial)runOnUiThread(()->status.setText("Menghubungkan ulang…"));}
        finally{main.post(()->loading=false);}});
        if(!queued)loading=false;
    }
    private void apply(JSONObject r,boolean initial){
        boolean available=r.optBoolean("success",false);
        chatOpen=available&&!r.optBoolean("ended",false);
        call.setEnabled(chatOpen);call.setAlpha(chatOpen?1f:.45f);quick.setEnabled(chatOpen);
        input.setEnabled(chatOpen);send.setEnabled(chatOpen&&!sending);
        if(!available){status.setText(r.optString("message","Chat belum tersedia"));return;}
        String rn=r.optString("restaurant_name","");if(!rn.isEmpty()){merchantName=rn;title.setText(rn);}
        status.setText(chatOpen?"Pesanan #"+first(orderId,orderDbId):"Riwayat pesanan");
        boolean wasAtBottom=nearBottom();
        int priorId=lastId;
        JSONArray a=r.optJSONArray("messages");if(a!=null)for(int i=0;i<a.length();i++){JSONObject m=a.optJSONObject(i);if(m==null||m.optInt("id",0)<=lastId)continue;addMessage(m);lastId=Math.max(lastId,m.optInt("id",0));}
        JSONArray receipts=r.optJSONArray("receipt_updates");
        if(receipts!=null)for(int i=0;i<receipts.length();i++){
            JSONObject update=receipts.optJSONObject(i);
            TextView receipt=update==null?null:receiptViews.get(update.optInt("id"));
            if(receipt!=null)chatStyle.receipt(receipt,update,true);
        }
        chatStyle.profile(r,merchantName,getIntent().getStringExtra("participant_photo"));
        boolean addedCall=chatStyle.mergeCalls(messages,r);
        if(lastId>priorId)latestShownAt=android.os.SystemClock.elapsedRealtime();
        if(!stopped&&hasWindowFocus()&&lastId>readThrough){
            main.removeCallbacks(reportVisibleRead);
            main.postDelayed(reportVisibleRead,1250);
        }
        if(initial&&messages.getChildCount()==0){TextView e=text("Belum ada pesan. Gunakan Quick Chat atau ketik pesan ke merchant.",12,"#64748B",false);e.setGravity(Gravity.CENTER);e.setPadding(dp(20),dp(40),dp(20),dp(20));messages.addView(e);}
        if(r.optBoolean("ended",false)){input.setEnabled(false);send.setEnabled(false);}if(initial||(wasAtBottom&&(lastId>priorId||addedCall)))scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));
    }
    private void addMessage(JSONObject message){
        if(messages.getChildCount()==1&&messages.getChildAt(0) instanceof TextView
                &&((TextView)messages.getChildAt(0)).getText().toString().startsWith("Belum ada pesan"))
            messages.removeAllViews();
        boolean mine="driver".equalsIgnoreCase(message.optString("sender_type",""));
        LinearLayout wrapper=new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setGravity(mine?Gravity.END:Gravity.START);
        TextView bubble=text(message.optString("message",""),14,mine?"#FFFFFF":"#0F172A",false);
        if(mine)bubble.setTextColor(Color.WHITE);
        bubble.setMaxWidth(Math.min(dp(420),Math.round(getResources().getDisplayMetrics().widthPixels*.74f)));
        wrapper.addView(bubble);
        TextView timestamp=text("",10,"#64748B",false);
        chatStyle.receipt(timestamp,message,mine);
        chatStyle.appendMeta(wrapper,timestamp);
        if(mine)receiptViews.put(message.optInt("id",0),timestamp);
        chatStyle.decorate(wrapper,message,mine);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.setMargins(0,dp(2),0,dp(2));
        messages.addView(wrapper,lp);
    }

    private void sendText(String raw){
        String msg=safe(raw).trim();if(msg.isEmpty()||sending||!chatOpen)return;if(msg.length()>500){toast("Pesan maksimal 500 karakter.");return;}sending=true;send.setEnabled(false);
        boolean queued=DriverNetworkExecutor.execute(()->{try{JSONObject p=new JSONObject();p.put("order_id",orderId);p.put("order_db_id",orderDbId);p.put("message",msg);JSONObject r=DriverMessageApi.post(session,BASE+"sendMerchantDriverChat.php",p);runOnUiThread(()->{if(r.optBoolean("success",false)){input.setText("");load(false);}else toast(r.optString("message","Pesan gagal dikirim"));});}catch(Exception e){runOnUiThread(()->toast("Koneksi gagal. Pesan belum dikirim."));}finally{main.post(()->{sending=false;send.setEnabled(chatOpen);});}});
        if(!queued){sending=false;send.setEnabled(chatOpen);toast("Koneksi sibuk. Coba kirim lagi.");}
    }

    private Button button(String s,boolean primary){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(13);b.setTypeface(Typeface.DEFAULT_BOLD);b.setTextColor(primary?DriverThemeTokens.onAccent(this):DriverThemeTokens.color(this,"#0B7CFF"));b.setPadding(dp(6),0,dp(6),0);b.setElevation(0);b.setBackground(primary?round("#0B7CFF",14):stroke("#FFFFFF","#CFE2FF",14,1));return b;}
    private TextView text(String s,int sp,String c,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setTextColor(DriverThemeTokens.color(this,c));if(bold)v.setTypeface(Typeface.DEFAULT_BOLD);return v;}
    private GradientDrawable round(String c,int r){GradientDrawable g=new GradientDrawable();g.setColor(DriverThemeTokens.color(this,c));g.setCornerRadius(dp(r));return g;}
    private GradientDrawable stroke(String c,String sc,int r,int w){GradientDrawable g=round(c,r);g.setStroke(dp(w),DriverThemeTokens.color(this,sc));return g;}
    private int dp(int v){return(int)(v*getResources().getDisplayMetrics().density+.5f);}private String safe(String s){return s==null?"":s;}private String first(String...v){if(v!=null)for(String s:v)if(s!=null&&!s.trim().isEmpty())return s.trim();return"";}private String enc(String s){try{return URLEncoder.encode(safe(s),StandardCharsets.UTF_8.name());}catch(Exception e){return"";}}private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
}
