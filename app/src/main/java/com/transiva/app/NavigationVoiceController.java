package com.transiva.app;
import android.app.Activity;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.widget.Toast;
import java.util.Locale;

/** Indonesian TTS guidance; no session credentials are stored or sent to a speech service. */
public final class NavigationVoiceController implements AutoCloseable {
    private final Activity activity;
    private final NavigationVoicePolicy policy=new NavigationVoicePolicy();
    private TextToSpeech engine;
    private boolean ready,closed,enabled=true,resumed,initialized;
    private String route="";
    private java.util.function.Consumer<Boolean> statusListener;
    public NavigationVoiceController(Activity a){
        activity=a;
        engine=new TextToSpeech(a.getApplicationContext(),status->new android.os.Handler(android.os.Looper.getMainLooper()).post(()->{
            if(closed||engine==null)return;
            initialized=true;
            if(status!=TextToSpeech.SUCCESS){if(statusListener!=null)statusListener.accept(false);return;}
            int language=engine.setLanguage(new Locale("id","ID"));
            ready=language!=TextToSpeech.LANG_MISSING_DATA&&language!=TextToSpeech.LANG_NOT_SUPPORTED;
            if(statusListener!=null)statusListener.accept(ready);
            engine.setSpeechRate(.95f);
            engine.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        }));
    }
    public void status(java.util.function.Consumer<Boolean> callback){statusListener=callback;if(initialized)callback.accept(ready);}
    public void route(String key){if(!route.equals(key)){route=key;policy.reset();if(engine!=null)engine.stop();}}
    public void enabled(boolean value){enabled=value;if(!value&&engine!=null)engine.stop();if(value&&initialized&&!ready)Toast.makeText(activity,"Suara Bahasa Indonesia belum siap. Periksa pengaturan Text-to-speech HP.",Toast.LENGTH_LONG).show();}
    public boolean enabled(){return enabled;}
    public void resumed(boolean value){resumed=value;if(!value&&engine!=null)engine.stop();}
    public void speak(int index,double meters,String text,boolean arrival,double speedMps){
        if(closed||!resumed||!enabled||!ready||engine==null||activity.isFinishing()||activity.isDestroyed())return;
        AudioManager audio=(AudioManager)activity.getSystemService(Context.AUDIO_SERVICE);
        if(audio!=null&&audio.getMode()!=AudioManager.MODE_NORMAL)return;
        long now=SystemClock.elapsedRealtime();if(!policy.shouldSpeak(index,meters,speedMps,arrival,now))return;
        String prefix=arrival||meters<=18?"":"Dalam "+Math.round(meters/10)*10+" meter, ";
        engine.speak(prefix+text,TextToSpeech.QUEUE_FLUSH,null,"nav-"+route+"-"+index);
    }
    @Override public void close(){closed=true;ready=false;if(engine!=null){engine.stop();engine.shutdown();engine=null;}}
}
