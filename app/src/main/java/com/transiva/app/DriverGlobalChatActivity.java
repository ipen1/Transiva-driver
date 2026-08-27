package com.transiva.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
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
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DriverGlobalChatActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final List<MessageRef> refs=new ArrayList<>();
    private LinearLayout messages; private ScrollView scroll; private EditText input; private TextView mentionPill,status;
    private ProgressBar loading; private int lastId=0; private boolean busy=false; private long jumpId=0;
    private String myUsername="";
    private final Runnable refresh=new Runnable(){public void run(){if(!isFinishing()){load(false);main.postDelayed(this,12000L);}}};

    @Override protected void onCreate(Bundle b){ super.onCreate(b); getWindow().setStatusBarColor(Color.parseColor("#071426")); getWindow().setNavigationBarColor(Color.parseColor("#071426"));
        myUsername=new SessionManager(this).getUsername(); jumpId=getIntent().getLongExtra("jump_message_id",0L); setContentView(build()); DriverAppSettings.apply(this); load(true); main.postDelayed(refresh,12000L); }
    @Override protected void onNewIntent(android.content.Intent intent){super.onNewIntent(intent);setIntent(intent);jumpId=intent.getLongExtra("jump_message_id",0L);load(false);}
    @Override protected void onDestroy(){main.removeCallbacks(refresh);super.onDestroy();}

    private View build(){
        FrameLayout page=new FrameLayout(this); page.setBackgroundColor(Color.parseColor("#F4F7FB"));
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(12),dp(10),dp(12),dp(10));page.addView(root,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);head.setPadding(dp(10),dp(9),dp(10),dp(9));head.setBackground(round("#FFFFFF",18));
        TextView back=t("‹",34,"#0B3A78",true);back.setGravity(Gravity.CENTER);back.setOnClickListener(v->finish());head.addView(back,new LinearLayout.LayoutParams(dp(46),dp(46)));
        LinearLayout titles=new LinearLayout(this);titles.setOrientation(LinearLayout.VERTICAL);titles.setPadding(dp(8),0,0,0);TextView title=t("Driver Lounge",19,"#0B3A78",true);titles.addView(title);status=t("Chat global • pesan otomatis hilang 24 jam",10,"#6F7E90",false);titles.addView(status);head.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        mentionPill=t("@ Saya",12,"#0B7CFF",true);mentionPill.setPadding(dp(12),dp(8),dp(12),dp(8));mentionPill.setBackground(round("#EAF4FF",14));mentionPill.setOnClickListener(v->jumpNextMention());head.addView(mentionPill);root.addView(head,new LinearLayout.LayoutParams(-1,-2));
        loading=new ProgressBar(this);LinearLayout.LayoutParams plp=new LinearLayout.LayoutParams(dp(24),dp(24));plp.gravity=Gravity.CENTER_HORIZONTAL;plp.topMargin=dp(8);root.addView(loading,plp);
        scroll=new ScrollView(this);scroll.setFillViewport(true);messages=new LinearLayout(this);messages.setOrientation(LinearLayout.VERTICAL);messages.setPadding(dp(4),dp(8),dp(4),dp(12));scroll.addView(messages,new ScrollView.LayoutParams(-1,-2));root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout composer=new LinearLayout(this);composer.setGravity(Gravity.BOTTOM|Gravity.CENTER_VERTICAL);composer.setPadding(dp(8),dp(7),dp(7),dp(7));composer.setBackground(round("#FFFFFF",20));
        TextView at=t("@",20,"#0B7CFF",true);at.setGravity(Gravity.CENTER);at.setBackground(round("#EAF4FF",15));at.setOnClickListener(v->{int p=input.getSelectionStart();String insert="@";input.getText().insert(Math.max(0,p),insert);input.requestFocus();});composer.addView(at,new LinearLayout.LayoutParams(dp(42),dp(42)));
        input=new EditText(this);input.setHint("Ngobrol dengan driver lain…");input.setTextSize(14);input.setTextColor(Color.parseColor("#14263A"));input.setHintTextColor(Color.parseColor("#93A0AE"));input.setBackgroundColor(Color.TRANSPARENT);input.setSingleLine(false);input.setMaxLines(4);input.setImeOptions(EditorInfo.IME_ACTION_SEND);input.setOnEditorActionListener((v,a,e)->{if(a==EditorInfo.IME_ACTION_SEND){send();return true;}return false;});composer.addView(input,new LinearLayout.LayoutParams(0,-2,1));
        Button send=new Button(this);send.setText("Kirim");send.setTextColor(Color.WHITE);send.setTextSize(12);send.setTypeface(Typeface.DEFAULT_BOLD);send.setAllCaps(false);send.setBackground(round("#0B7CFF",16));send.setOnClickListener(v->send());composer.addView(send,new LinearLayout.LayoutParams(dp(76),dp(46)));root.addView(composer,new LinearLayout.LayoutParams(-1,-2));
        return page;
    }

    private void load(boolean initial){if(busy)return;busy=true;if(initial)loading.setVisibility(View.VISIBLE);DriverGlobalChatApi.get(this,0,new DriverGlobalChatApi.Callback(){public void onResult(JSONObject json){busy=false;loading.setVisibility(View.GONE);JSONObject data=json.optJSONObject("data");if(data==null)data=json;render(data.optJSONArray("messages"));int unread=data.optInt("unread_mentions",0);DriverGlobalChatStore.setUnreadMentions(DriverGlobalChatActivity.this,unread);mentionPill.setText(unread>0?"@ Saya  "+unread:"@ Saya");if(jumpId>0){jumpTo(jumpId,true);jumpId=0;}}public void onError(String m){busy=false;loading.setVisibility(View.GONE);status.setText(m);}});}

    private void render(JSONArray arr){if(arr==null)return;messages.removeAllViews();refs.clear();lastId=0;for(int i=0;i<arr.length();i++){JSONObject m=arr.optJSONObject(i);if(m==null)continue;int id=m.optInt("id");lastId=Math.max(lastId,id);boolean mine=m.optBoolean("mine");boolean mention=m.optBoolean("mentions_me");String user=m.optString("display_name",m.optString("username","Driver"));String username=m.optString("username","");String text=m.optString("message","");String time=m.optString("created_at","");
            LinearLayout row=new LinearLayout(this);row.setGravity(mine?Gravity.END:Gravity.START);row.setPadding(0,dp(3),0,dp(3));LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(12),dp(9),dp(12),dp(8));card.setBackground(round(mention?"#FFF4C7":(mine?"#DCEEFF":"#FFFFFF"),17));
            TextView name=t((mention?"@  ":"")+user+(username.isEmpty()?"":"  @"+username),11,mention?"#A66300":"#0B7CFF",true);name.setOnClickListener(v->{String mentionText="@"+username+" ";input.getText().insert(input.getSelectionStart(),mentionText);input.requestFocus();});card.addView(name);
            TextView body=tSpan(text,14,"#14263A");card.addView(body);TextView ts=t(shortTime(time),9,"#8493A4",false);ts.setGravity(Gravity.END);card.addView(ts);row.addView(card,new LinearLayout.LayoutParams(-2,-2));messages.addView(row,new LinearLayout.LayoutParams(-1,-2));refs.add(new MessageRef(id,mention,row));}
        scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN)); }

    private void send(){String text=input.getText().toString().trim();if(text.isEmpty())return;if(text.length()>500){Toast.makeText(this,"Maksimal 500 karakter",Toast.LENGTH_SHORT).show();return;}input.setEnabled(false);DriverGlobalChatApi.send(this,text,new DriverGlobalChatApi.Callback(){public void onResult(JSONObject j){input.setText("");input.setEnabled(true);load(false);}public void onError(String m){input.setEnabled(true);Toast.makeText(DriverGlobalChatActivity.this,m,Toast.LENGTH_SHORT).show();}});}
    private void jumpNextMention(){for(MessageRef r:refs){if(r.mention){jumpTo(r.id,true);return;}}Toast.makeText(this,"Belum ada mention dalam 24 jam terakhir",Toast.LENGTH_SHORT).show();}
    private void jumpTo(long id,boolean mark){for(MessageRef r:refs){if(r.id==id){scroll.post(()->{scroll.smoothScrollTo(0,Math.max(0,r.view.getTop()-dp(100)));r.view.animate().alpha(0.45f).setDuration(160).withEndAction(()->r.view.animate().alpha(1f).setDuration(260).start()).start();});if(mark)DriverGlobalChatApi.readMention(this,id,new DriverGlobalChatApi.Callback(){public void onResult(JSONObject j){load(false);}public void onError(String m){}});return;}}}

    private TextView t(String s,int size,String color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(Color.parseColor(color));if(bold)v.setTypeface(Typeface.DEFAULT_BOLD);return v;}
    private TextView tSpan(String s,int size,String color){TextView v=t(s,size,color,false);SpannableString sp=new SpannableString(s);String lower=s.toLowerCase(Locale.ROOT);String me="@"+(myUsername==null?"":myUsername.toLowerCase(Locale.ROOT));int p=me.length()>1?lower.indexOf(me):-1;if(p>=0)sp.setSpan(new ForegroundColorSpan(Color.parseColor("#0B7CFF")),p,p+me.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);v.setText(sp);return v;}
    private GradientDrawable round(String c,int radius){GradientDrawable g=new GradientDrawable();g.setColor(Color.parseColor(c));g.setCornerRadius(dp(radius));return g;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private String shortTime(String raw){try{Date d=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).parse(raw);return new SimpleDateFormat("HH:mm",Locale.getDefault()).format(d);}catch(Exception e){return raw;}}
    private static class MessageRef{final long id;final boolean mention;final View view;MessageRef(long i,boolean m,View v){id=i;mention=m;view=v;}}
}
