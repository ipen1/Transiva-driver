package com.transiva.app;

/** Foreground/background use the same speech stages and freshness/session guard. */
final class NavigationGuidanceVoice implements NavigationInstructionController.Voice {
    private final NavigationVoiceController voice;private final java.util.function.BooleanSupplier allowed;private final java.util.function.DoubleSupplier speed;
    NavigationGuidanceVoice(NavigationVoiceController v,java.util.function.BooleanSupplier a,java.util.function.DoubleSupplier s){voice=v;allowed=a;speed=s;}
    static boolean fresh(android.location.Location fix){
        if(fix==null||!fix.hasAccuracy()||!Float.isFinite(fix.getAccuracy())||fix.getAccuracy()<=0||fix.getAccuracy()>35)return false;
        long nanos=fix.getElapsedRealtimeNanos(),age=android.os.SystemClock.elapsedRealtimeNanos()-nanos;return nanos>0&&age>=0&&age<=5500_000_000L;
    }
    public void instruction(int index,double meters,String text,boolean arrival){instruction(index,meters,text,arrival,true);}
    public void instruction(int index,double meters,String text,boolean arrival,boolean confirmed){if(allowed.getAsBoolean())voice.speak(index,meters,text,arrival,speed.getAsDouble(),confirmed);}
    public boolean cruise(int index,double meters){return allowed.getAsBoolean()&&voice.cruise(index,meters,speed.getAsDouble());}
}
