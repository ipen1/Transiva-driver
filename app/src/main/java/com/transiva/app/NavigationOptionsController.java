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
    private boolean enabled=true,ready;
    NavigationOptionsController(Activity a,FrameLayout root,Listener l){
        activity=a;listener=l;LinearLayout row=new LinearLayout(a);row.setOrientation(LinearLayout.HORIZONTAL);row.setPadding(0,0,0,0);
        profile=button(a,"Profil");sound=button(a,"Suara aktif");choices=button(a,"Pilihan rute");
        for(TextView b:new TextView[]{profile,sound,choices}){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(a,48),1);lp.setMargins(dp(a,3),0,dp(a,3),0);row.addView(b,lp);}
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,dp(a,48));lp.leftMargin=dp(a,15);lp.rightMargin=dp(a,15);lp.topMargin=dp(a,147);root.addView(row,lp);
        profile.setOnClickListener(v->new AlertDialog.Builder(a).setTitle("Profil rute kendaraan").setMessage("Profil motor memerlukan provider khusus. Jika belum tersedia, label rute mobil akan ditampilkan.").setPositiveButton("Motor",(d,w)->listener.profile("motor")).setNeutralButton("Mobil",(d,w)->listener.profile("car")).setNegativeButton("Batal",null).show());
        sound.setOnClickListener(v->{enabled=!enabled;updateVoice();listener.voice(enabled);});choices.setOnClickListener(v->listener.alternatives());
    }
    void profile(String requested,String used){profile.setText("motor".equals(requested)?("motor".equals(used)?"Motor":"Motor\n(rute mobil)"):"Mobil");}
    void voice(boolean value){enabled=value;updateVoice();}
    void voiceReady(boolean value){ready=value;updateVoice();}
    private void updateVoice(){sound.setText(enabled?(ready?"Suara aktif":"Suara belum siap"):"Suara mati");}
    void choices(List<StableRouteEngine.Result> routes,int selected,java.util.function.IntConsumer pick){
        if(routes.size()<2){Toast.makeText(activity,"Provider belum menyediakan alternatif untuk titik ini.",Toast.LENGTH_LONG).show();return;}
        String[] labels=new String[routes.size()];for(int i=0;i<labels.length;i++){StableRouteEngine.Result r=routes.get(i);labels[i]=String.format(Locale.US,"Rute %d%s • %.1f km • ±%d menit%s",i+1,i==selected?" (aktif)":"",r.distanceMeters/1000,Math.max(1,(int)Math.ceil(r.durationSeconds/60)),r.summary.isEmpty()?"":"\n"+r.summary);}
        new AlertDialog.Builder(activity).setTitle("Pilih rute perjalanan").setSingleChoiceItems(labels,selected,(d,w)->{d.dismiss();pick.accept(w);}).setNegativeButton("Tutup",null).show();
    }
    private static TextView button(Activity a,String text){TextView b=new TextView(a);b.setText(text);b.setTextSize(12);b.setGravity(Gravity.CENTER);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setTextColor(DriverThemeTokens.textPrimary(a));b.setBackgroundColor(DriverThemeTokens.surface(a));b.setElevation(dp(a,4));return b;}
    private static int dp(Activity a,int value){return Math.round(value*a.getResources().getDisplayMetrics().density);}
}
