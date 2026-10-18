package com.transiva.app;
import android.content.Context;
import android.media.AudioManager;
import android.app.NotificationManager;
/** Conservative API confirmation. No audio start, volume change or extra poller. */
final class CallRingingState {
    static boolean confirmed(boolean playing,boolean normalMode,int ringVolume,boolean unrestricted){
        return playing&&normalMode&&ringVolume>0&&unrestricted;
    }
    static String status(boolean incoming,boolean accepted,long connectedAt,String state,boolean confirmed,String peer,String current){
        if(incoming||accepted||connectedAt>0||!"ringing".equals(state))return current;
        return (confirmed?"Berdering":"Memanggil")+" "+peer;
    }
    static boolean audible(Context c,String call){
        try{
            AudioManager audio=(AudioManager)c.getSystemService(Context.AUDIO_SERVICE);
            NotificationManager notifications=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
            return audio!=null&&notifications!=null&&confirmed(IncomingCallAlertManager.isRinging(call),
                audio.getRingerMode()==AudioManager.RINGER_MODE_NORMAL,audio.getStreamVolume(AudioManager.STREAM_RING),
                notifications.getCurrentInterruptionFilter()==NotificationManager.INTERRUPTION_FILTER_ALL);
        }catch(RuntimeException unavailable){return false;}
    }
}
