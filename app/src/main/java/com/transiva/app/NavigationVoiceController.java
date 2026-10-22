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
    private final Context context;
    private final java.util.function.BooleanSupplier alive;
    private final NavigationVoicePolicy policy=new NavigationVoicePolicy();
    private TextToSpeech engine;
    private boolean ready,closed,enabled=true,resumed,initialized;
    private String route="";
    private java.util.function.Consumer<Boolean> statusListener;
    public NavigationVoiceController(Activity a){
        this(a,()->!a.isFinishing()&&!a.isDestroyed());
    }
    public NavigationVoiceController(Context a,java.util.function.BooleanSupplier lifetime){
        context=a;alive=lifetime;
        engine=new TextToSpeech(a.getApplicationContext(),status->new android.os.Handler(android.os.Looper.getMainLooper()).post(()->{
            if(closed||engine==null)return;
            initialized=true;
            if(status!=TextToSpeech.SUCCESS){if(statusListener!=null)statusListener.accept(false);return;}
            int language=engine.setLanguage(new Locale("id","ID"));
            ready=language!=TextToSpeech.LANG_MISSING_DATA&&language!=TextToSpeech.LANG_NOT_SUPPORTED;
            if(statusListener!=null)statusListener.accept(ready);
            selectVoice();engine.setSpeechRate(.97f);engine.setPitch(1f);
            engine.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        }));
    }
    public void status(java.util.function.Consumer<Boolean> callback){statusListener=callback;if(initialized)callback.accept(ready);}
    public void route(String key){if(!route.equals(key)){route=key;policy.reset();if(engine!=null)engine.stop();}}
    public void enabled(boolean value){enabled=value;if(!value&&engine!=null)engine.stop();if(value&&initialized&&!ready)Toast.makeText(context,"Suara Bahasa Indonesia belum siap. Periksa pengaturan Text-to-speech HP.",Toast.LENGTH_LONG).show();}
    public boolean enabled(){return enabled;}
    public void resumed(boolean value){resumed=value;if(!value&&engine!=null)engine.stop();}
    private void selectVoice(){
        try{
            android.speech.tts.Voice best=null;java.util.Set<android.speech.tts.Voice> voices=engine.getVoices();if(voices==null)return;
            for(android.speech.tts.Voice candidate:voices){
                String language=candidate.getLocale().getLanguage();
                if(candidate.getFeatures()!=null&&candidate.getFeatures().contains("notInstalled"))continue;
                if(candidate.isNetworkConnectionRequired()||!("id".equals(language)||"in".equals(language)))continue;
                if(best==null||candidate.getQuality()>best.getQuality()||candidate.getQuality()==best.getQuality()&&candidate.getLatency()<best.getLatency())best=candidate;
            }
            if(best!=null)engine.setVoice(best);
        }catch(RuntimeException e){NavigationDiagnostics.error(context,"NAV_VOICE_SELECTION_FAILED",e);}
    }
    private boolean available(){
        if(closed||!resumed||!enabled||!ready||engine==null||!alive.getAsBoolean())return false;
        AudioManager audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        return audio==null||audio.getMode()==AudioManager.MODE_NORMAL;
    }
    public void speak(int index,double meters,String text,boolean arrival,double speed){speak(index,meters,text,arrival,speed,true);}
    public void speak(int index,double meters,String text,boolean arrival,double speed,boolean confirmed){
        if(!available()||!policy.shouldSpeak(index,meters,speed,arrival,confirmed,SystemClock.elapsedRealtime()))return;
        if(engine.speak(NavigationSpeechText.prompt(meters,speed,arrival,text),TextToSpeech.QUEUE_FLUSH,null,"nav-"+route+"-"+index)!=TextToSpeech.SUCCESS)policy.retry(index);
    }
    public boolean cruise(int index,double meters,double speed){
        if(!available()||!policy.cruise(index,meters,speed,SystemClock.elapsedRealtime()))return false;
        if(engine.speak("Terus lurus sejauh "+NavigationSpeechText.distance(meters)+".",TextToSpeech.QUEUE_FLUSH,null,"nav-"+route+"-cruise-"+index)!=TextToSpeech.SUCCESS){policy.retry(index);return false;}
        return true;
    }
    @Override public void close(){closed=true;ready=false;if(engine!=null){engine.stop();engine.shutdown();engine=null;}}
}
