package com.transiva.app;
import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.*;
import java.util.List;
import java.util.Locale;

/** Route controls remain separate from order status/actions. */
final class NavigationOptionsController {
    interface Listener{void profile(String value);void voice(boolean enabled);void alternatives();}
    private final Activity activity;private final Listener listener;
    private final TextView profile,sound,choices;
    private boolean enabled=true,ready,backgroundFailed,warned;
    NavigationOptionsController(Activity a,FrameLayout root,Listener l){
        activity=a;listener=l;LinearLayout row=new LinearLayout(a);row.setOrientation(LinearLayout.HORIZONTAL);row.setPadding(0,dp(a,8),0,0);
        profile=button(a,"Profil");sound=button(a,"Suara aktif");choices=button(a,"Pilihan rute");
        for(TextView b:new TextView[]{profile,sound,choices}){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMargins(dp(a,3),0,dp(a,3),0);row.addView(b,lp);}
        android.view.View header=root.findViewWithTag("navigation_header");
        if(header instanceof LinearLayout)((LinearLayout)header).addView(row,new LinearLayout.LayoutParams(-1,-2));
        else root.addView(row,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
        profile.setOnClickListener(v->new AlertDialog.Builder(a).setTitle("Profil rute kendaraan").setMessage("Pilih kendaraan Anda. Rute mengutamakan jalan nyaman sesuai data peta. Jika rute motor belum tersedia, gunakan Google Maps.").setPositiveButton("Motor",(d,w)->listener.profile("motor")).setNeutralButton("Mobil",(d,w)->listener.profile("car")).setNegativeButton("Batal",null).show());
        sound.setOnClickListener(v->{enabled=!enabled;updateVoice();listener.voice(enabled);});choices.setOnClickListener(v->listener.alternatives());
    }
    void profile(String requested,String used){profile.setText("motor".equals(requested)?("motor".equals(used)?"Motor":"Motor\n(rute mobil)"):"Mobil");}
    void voice(boolean value){enabled=value;updateVoice();}
    void voiceReady(boolean value){ready=value;updateVoice();}
    void guidanceFailure(boolean value){
        if(backgroundFailed==value)return;backgroundFailed=value;updateVoice();
        if(value&&!warned){warned=true;Toast.makeText(activity,"Panduan background belum aktif. Tetap buka navigasi sampai pulih.",Toast.LENGTH_LONG).show();}
        if(!value)warned=false;
    }
    private void updateVoice(){
        String label=enabled?(ready?"Suara aktif":"Suara belum siap"):"Suara mati";
        sound.setMaxLines(backgroundFailed?3:2);
        sound.setText(label+(backgroundFailed?"\nLatar nonaktif":""));
        sound.setContentDescription(label+(backgroundFailed?". Panduan background belum aktif, pertahankan halaman navigasi terbuka.":""));
    }
    void choices(List<StableRouteEngine.Result> routes,int selected,java.util.function.IntConsumer pick){
        if(routes.size()<2){Toast.makeText(activity,"Provider belum menyediakan alternatif untuk titik ini.",Toast.LENGTH_LONG).show();return;}
        String[] labels=new String[routes.size()];for(int i=0;i<labels.length;i++){StableRouteEngine.Result r=routes.get(i);labels[i]=String.format(Locale.US,"Rute %d%s • %.1f km • ±%d menit%s",i+1,i==selected?" (aktif)":"",r.distanceMeters/1000,Math.max(1,(int)Math.ceil(r.durationSeconds/60)),r.summary.isEmpty()?"":"\n"+r.summary);}
        new AlertDialog.Builder(activity).setTitle("Pilih rute perjalanan").setSingleChoiceItems(labels,selected,(d,w)->{d.dismiss();pick.accept(w);}).setNegativeButton("Tutup",null).show();
    }
    private static TextView button(Activity a,String text){TextView b=new TextView(a);b.setText(text);b.setTextSize(11);b.setMaxLines(2);b.setMinHeight(dp(a,48));b.setPadding(dp(a,3),dp(a,3),dp(a,3),dp(a,3));b.setGravity(Gravity.CENTER);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setTextColor(DriverThemeTokens.textPrimary(a));android.graphics.drawable.GradientDrawable bg=DriverNavigationUi.roundRect(DriverThemeTokens.surface(a),dp(a,12));bg.setStroke(dp(a,1),DriverThemeTokens.border(a));b.setBackground(bg);b.setElevation(dp(a,1));return b;}
    private static int dp(Activity a,int value){return Math.round(value*a.getResources().getDisplayMetrics().density);}
}
