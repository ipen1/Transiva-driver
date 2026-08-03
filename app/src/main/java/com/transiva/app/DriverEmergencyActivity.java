package com.transiva.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class DriverEmergencyActivity extends Activity {
    @Override protected void onCreate(Bundle b){ super.onCreate(b); getWindow().setStatusBarColor(Color.parseColor("#B91C1C")); setContentView(screen()); }
    private android.view.View screen(){
        Intent i=getIntent(); String name=first(i.getStringExtra("driver_name"),"Driver"); String user=first(i.getStringExtra("driver_username"),"");
        String lat=first(i.getStringExtra("latitude"),"0"), lng=first(i.getStringExtra("longitude"),"0");
        String order=first(i.getStringExtra("order_id"),"Tidak ada"); String pickup=first(i.getStringExtra("pickup_address"),"-"); String dest=first(i.getStringExtra("destination_address"),"-");
        ScrollView scroll=new ScrollView(this); LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(18),dp(22),dp(18),dp(24)); root.setBackgroundColor(Color.parseColor("#FFF7F7")); scroll.addView(root);
        TextView icon=t("🆘",44,"#B91C1C",true); icon.setGravity(Gravity.CENTER); root.addView(icon);
        TextView title=t(name+" membutuhkan bantuan darurat",23,"#991B1B",true); title.setGravity(Gravity.CENTER); root.addView(title);
        TextView sub=t(user,12,"#64748B",false); sub.setGravity(Gravity.CENTER); root.addView(sub);
        LinearLayout card=card(); card.addView(t("Lokasi terakhir",14,"#991B1B",true)); card.addView(t(lat+", "+lng,16,"#111827",true)); card.addView(t("Order aktif: #"+order,14,"#0B3A78",true)); card.addView(t("Jemput: "+pickup,13,"#334155",false)); card.addView(t("Tujuan: "+dest,13,"#334155",false)); LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2); cp.setMargins(0,dp(20),0,dp(14)); root.addView(card,cp);
        Button map=new Button(this); map.setText("BUKA LOKASI DI MAPS"); map.setTextColor(Color.WHITE); map.setTypeface(Typeface.DEFAULT,Typeface.BOLD); map.setBackground(round("#DC2626",16)); map.setOnClickListener(v->{ try{ startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:"+lat+","+lng+"?q="+lat+","+lng+"("+Uri.encode(name)+")"))); }catch(Exception ignored){} }); root.addView(map,new LinearLayout.LayoutParams(-1,dp(56)));
        TextView warning=t("Segera hubungi driver atau admin. Utamakan keselamatan dan jangan datang sendiri bila situasi berbahaya.",12,"#7F1D1D",true); warning.setGravity(Gravity.CENTER); LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(-1,-2); wp.setMargins(0,dp(16),0,0); root.addView(warning,wp);
        return scroll;
    }
    private LinearLayout card(){ LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(dp(16),dp(16),dp(16),dp(16)); l.setBackground(round("#FFFFFF",18)); l.setElevation(dp(3)); return l; }
    private TextView t(String s,int z,String c,boolean b){ TextView v=new TextView(this);v.setText(s);v.setTextSize(z);v.setTextColor(Color.parseColor(c));v.setTypeface(Typeface.DEFAULT,b?Typeface.BOLD:Typeface.NORMAL);v.setPadding(0,dp(4),0,dp(4));return v; }
    private GradientDrawable round(String c,int r){ GradientDrawable g=new GradientDrawable();g.setColor(Color.parseColor(c));g.setCornerRadius(dp(r));return g; }
    private String first(String...x){for(String s:x)if(s!=null&&!s.trim().isEmpty())return s.trim();return "";} private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
