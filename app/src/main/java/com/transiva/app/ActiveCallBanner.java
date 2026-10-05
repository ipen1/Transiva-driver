package com.transiva.app;
import android.content.*;
import android.graphics.drawable.GradientDrawable;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
/** One live session entry point across both chatrooms. Never starts a second call. */
public final class ActiveCallBanner extends TextView {
    private boolean registered;
    private final BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){render();}};
    private final Runnable clock=new Runnable(){public void run(){render();if(isAttachedToWindow())postDelayed(this,1000);}};
    public ActiveCallBanner(Context c){super(c);float d=getResources().getDisplayMetrics().density;int p=Math.round(12*d);setPadding(p,p,p,p);setTextSize(12);setTextColor(0xFF075CA9);setTypeface(null,android.graphics.Typeface.BOLD);GradientDrawable bg=new GradientDrawable();bg.setColor(0xFFEAF5FF);bg.setCornerRadius(p);setBackground(bg);setVisibility(GONE);setOnClickListener(v->{WebRtcSessionEngine e=WebRtcCallForegroundService.current();if(e!=null&&e.active())getContext().startActivity(WebRtcCallForegroundService.open(getContext()));});}
    private void render(){WebRtcSessionEngine e=WebRtcCallForegroundService.current();if(e==null||!e.active()){setVisibility(GONE);return;}setVisibility(VISIBLE);long seconds=e.connectedAt==0?0:Math.max(0,(android.os.SystemClock.elapsedRealtime()-e.connectedAt)/1000);setText((e.video?"📹 Video Call":"☎ Panggilan suara")+" berlangsung • "+e.peer+"\n"+(e.connected?String.format(java.util.Locale.US,"%02d:%02d",seconds/60,seconds%60):e.status)+" • Ketuk untuk kembali");}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();ContextCompat.registerReceiver(getContext(),receiver,new IntentFilter(WebRtcSessionEngine.CHANGED),ContextCompat.RECEIVER_NOT_EXPORTED);registered=true;removeCallbacks(clock);post(clock);}
    @Override protected void onDetachedFromWindow(){removeCallbacks(clock);if(registered){getContext().unregisterReceiver(receiver);registered=false;}super.onDetachedFromWindow();}
}
