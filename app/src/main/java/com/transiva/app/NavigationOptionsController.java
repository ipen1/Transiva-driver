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
        activity=a;listener=l;
        profile = new TextView(a);
        sound = new TextView(a);
        choices = new TextView(a);
        choices.setText("Pilihan rute");
        profile.setText("Profil kendaraan");
        android.view.View more = root.findViewWithTag("navigation_options");
        more.setOnClickListener(v -> {
            PopupMenu menu = new PopupMenu(a, more);
            menu.getMenu().add(0, 1, 0, profile.getText());
            menu.getMenu().add(0, 2, 1, sound.getText());
            menu.getMenu().add(0, 3, 2, choices.getText());
            menu.setOnMenuItemClickListener(item -> {
                switch (item.getItemId()) {
                    case 1:
                        PremiumDialogs.builder(a).setTitle("Profil rute kendaraan")
                                .setPositiveButton("Motor",(d,w)->listener.profile("motor"))
                                .setNeutralButton("Mobil",(d,w)->listener.profile("car"))
                                .setNegativeButton("Batal",null).show();
                        break;
                    case 2:
                        enabled = !enabled;
                        updateVoice();
                        listener.voice(enabled);
                        break;
                    case 3: listener.alternatives(); break;
                    default: return false;
                }
                return true;
            });
            menu.show();
        });
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
        PremiumDialogs.builder(activity).setTitle("Pilih rute perjalanan").setSingleChoiceItems(labels,selected,(d,w)->{d.dismiss();pick.accept(w);}).setNegativeButton("Tutup",null).show();
    }
}
