package com.transiva.app;
import android.content.Context;
import android.os.SystemClock;
import android.view.View;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.Locale;
/** Server snapshots + monotonic display clock. Billing is always calculated by server. */
public final class SmartWaitingTextView extends android.widget.TextView {
    private JSONObject state;
    private long sampledAt;
    private final Runnable tick=new Runnable(){ public void run(){ render(); if(isAttachedToWindow())postDelayed(this,1000); } };
    public SmartWaitingTextView(Context context){ super(context);setTextSize(13);setTextColor(DriverThemeTokens.textPrimary(context));float d=getResources().getDisplayMetrics().density;int p=Math.round(10*d);setPadding(p,p,p,p);setVisibility(GONE); }
    public void bind(JSONObject value){ state=value;sampledAt=SystemClock.elapsedRealtime();render(); }
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();removeCallbacks(tick);post(tick);}
    @Override protected void onDetachedFromWindow(){removeCallbacks(tick);super.onDetachedFromWindow();}
    private String money(long n){return "Rp"+java.text.NumberFormat.getNumberInstance(new Locale("id","ID")).format(n);}
    private void render(){
        JSONArray sessions=state==null?null:state.optJSONArray("sessions");
        if(sessions==null||sessions.length()==0){setVisibility(GONE);return;}
        setVisibility(VISIBLE);JSONObject active=state.optJSONObject("active");long fee=state.optLong("total_fee");
        String message="⏱ Smart Waiting";
        if(active!=null){
            long elapsed=active.optLong("elapsed_seconds")+Math.max(0,(SystemClock.elapsedRealtime()-sampledAt)/1000);
            long free=active.optLong("free_seconds",300), rate=active.optLong("rate_per_minute",500);
            long activeFee=((Math.max(0,elapsed-free)+59)/60)*rate;
            fee=fee-active.optLong("fee")+activeFee;
            long remaining=Math.max(0,free-elapsed);
            message+=" • "+active.optString("label")+"\n"+(remaining>0?String.format(Locale.US,"Gratis tersisa %02d:%02d",remaining/60,remaining%60):"Waktu berbayar berjalan")+" • "+money(rate)+"/menit";
        }else message+=" • berhenti";
        message+="\nBiaya tunggu "+money(fee)+" • "+(active!=null?"Estimasi total ":"Total ")+money(state.optLong("base_total")+fee);
        if(active==null){
            for(int i=0;i<sessions.length();i++){JSONObject s=sessions.optJSONObject(i);if(s!=null)message+="\n"+s.optString("label")+" • "+s.optInt("paid_minutes")+" menit berbayar • "+money(s.optLong("fee"));}
        }
        if(active==null)message+="\nGratis 5 menit setiap titik; menit tambahan yang dimulai Rp500.";
        setText(message);
    }
}
