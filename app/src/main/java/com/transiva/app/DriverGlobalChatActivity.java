package com.transiva.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
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
    private LinearLayout messages,suggestions; private ScrollView scroll; private EditText input; private TextView mentionPill,status;
    private ProgressBar loading; private boolean busy=false; private long jumpId=0; private String myUsername="";
    private int suggestSeq=0;
    private final Runnable refresh=new Runnable(){public void run(){if(!isFinishing()){load(false);main.postDelayed(this,DriverPollingCoordinator.interval(DriverGlobalChatActivity.this,12000L));}}};

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.parseColor("#071426")); getWindow().setNavigationBarColor(Color.parseColor("#071426"));
        myUsername=new SessionManager(this).getUsername(); jumpId=getIntent().getLongExtra("jump_message_id",0L);
        setContentView(build()); DriverGlobalChatBubble.detach(this); DriverAppSettings.apply(this); load(true); main.postDelayed(refresh,DriverPollingCoordinator.interval(this,12000L));
    }
    @Override protected void onResume(){super.onResume();DriverGlobalChatBubble.detach(this);}
    @Override protected void onNewIntent(android.content.Intent intent){super.onNewIntent(intent);setIntent(intent);jumpId=intent.getLongExtra("jump_message_id",0L);load(false);}
    @Override protected void onDestroy(){main.removeCallbacks(refresh);super.onDestroy();}
    @Override public void finish(){super.finish();overridePendingTransition(R.anim.global_chat_hold,R.anim.global_chat_exit_to_left);}

    private View build(){
        FrameLayout page=new FrameLayout(this); page.setBackgroundColor(Color.parseColor("#F4F7FB"));
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(12),dp(10),dp(12),dp(10));page.addView(root,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);head.setPadding(dp(10),dp(9),dp(10),dp(9));head.setBackground(round("#FFFFFF",18));
        TextView back=t("‹",34,"#0B3A78",true);back.setGravity(Gravity.CENTER);back.setOnClickListener(v->finish());head.addView(back,new LinearLayout.LayoutParams(dp(46),dp(46)));
        LinearLayout titles=new LinearLayout(this);titles.setOrientation(LinearLayout.VERTICAL);titles.setPadding(dp(8),0,0,0);TextView title=t("Driver Lounge",19,"#0B3A78",true);titles.addView(title);status=t("Chat global • pesan hilang otomatis setelah 24 jam",10,"#6F7E90",false);titles.addView(status);head.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        mentionPill=t("@ Saya",12,"#0B7CFF",true);mentionPill.setPadding(dp(12),dp(8),dp(12),dp(8));mentionPill.setBackground(round("#EAF4FF",14));mentionPill.setOnClickListener(v->jumpNextMention());head.addView(mentionPill);root.addView(head,new LinearLayout.LayoutParams(-1,-2));
        loading=new ProgressBar(this);LinearLayout.LayoutParams plp=new LinearLayout.LayoutParams(dp(24),dp(24));plp.gravity=Gravity.CENTER_HORIZONTAL;plp.topMargin=dp(8);root.addView(loading,plp);
        scroll=new ScrollView(this);scroll.setFillViewport(true);messages=new LinearLayout(this);messages.setOrientation(LinearLayout.VERTICAL);messages.setPadding(dp(4),dp(8),dp(4),dp(12));scroll.addView(messages,new ScrollView.LayoutParams(-1,-2));root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));

        suggestions=new LinearLayout(this); suggestions.setOrientation(LinearLayout.VERTICAL); suggestions.setVisibility(View.GONE); suggestions.setPadding(dp(8),dp(5),dp(8),dp(5)); suggestions.setBackground(round("#FFFFFF",16));
        LinearLayout.LayoutParams slp=new LinearLayout.LayoutParams(-1,-2);slp.setMargins(dp(2),0,dp(2),dp(6));root.addView(suggestions,slp);

        LinearLayout composer=new LinearLayout(this);composer.setGravity(Gravity.BOTTOM|Gravity.CENTER_VERTICAL);composer.setPadding(dp(8),dp(7),dp(7),dp(7));composer.setBackground(round("#FFFFFF",20));
        TextView at=t("@",20,"#0B7CFF",true);at.setGravity(Gravity.CENTER);at.setBackground(round("#EAF4FF",15));at.setOnClickListener(v->{int p=input.getSelectionStart();input.getText().insert(Math.max(0,p),"@");input.requestFocus();showSuggestions(mentionPrefix());});composer.addView(at,new LinearLayout.LayoutParams(dp(42),dp(42)));
        input=new EditText(this);input.setHint("Ngobrol dengan driver lain…");input.setTextSize(14);input.setTextColor(Color.parseColor("#14263A"));input.setHintTextColor(Color.parseColor("#93A0AE"));input.setBackgroundColor(Color.TRANSPARENT);input.setSingleLine(false);input.setMaxLines(4);input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v,a,e)->{if(a==EditorInfo.IME_ACTION_SEND){send();return true;}return false;});
        input.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int b,int c){main.removeCallbacks(suggestRunnable);main.postDelayed(suggestRunnable,160);}public void afterTextChanged(Editable e){}});
        composer.addView(input,new LinearLayout.LayoutParams(0,-2,1));
        Button send=new Button(this);send.setText("Kirim");send.setTextColor(Color.WHITE);send.setTextSize(12);send.setTypeface(Typeface.DEFAULT_BOLD);send.setAllCaps(false);send.setBackground(round("#0B7CFF",16));send.setOnClickListener(v->send());composer.addView(send,new LinearLayout.LayoutParams(dp(76),dp(46)));root.addView(composer,new LinearLayout.LayoutParams(-1,-2));
        return page;
    }

    private final Runnable suggestRunnable=()->showSuggestions(mentionPrefix());
    private String mentionPrefix(){
        if(input==null)return null; int cursor=input.getSelectionStart(); if(cursor<0)cursor=input.length(); String s=input.getText().toString(); cursor=Math.min(cursor,s.length());
        int at=s.lastIndexOf('@',Math.max(0,cursor-1)); if(at<0)return null; if(at>0 && !Character.isWhitespace(s.charAt(at-1)))return null;
        String token=s.substring(at+1,cursor); if(token.indexOf(' ')>=0 || token.indexOf('\n')>=0)return null; return token;
    }
    private void showSuggestions(String prefix){
        if(prefix==null){suggestions.setVisibility(View.GONE);return;} final int seq=++suggestSeq;
        DriverGlobalChatApi.suggestUsers(this,prefix,new DriverGlobalChatApi.Callback(){public void onResult(JSONObject json){if(seq!=suggestSeq)return;JSONObject d=json.optJSONObject("data");JSONArray a=d!=null?d.optJSONArray("users"):json.optJSONArray("users");renderSuggestions(a);}public void onError(String m){if(seq==suggestSeq)suggestions.setVisibility(View.GONE);}});
    }
    private void renderSuggestions(JSONArray arr){
        suggestions.removeAllViews(); if(arr==null||arr.length()==0){suggestions.setVisibility(View.GONE);return;} int n=Math.min(3,arr.length());
        for(int i=0;i<n;i++){JSONObject u=arr.optJSONObject(i);if(u==null)continue;String username=u.optString("username","");String name=u.optString("display_name",username);if(username.isEmpty())continue;
            TextView row=t("@"+username+(name.equalsIgnoreCase(username)?"":"  •  "+name),13,"#12385F",true);row.setPadding(dp(12),dp(10),dp(12),dp(10));row.setBackground(round(i==0?"#F2F7FF":"#FFFFFF",12));row.setOnClickListener(v->insertMention(username));suggestions.addView(row,new LinearLayout.LayoutParams(-1,-2));}
        suggestions.setVisibility(suggestions.getChildCount()>0?View.VISIBLE:View.GONE);
    }
    private void insertMention(String username){
        int cursor=input.getSelectionStart();String s=input.getText().toString();int at=s.lastIndexOf('@',Math.max(0,cursor-1));if(at<0){input.getText().insert(Math.max(0,cursor),"@"+username+" ");}else{input.getText().replace(at,Math.max(at,cursor),"@"+username+" ");}suggestions.setVisibility(View.GONE);input.requestFocus();input.setSelection(input.length());
    }

    private void load(boolean initial){if(busy)return;busy=true;if(initial)loading.setVisibility(View.VISIBLE);DriverGlobalChatApi.get(this,0,new DriverGlobalChatApi.Callback(){public void onResult(JSONObject json){busy=false;loading.setVisibility(View.GONE);JSONObject data=json.optJSONObject("data");if(data==null)data=json;render(data.optJSONArray("messages"));int unread=data.optInt("unread_mentions",0);DriverGlobalChatStore.setUnreadMentions(DriverGlobalChatActivity.this,unread);mentionPill.setText(unread>0?"@ Saya  "+unread:"@ Saya");if(jumpId>0){jumpTo(jumpId,true);jumpId=0;}}public void onError(String m){busy=false;loading.setVisibility(View.GONE);status.setText(m);}});}

    private void render(JSONArray arr){if(arr==null)return;messages.removeAllViews();refs.clear();for(int i=0;i<arr.length();i++){JSONObject m=arr.optJSONObject(i);if(m==null)continue;int id=m.optInt("id");boolean mine=m.optBoolean("mine");boolean mention=m.optBoolean("mentions_me");String user=m.optString("display_name",m.optString("username","Driver"));String username=m.optString("username","");String text=m.optString("message","");String time=m.optString("created_at","");
            LinearLayout row=new LinearLayout(this);row.setGravity(mine?Gravity.END:Gravity.START);row.setPadding(0,dp(4),0,dp(4));
            LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(13),dp(10),dp(13),dp(9));card.setBackground(round(mention?"#FFF5D6":(mine?"#DCEEFF":"#FFFFFF"),17));
            TextView name=t(shortDisplayName(user),12,mention?"#A66300":"#0B5FB8",true);name.setSingleLine(true);name.setMaxEms(10);name.setEllipsize(android.text.TextUtils.TruncateAt.END);name.setMinEms(10);name.setOnClickListener(v->{if(!username.isEmpty())insertMention(username);});card.addView(name);
            TextView body=tSpan(text,14,"#14263A");LinearLayout.LayoutParams blp=new LinearLayout.LayoutParams(-2,-2);blp.topMargin=dp(3);card.addView(body,blp);
            TextView ts=t(shortTime(time),10,"#8493A4",false);LinearLayout.LayoutParams tlp=new LinearLayout.LayoutParams(-2,-2);tlp.topMargin=dp(4);card.addView(ts,tlp);
            row.addView(card,new LinearLayout.LayoutParams(-2,-2));messages.addView(row,new LinearLayout.LayoutParams(-1,-2));refs.add(new MessageRef(id,mention,row));}
        scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN)); }

    private void send(){String text=input.getText().toString().trim();if(text.isEmpty())return;if(text.length()>500){Toast.makeText(this,"Maksimal 500 karakter",Toast.LENGTH_SHORT).show();return;}suggestions.setVisibility(View.GONE);input.setEnabled(false);DriverGlobalChatApi.send(this,text,new DriverGlobalChatApi.Callback(){public void onResult(JSONObject j){input.setText("");input.setEnabled(true);load(false);}public void onError(String m){input.setEnabled(true);Toast.makeText(DriverGlobalChatActivity.this,m,Toast.LENGTH_SHORT).show();}});}
    private void jumpNextMention(){for(MessageRef r:refs){if(r.mention){jumpTo(r.id,true);return;}}Toast.makeText(this,"Belum ada mention dalam 24 jam terakhir",Toast.LENGTH_SHORT).show();}
    private void jumpTo(long id,boolean mark){for(MessageRef r:refs){if(r.id==id){scroll.post(()->{scroll.smoothScrollTo(0,Math.max(0,r.view.getTop()-dp(100)));r.view.animate().alpha(0.45f).setDuration(160).withEndAction(()->r.view.animate().alpha(1f).setDuration(260).start()).start();});if(mark)DriverGlobalChatApi.readMention(this,id,new DriverGlobalChatApi.Callback(){public void onResult(JSONObject j){load(false);}public void onError(String m){}});return;}}}

    private String shortDisplayName(String raw){String v=raw==null?"":raw.trim();if(v.isEmpty())v="Driver";return v.length()<=10?v:v.substring(0,7)+"...";}
    private TextView t(String s,int size,String color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(Color.parseColor(color));if(bold)v.setTypeface(Typeface.DEFAULT_BOLD);return v;}
    private TextView tSpan(String s,int size,String color){TextView v=t(s,size,color,false);SpannableString sp=new SpannableString(s);String lower=s.toLowerCase(Locale.ROOT);String me="@"+(myUsername==null?"":myUsername.toLowerCase(Locale.ROOT));int p=me.length()>1?lower.indexOf(me):-1;if(p>=0)sp.setSpan(new ForegroundColorSpan(Color.parseColor("#0B7CFF")),p,p+me.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);v.setText(sp);return v;}
    private GradientDrawable round(String c,int radius){GradientDrawable g=new GradientDrawable();g.setColor(Color.parseColor(c));g.setCornerRadius(dp(radius));return g;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private String shortTime(String raw){try{Date d=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).parse(raw);return new SimpleDateFormat("HH:mm",Locale.getDefault()).format(d);}catch(Exception e){return raw;}}
    private static class MessageRef{final long id;final boolean mention;final View view;MessageRef(long i,boolean m,View v){id=i;mention=m;view=v;}}
}
