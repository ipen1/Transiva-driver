package com.transiva.app;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.*;
import android.provider.Settings;
import android.net.Uri;
import android.widget.*;
import org.json.JSONObject;
/** Readable local registration state. Does not display bearer or FCM tokens. */
public final class FcmRegistrationDiagnosticsActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());private TextView status;
    private final Runnable refresh=new Runnable(){public void run(){render();main.postDelayed(this,2000);}};
    @Override public void onCreate(Bundle state){
        super.onCreate(state);ScrollView scroll=new ScrollView(this);LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);int pad=(int)(20*getResources().getDisplayMetrics().density);box.setPadding(pad,pad,pad,pad);scroll.addView(box);setContentView(scroll);
        TextView title=new TextView(this);title.setText("Diagnostik Notifikasi");title.setTextSize(22);box.addView(title);
        status=new TextView(this);status.setTextSize(15);status.setTextIsSelectable(true);box.addView(status);
        Button sync=new Button(this);sync.setText("Sinkronkan ulang");sync.setOnClickListener(v->{
            JSONObject current=FcmTokenCoordinator.diagnostics(this);
            if(current.optBoolean("verification_build")){Toast.makeText(this,"Gunakan build normal untuk menguji FCM",Toast.LENGTH_LONG).show();return;}
            if(!current.optBoolean("session_ready")){Toast.makeText(this,"Sesi belum siap. Silakan masuk kembali",Toast.LENGTH_LONG).show();return;}
            FcmTokenCoordinator.forceSync(this);render();Toast.makeText(this,"Sinkronisasi dijadwalkan saat internet tersedia",Toast.LENGTH_SHORT).show();});box.addView(sync);
        Button settings=new Button(this);settings.setText("Pengaturan notifikasi");settings.setOnClickListener(v->{
            Intent i=Build.VERSION.SDK_INT>=26?new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()):new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()));startActivity(i);
        });box.addView(settings);
        Button receipts=new Button(this);receipts.setText("Kirim ulang receipt tertunda");receipts.setOnClickListener(v->{CallDeliveryTrace.resume(this);Toast.makeText(this,"Receipt dijadwalkan saat internet tersedia",Toast.LENGTH_SHORT).show();});box.addView(receipts);
        Button copy=new Button(this);copy.setText("Salin laporan");copy.setOnClickListener(v->{android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(clipboard!=null)clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Status notifikasi",status.getText()));Toast.makeText(this,"Laporan disalin",Toast.LENGTH_SHORT).show();});box.addView(copy);
        TextView note=new TextView(this);note.setText("Konfirmasi registrasi berarti token tersimpan. NOTIFICATION_POSTED berarti Android menerima permintaan notifikasi; RINGING_STARTED berarti API dering melaporkan playback. Receipt adalah laporan aplikasi, bukan bukti pengguna mendengar atau menjawab.");box.addView(note);
    }
    private String time(long t){return t<=0?"Belum ada":java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(t));}
    private void render(){if(status==null)return;JSONObject j=FcmTokenCoordinator.diagnostics(this);
        boolean enabled=androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled();
        String channel="Belum dibuat";if(Build.VERSION.SDK_INT>=26){NotificationManager n=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);android.app.NotificationChannel ch=n==null?null:n.getNotificationChannel(IncomingCallNotifier.SILENT);if(ch!=null)channel=ch.getImportance()==NotificationManager.IMPORTANCE_NONE?"Diblokir":"Aktif";}
        String fallback="Belum dibuat";if(Build.VERSION.SDK_INT>=26){NotificationManager n=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);android.app.NotificationChannel ch=n==null?null:n.getNotificationChannel(IncomingCallNotifier.AUDIBLE);if(ch!=null)fallback=ch.getImportance()==NotificationManager.IMPORTANCE_NONE?"Diblokir":"Aktif";}
        String error=j.optString("last_error");if(error.isEmpty())error="Tidak ada";
        status.setText("Aplikasi: "+j.optString("app")+"\nSesi siap: "+j.optBoolean("session_ready")+"\nBuild pengujian lokal: "+j.optBoolean("verification_build")
            +"\nNotifikasi diizinkan: "+enabled+"\nKanal panggilan: "+channel+"\nKanal cadangan: "+fallback+"\nRegistrasi tertunda: "+j.optBoolean("pending")
            +"\nSesi ini terkonfirmasi: "+j.optBoolean("current_session_acknowledged")+"\nKonfirmasi terakhir: "+time(j.optLong("last_ack_at"))
            +"\nPercobaan terakhir: "+time(j.optLong("last_attempt_at"))+"\nHTTP: "+j.optInt("last_http")+"\nKesalahan: "+error+"\nPush panggilan terakhir: "+j.optString("last_call_push")+" ("+time(j.optLong("last_call_push_at"))+")"+"\nFingerprint: "+j.optString("token_fingerprint")+"\n\nTahapan panggilan terakhir:\n"+CallDeliveryTrace.report(this)+"\n\n");
    }
    @Override protected void onResume(){super.onResume();main.post(refresh);}
    @Override protected void onPause(){main.removeCallbacks(refresh);super.onPause();}
    @Override protected void onDestroy(){main.removeCallbacksAndMessages(null);super.onDestroy();}
}
