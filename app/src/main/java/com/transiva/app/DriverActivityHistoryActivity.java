package com.transiva.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.transiva.app.driver.ui.DriverBottomNavigation;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.Locale;

public class DriverActivityHistoryActivity extends Activity {
    private static final String URL = "https://transiva.my.id/server/driver_activity_history.php";
    private SessionManager session;
    private LinearLayout listBox;
    private TextView runningCount, finishedCount, canceledCount, stateText;
    private TextView todayEarning, todayTrips, rating, onlineTime, todayDistance;
    private ProgressBar progress;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#0B7CFF"));
        getWindow().setNavigationBarColor(Color.parseColor("#071426"));
        session = new SessionManager(this);
        if (!validDriverSession()) { redirectLogin(); return; }
        setContentView(buildScreen());
        DriverAppSettings.apply(this);
        loadActivities();
    }

    @Override protected void onResume() {
        super.onResume();
        if (listBox != null) loadActivities();
    }

    private boolean validDriverSession() {
        return session != null && session.isLoggedIn()
                && "driver".equals(session.normalizeRole(session.getRole()))
                && !clean(session.getToken()).isEmpty();
    }

    private void redirectLogin() {
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(i); finish();
    }

    private View buildScreen() {
        FrameLayout page = new FrameLayout(this); page.setBackgroundColor(Color.parseColor("#F6F9FE"));
        LinearLayout shell = new LinearLayout(this); shell.setOrientation(LinearLayout.VERTICAL); page.addView(shell, new FrameLayout.LayoutParams(-1,-1));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(14),dp(14),dp(14),dp(24)); scroll.addView(content,new ScrollView.LayoutParams(-1,-2));
        content.addView(header("Aktivitas", "Statistik hari ini dan riwayat perjalanan"));

        LinearLayout dailyTop = new LinearLayout(this); dailyTop.setOrientation(LinearLayout.HORIZONTAL);
        todayEarning = statCard("Rp0", "Hari ini"); todayTrips = statCard("0", "Trip"); rating = statCard("0.0", "Rating");
        dailyTop.addView(statContainer(todayEarning), statLp(false)); dailyTop.addView(statContainer(todayTrips), statLp(true)); dailyTop.addView(statContainer(rating), statLp(true));
        LinearLayout.LayoutParams dtp = new LinearLayout.LayoutParams(-1,-2); dtp.setMargins(0,dp(14),0,dp(8)); content.addView(dailyTop,dtp);

        LinearLayout dailyBottom = new LinearLayout(this); dailyBottom.setOrientation(LinearLayout.HORIZONTAL);
        onlineTime = statCard("0 mnt", "Waktu Online"); todayDistance = statCard("0.0 km", "Jarak Hari Ini");
        dailyBottom.addView(statContainer(onlineTime), statLp(false)); dailyBottom.addView(statContainer(todayDistance), statLp(true));
        LinearLayout.LayoutParams dbp = new LinearLayout.LayoutParams(-1,-2); dbp.setMargins(0,0,0,dp(14)); content.addView(dailyBottom,dbp);

        LinearLayout summary = new LinearLayout(this); summary.setOrientation(LinearLayout.HORIZONTAL);
        runningCount = statCard("0","Berjalan"); finishedCount = statCard("0","Selesai"); canceledCount = statCard("0","Dibatalkan");
        summary.addView(statContainer(runningCount), statLp(false));
        summary.addView(statContainer(finishedCount), statLp(true));
        summary.addView(statContainer(canceledCount), statLp(true));
        LinearLayout.LayoutParams slp=new LinearLayout.LayoutParams(-1,-2); slp.setMargins(0,dp(14),0,dp(14)); content.addView(summary,slp);

        LinearLayout titleCard=card(); titleCard.addView(text("Aktivitas Terbaru",15,"#0B3A78",true));
        stateText=text("Menghubungkan ke database…",11,"#64748B",false); titleCard.addView(stateText); content.addView(titleCard,sectionLp());
        progress=new ProgressBar(this); content.addView(progress,new LinearLayout.LayoutParams(-1,dp(42)));
        listBox=new LinearLayout(this); listBox.setOrientation(LinearLayout.VERTICAL); content.addView(listBox);
        shell.addView(DriverBottomNavigation.build(this, DriverBottomNavigation.ActiveItem.ACTIVITY),new LinearLayout.LayoutParams(-1,dp(62)));
        return page;
    }

    private TextView statCard(String value,String label){
        LinearLayout c=card(); c.setGravity(Gravity.CENTER); c.setPadding(dp(7),dp(12),dp(7),dp(12));
        TextView n=text(value,20,"#0B7CFF",true); n.setGravity(Gravity.CENTER); c.addView(n);
        TextView l=text(label,10,"#64748B",false); l.setGravity(Gravity.CENTER); c.addView(l);
        n.setTag(c); return n;
    }
    private View statContainer(TextView v){ return (View)v.getTag(); }

    private void loadActivities(){
        if(progress==null) return; progress.setVisibility(View.VISIBLE); stateText.setText("Menyinkronkan aktivitas…");
        DriverNetworkExecutor.execute(() -> {
            try{
                String username=clean(session.getUsername());
                String endpoint=URL+"?driver="+ URLEncoder.encode(username, StandardCharsets.UTF_8.name())+"&_="+System.currentTimeMillis();
                JSONObject r=DriverMessageApi.get(session,endpoint);
                runOnUiThread(() -> render(r));
            }catch(Exception e){ runOnUiThread(() -> { progress.setVisibility(View.GONE); stateText.setText("Gagal terhubung ke database"); Toast.makeText(this,"Aktivitas gagal dimuat",Toast.LENGTH_SHORT).show(); }); }
        });
    }

    private void render(JSONObject r){
        progress.setVisibility(View.GONE); listBox.removeAllViews();
        if(!r.optBoolean("success",false)){ stateText.setText(clean(r.optString("message","Gagal memuat aktivitas"))); listBox.addView(empty("Data aktivitas belum tersedia.")); return; }
        JSONObject perf=r.optJSONObject("performance"); if(perf==null)perf=new JSONObject();
        todayEarning.setText(rupiah(perf.optDouble("today_earning",0)));
        todayTrips.setText(String.valueOf(perf.optInt("today_trips",0)));
        rating.setText(String.format(Locale.US,"%.1f",perf.optDouble("rating",0)));
        onlineTime.setText(formatMinutes(perf.optInt("online_minutes",0)));
        todayDistance.setText(String.format(Locale.US,"%.1f km",perf.optDouble("today_distance_km",0)));
        JSONObject s=r.optJSONObject("summary"); if(s==null)s=new JSONObject();
        runningCount.setText(String.valueOf(s.optInt("running",0))); finishedCount.setText(String.valueOf(s.optInt("finished",0))); canceledCount.setText(String.valueOf(s.optInt("canceled",0)));
        JSONArray a=r.optJSONArray("activities"); int count=a==null?0:a.length(); stateText.setText(count+" aktivitas ditemukan");
        if(count==0){ listBox.addView(empty("Belum ada riwayat aktivitas driver.")); return; }
        for(int i=0;i<count;i++){ JSONObject o=a.optJSONObject(i); if(o!=null) listBox.addView(activityCard(o),sectionLp()); }
    }

    private View activityCard(JSONObject o){
        LinearLayout c=card();
        LinearLayout top=new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL); top.setOrientation(LinearLayout.HORIZONTAL);
        TextView service=text(first(o.optString("service_name"),o.optString("order_type"),"Order"),15,"#0B3A78",true); top.addView(service,new LinearLayout.LayoutParams(0,-2,1));
        String status=clean(o.optString("status"));
        String activityKind=clean(o.optString("activity_kind"));
        String visualStatus=activityKind.isEmpty()?status:activityKind;
        TextView badge=text(statusLabel(visualStatus),10,statusColor(visualStatus),true); badge.setPadding(dp(9),dp(5),dp(9),dp(5)); badge.setBackground(round("#EEF6FF",14)); top.addView(badge); c.addView(top);
        c.addView(text("Order #"+first(o.optString("order_id"),o.optString("id"),"-"),11,"#64748B",false));
        c.addView(text("Dari: "+first(o.optString("pickup_address"),"-"),12,"#334155",false));
        c.addView(text("Tujuan: "+first(o.optString("destination_address"),o.optString("delivery_address"),"-"),12,"#334155",false));
        double price=o.optDouble("driver_earning",o.optDouble("price",0));
        String time=first(o.optString("activity_time"),o.optString("updated_at"),o.optString("created_at"),"");
        c.addView(text((price>0?rupiah(price)+"  •  ":"")+time,11,"#0B7CFF",true));
        return c;
    }

    private View empty(String message){ LinearLayout c=card(); c.setGravity(Gravity.CENTER); TextView t=text(message,12,"#718096",false); t.setGravity(Gravity.CENTER); c.addView(t); return c; }
    private LinearLayout header(String title,String sub){ LinearLayout b=card(); b.setBackground(gradient("#086BFF","#2EA2FF",22)); b.addView(text(title,24,"#FFFFFF",true)); b.addView(text(sub,11,"#EAF4FF",false)); return b; }
    private LinearLayout card(){ LinearLayout v=new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setPadding(dp(14),dp(13),dp(14),dp(13)); v.setBackground(round("#FFFFFF",18)); v.setElevation(dp(2)); return v; }
    private LinearLayout.LayoutParams sectionLp(){ LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.setMargins(0,0,0,dp(10)); return p; }
    private LinearLayout.LayoutParams statLp(boolean margin){ LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1); if(margin)p.setMargins(dp(8),0,0,0); return p; }
    private TextView text(String s,int size,String color,boolean bold){ TextView t=new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(Color.parseColor(color)); t.setTypeface(Typeface.DEFAULT,bold?Typeface.BOLD:Typeface.NORMAL); t.setPadding(0,dp(2),0,dp(2)); return t; }
    private GradientDrawable round(String color,int radius){ GradientDrawable g=new GradientDrawable(); g.setColor(Color.parseColor(color)); g.setCornerRadius(dp(radius)); return g; }
    private GradientDrawable gradient(String a,String b,int radius){ GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{Color.parseColor(a),Color.parseColor(b)}); g.setCornerRadius(dp(radius)); return g; }
    private String statusLabel(String s){ s=s.toLowerCase(Locale.US); if(s.contains("cancel"))return "Dibatalkan"; if(s.contains("finish")||s.contains("complete")||s.equals("done")||s.equals("delivered"))return "Selesai"; return "Berjalan"; }
    private String statusColor(String s){ s=s.toLowerCase(Locale.US); if(s.contains("cancel"))return "#DC2626"; if(s.contains("finish")||s.contains("complete")||s.equals("done")||s.equals("delivered"))return "#16A34A"; return "#0B7CFF"; }
    private String formatMinutes(int m){ if(m<60)return m+" mnt"; return (m/60)+"j "+(m%60)+"m"; }
    private String rupiah(double v){ return NumberFormat.getCurrencyInstance(new Locale("id","ID")).format(v).replace(",00",""); }
    private String first(String...v){ for(String s:v)if(!clean(s).isEmpty())return clean(s); return ""; }
    private String clean(String v){ return v==null?"":v.trim(); }
    private int dp(int v){ return Math.round(v*getResources().getDisplayMetrics().density); }
}
