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
import java.util.Calendar;
import java.text.SimpleDateFormat;
import java.util.Date;
import android.widget.HorizontalScrollView;

public class DriverActivityHistoryActivity extends Activity {
    private static final String URL = "https://transiva.my.id/server/driver_activity_history.php";
    private SessionManager session;
    private LinearLayout listBox;
    private TextView runningCount, finishedCount, canceledCount, stateText;
    private TextView todayEarning, todayTrips, rating, onlineTime, todayDistance;
    private ProgressBar progress;
    private JSONArray cached = new JSONArray();
    private String statusFilter = "Semua", dateFilter = "Semua tanggal";
    private int visibleCount = 10;
    private boolean loading = false;
    private LinearLayout filterRow, dateRow, extraStats;
    private TextView moreButton;
    private ScrollView mainScroll;

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
        if (listBox != null && !loading) loadActivities();
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
        ScrollView scroll = new ScrollView(this); mainScroll=scroll; scroll.setFillViewport(true); shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(14),dp(14),dp(14),dp(24)); scroll.addView(content,new ScrollView.LayoutParams(-1,-2));
        content.addView(header("Aktivitas", "Statistik hari ini dan riwayat perjalanan"));

        LinearLayout hero=card();hero.setBackground(gradient("#086BFF","#2EA2FF",20));
        hero.addView(text("Pendapatan hari ini",12,"#EAF4FF",false));
        todayEarning=text("Rp0",25,"#FFFFFF",true);hero.addView(todayEarning);
        LinearLayout metrics=new LinearLayout(this);
        todayTrips=metric(metrics,"Trip hari ini");onlineTime=metric(metrics,"Waktu online");todayDistance=metric(metrics,"Jarak hari ini");
        hero.addView(metrics);content.addView(hero,sectionLp());
        LinearLayout stats=card();TextView toggle=text("Statistik lainnya  ▾",13,"#0B3A78",true);stats.addView(toggle);
        extraStats=new LinearLayout(this);extraStats.setOrientation(LinearLayout.VERTICAL);extraStats.setVisibility(View.GONE);
        rating=stat(extraStats,"Rating");runningCount=stat(extraStats,"Total berjalan");
        finishedCount=stat(extraStats,"Total selesai");canceledCount=stat(extraStats,"Total dibatalkan");
        stats.addView(extraStats);toggle.setOnClickListener(v->{boolean open=extraStats.getVisibility()!=View.VISIBLE;extraStats.setVisibility(open?View.VISIBLE:View.GONE);toggle.setText(open?"Statistik lainnya  ▴":"Statistik lainnya  ▾");});
        content.addView(stats,sectionLp());
        content.addView(text("Riwayat perjalanan",17,"#0B3A78",true));
        filterRow=new LinearLayout(this);dateRow=new LinearLayout(this);
        HorizontalScrollView fs=new HorizontalScrollView(this);fs.setHorizontalScrollBarEnabled(false);fs.addView(filterRow);content.addView(fs,sectionLp());
        HorizontalScrollView ds=new HorizontalScrollView(this);ds.setHorizontalScrollBarEnabled(false);ds.addView(dateRow);content.addView(ds,sectionLp());buildFilters();
        stateText=text("Menghubungkan ke database…",11,"#64748B",false);content.addView(stateText,sectionLp());
        progress=new ProgressBar(this);progress.setVisibility(View.GONE);content.addView(progress,new LinearLayout.LayoutParams(-1,dp(24)));
        listBox=new LinearLayout(this);listBox.setOrientation(LinearLayout.VERTICAL);content.addView(listBox);
        moreButton=text("Muat 10 aktivitas berikutnya  ↓",13,"#0B7CFF",true);moreButton.setGravity(Gravity.CENTER);moreButton.setPadding(dp(8),dp(12),dp(8),dp(12));moreButton.setVisibility(View.GONE);
        moreButton.setOnClickListener(v->{visibleCount+=10;renderList();});content.addView(moreButton,sectionLp());
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

    private TextView metric(LinearLayout row,String label){
        LinearLayout cell=new LinearLayout(this);cell.setOrientation(LinearLayout.VERTICAL);
        TextView value=text("0",15,"#FFFFFF",true);cell.addView(value);cell.addView(text(label,10,"#EAF4FF",false));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.topMargin=dp(7);row.addView(cell,lp);return value;
    }
    private TextView stat(LinearLayout parent,String label){
        LinearLayout row=new LinearLayout(this);TextView title=text(label,12,"#64748B",false);
        row.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        TextView value=text("0",13,"#0B7CFF",true);row.addView(value);parent.addView(row);return value;
    }
    private TextView chip(String label,boolean selected){
        TextView t=text(label,12,selected?"#FFFFFF":"#0B3A78",selected);
        t.setPadding(dp(12),dp(8),dp(12),dp(8));t.setBackground(round(selected?"#0B7CFF":"#EAF2FD",14));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.setMargins(0,0,dp(7),0);t.setLayoutParams(lp);return t;
    }
    private void buildFilters(){
        filterRow.removeAllViews();dateRow.removeAllViews();
        for(String f:new String[]{"Semua","Berjalan","Selesai","Dibatalkan"}){
            TextView t=chip(f,f.equals(statusFilter));t.setOnClickListener(v->{statusFilter=f;visibleCount=10;buildFilters();renderList();});filterRow.addView(t);
        }
        for(String f:new String[]{"Semua tanggal","Hari ini","7 hari","30 hari","Pilih tanggal"}){
            TextView t=chip(f,f.equals(dateFilter));t.setOnClickListener(v->{
                if(f.equals("Pilih tanggal")){
                    Calendar now=Calendar.getInstance();
                    new android.app.DatePickerDialog(this,(picker,y,m,d)->{
                        dateFilter=String.format(Locale.US,"%04d-%02d-%02d",y,m+1,d);
                        visibleCount=10;buildFilters();renderList();
                    },now.get(Calendar.YEAR),now.get(Calendar.MONTH),now.get(Calendar.DAY_OF_MONTH)).show();
                }else{dateFilter=f;visibleCount=10;buildFilters();renderList();}
            });dateRow.addView(t);
        }
        if(dateFilter.matches("\\d{4}-\\d{2}-\\d{2}")){
            TextView t=chip(dateFilter,true);t.setOnClickListener(v->{dateFilter="Semua tanggal";buildFilters();renderList();});dateRow.addView(t);
        }
    }
    private void loadActivities(){
        if(progress==null||loading)return;loading=true;
        if(cached.length()==0)progress.setVisibility(View.VISIBLE);
        DriverNetworkExecutor.execute(()->{
            try{
                String endpoint=URL+"?driver="+URLEncoder.encode(clean(session.getUsername()),StandardCharsets.UTF_8.name())+"&_="+System.currentTimeMillis();
                JSONObject result=DriverMessageApi.get(session,endpoint);
                runOnUiThread(()->{loading=false;render(result);});
            }catch(Exception e){runOnUiThread(()->{
                loading=false;progress.setVisibility(View.GONE);
                stateText.setText(cached.length()>0?"Data terakhir • sinkronisasi gagal":"Gagal terhubung ke database");
                if(cached.length()==0)Toast.makeText(this,"Aktivitas gagal dimuat",Toast.LENGTH_SHORT).show();
            });}
        });
    }
    private void render(JSONObject r){
        progress.setVisibility(View.GONE);
        if(!r.optBoolean("success",false)){
            stateText.setText(clean(r.optString("message","Gagal memuat aktivitas")));
            if(cached.length()==0){listBox.removeAllViews();listBox.addView(empty("Data aktivitas belum tersedia."));}return;
        }
        JSONObject perf=r.optJSONObject("performance");if(perf==null)perf=new JSONObject();
        todayEarning.setText(rupiah(perf.optDouble("today_earning",0)));
        todayTrips.setText(String.valueOf(perf.optInt("today_trips",0)));
        rating.setText(String.format(Locale.US,"%.1f",perf.optDouble("rating",0)));
        onlineTime.setText(formatMinutes(perf.optInt("online_minutes",0)));
        todayDistance.setText(String.format(Locale.US,"%.1f km",perf.optDouble("today_distance_km",0)));
        JSONObject summary=r.optJSONObject("summary");if(summary==null)summary=new JSONObject();
        runningCount.setText(String.valueOf(summary.optInt("running",0)));
        finishedCount.setText(String.valueOf(summary.optInt("finished",0)));
        canceledCount.setText(String.valueOf(summary.optInt("canceled",0)));
        JSONArray next=r.optJSONArray("activities");if(next==null)next=new JSONArray();
        if(!next.toString().equals(cached.toString())){cached=next;renderList();}
        else if(listBox.getChildCount()==0)renderList();
    }
    private boolean matches(JSONObject o){
        String status=first(o.optString("activity_kind"),o.optString("status"));
        if(!statusFilter.equals("Semua")&&!statusFilter.equals(statusLabel(status)))return false;
        if(dateFilter.equals("Semua tanggal"))return true;
        String raw=first(o.optString("activity_time"),o.optString("updated_at"),o.optString("created_at"));
        if(raw.length()<10)return false;
        try{
            String day=raw.substring(0,10);
            SimpleDateFormat fmt=new SimpleDateFormat("yyyy-MM-dd",Locale.US);fmt.setLenient(false);
            Date when=fmt.parse(day);if(when==null)return false;
            if(dateFilter.matches("\\d{4}-\\d{2}-\\d{2}"))return day.equals(dateFilter);
            Calendar start=Calendar.getInstance();start.set(Calendar.HOUR_OF_DAY,0);start.set(Calendar.MINUTE,0);start.set(Calendar.SECOND,0);start.set(Calendar.MILLISECOND,0);
            if(dateFilter.equals("7 hari"))start.add(Calendar.DAY_OF_YEAR,-6);
            else if(dateFilter.equals("30 hari"))start.add(Calendar.DAY_OF_YEAR,-29);
            Calendar tomorrow=(Calendar)start.clone();tomorrow.setTimeInMillis(System.currentTimeMillis());tomorrow.set(Calendar.HOUR_OF_DAY,0);tomorrow.set(Calendar.MINUTE,0);tomorrow.set(Calendar.SECOND,0);tomorrow.set(Calendar.MILLISECOND,0);tomorrow.add(Calendar.DAY_OF_YEAR,1);
            return !when.before(start.getTime())&&when.before(tomorrow.getTime());
        }catch(Exception ignored){return false;}
    }
    private void renderList(){
        if(listBox==null)return;int y=mainScroll.getScrollY();listBox.removeAllViews();
        int total=0,shown=0;
        for(int i=0;i<cached.length();i++){JSONObject o=cached.optJSONObject(i);if(o==null||!matches(o))continue;
            total++;if(shown<visibleCount){listBox.addView(activityCard(o),sectionLp());shown++;}}
        stateText.setText(total+" aktivitas ditemukan • "+shown+" ditampilkan");
        if(total==0)listBox.addView(empty("Tidak ada aktivitas untuk filter ini."));
        moreButton.setVisibility(total>shown?View.VISIBLE:View.GONE);
        mainScroll.post(()->mainScroll.scrollTo(0,y));
    }

    private View activityCard(JSONObject o){
        LinearLayout c=card();LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);
        TextView service=text(first(o.optString("service_name"),o.optString("order_type"),"Order"),15,"#0B3A78",true);
        top.addView(service,new LinearLayout.LayoutParams(0,-2,1));
        String status=first(o.optString("activity_kind"),o.optString("status"));
        TextView badge=text(statusLabel(status),10,statusColor(status),true);badge.setPadding(dp(9),dp(5),dp(9),dp(5));badge.setBackground(round("#EEF6FF",14));top.addView(badge);c.addView(top);
        double price=o.optDouble("driver_earning",o.optDouble("price",0));
        String time=first(o.optString("activity_time"),o.optString("updated_at"),o.optString("created_at"),"");
        c.addView(text((price>0?rupiah(price)+"  •  ":"")+time,12,"#0B7CFF",true));
        c.addView(text("Dari: "+first(o.optString("pickup_address"),"-"),12,"#334155",false));
        c.addView(text("Tujuan: "+first(o.optString("destination_address"),o.optString("delivery_address"),"-"),12,"#334155",false));
        LinearLayout detail=new LinearLayout(this);detail.setOrientation(LinearLayout.VERTICAL);detail.setVisibility(View.GONE);
        detail.addView(text("Order #"+first(o.optString("order_id"),o.optString("id"),"-"),11,"#64748B",false));
        String note=customerNote(o);if(!note.isEmpty())detail.addView(text("📝 Catatan customer: "+note,12,"#7C2D12",true));
        detail.addView(text("Status: "+statusLabel(status)+" • "+time,11,"#64748B",false));c.addView(detail);
        TextView toggle=text("Lihat detail  ▾",12,"#0B7CFF",true);toggle.setPadding(0,dp(8),0,dp(3));c.addView(toggle);
        toggle.setOnClickListener(v->{boolean open=detail.getVisibility()!=View.VISIBLE;detail.setVisibility(open?View.VISIBLE:View.GONE);toggle.setText(open?"Tutup detail  ▴":"Lihat detail  ▾");});
        return c;
    }

    private View empty(String message){ LinearLayout c=card(); c.setGravity(Gravity.CENTER); TextView t=text(message,12,"#718096",false); t.setGravity(Gravity.CENTER); c.addView(t); return c; }
    private LinearLayout header(String title,String sub){ LinearLayout b=card(); b.setBackground(gradient("#086BFF","#2EA2FF",22)); b.setPadding(dp(14),dp(9),dp(14),dp(9)); b.addView(text(title,20,"#FFFFFF",true)); b.addView(text(sub,11,"#EAF4FF",false)); return b; }
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
    private String customerNote(JSONObject o){
        if(o==null) return "";
        String note=first(
                o.optString("customer_note"),
                o.optString("note_customer"),
                o.optString("special_instructions"),
                o.optString("instructions"),
                o.optString("note")
        );
        if(note.isEmpty()) return "";
        String trimmed=note.trim();
        if(trimmed.startsWith("{")){
            try{
                JSONObject parsed=new JSONObject(trimmed);
                note=first(
                        parsed.optString("text"),
                        parsed.optString("note"),
                        parsed.optString("customer_note"),
                        parsed.optString("special_instructions"),
                        parsed.optString("instructions"),
                        parsed.optString("message"),
                        parsed.optString("remark")
                );
            }catch(Exception ignored){ return ""; }
        }
        if(note.startsWith("[")) return "";
        return clean(note);
    }
    private String first(String...v){ for(String s:v)if(!clean(s).isEmpty())return clean(s); return ""; }
    private String clean(String v){ return v==null?"":v.trim(); }
    private int dp(int v){ return Math.round(v*getResources().getDisplayMetrics().density); }
}
