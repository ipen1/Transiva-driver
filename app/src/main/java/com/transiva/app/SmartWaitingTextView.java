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
    private android.widget.LinearLayout compactDetail;
    private String detailedMessage="";
    private final Runnable tick=new Runnable(){ public void run(){ render(); if(isAttachedToWindow())postDelayed(this,1000); } };
    public SmartWaitingTextView(Context context){ super(context);setTextSize(13);setTextColor(DriverThemeTokens.textPrimary(context));float d=getResources().getDisplayMetrics().density;int p=Math.round(10*d);setPadding(p,p,p,p);setVisibility(GONE); }
    View compactDetailRow() {
        Context host=getContext();
        compactDetail=new android.widget.LinearLayout(host);
        compactDetail.setGravity(android.view.Gravity.CENTER_VERTICAL);
        setTextSize(12);
        int pad=Math.round(10*getResources().getDisplayMetrics().density);
        compactDetail.setMinimumHeight(Math.round(48*getResources().getDisplayMetrics().density));
        compactDetail.setPadding(pad,pad/2,pad,pad/2);
        setPadding(0,0,0,0);
        compactDetail.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x22086bff),
                DriverNavigationUi.roundRect(DriverThemeTokens.surfaceAlt(host),pad),null));
        compactDetail.addView(this,new android.widget.LinearLayout.LayoutParams(0,-2,1));
        android.widget.TextView details=new android.widget.TextView(host);
        details.setText("Lihat rincian ›");
        details.setTextColor(DriverThemeTokens.isDark(host)?0xffa9cfff:0xff075ac8);
        details.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        details.setTextSize(12);
        details.setPadding(pad/2,0,0,0);
        compactDetail.addView(details,new android.widget.LinearLayout.LayoutParams(-2,-2));
        compactDetail.setOnClickListener(view->PremiumDialogs.builder(host).setTitle("Rincian waktu tunggu")
                .setMessage(detailedMessage).setPositiveButton("Tutup",null).show());
        return compactDetail;
    }
    public void bind(JSONObject value){ state=value;sampledAt=SystemClock.elapsedRealtime();render(); }
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();removeCallbacks(tick);post(tick);}
    @Override protected void onDetachedFromWindow(){removeCallbacks(tick);super.onDetachedFromWindow();}
    private String money(long n){return "Rp"+java.text.NumberFormat.getNumberInstance(new Locale("id","ID")).format(n);}
    private void render(){
        JSONArray sessions=state==null?null:state.optJSONArray("sessions");
        if(sessions==null||sessions.length()==0){
            if(compactDetail!=null)compactDetail.setVisibility(GONE);
            setVisibility(GONE);
            return;
        }
        if(compactDetail!=null)compactDetail.setVisibility(VISIBLE);
        setVisibility(VISIBLE);JSONObject active=state.optJSONObject("active");long fee=state.optLong("total_fee");
        String message="⏱ Smart Waiting";
        String compactStatus="Berhenti";
        if(active!=null){
            long elapsed=active.optLong("elapsed_seconds")+Math.min(30,Math.max(0,(SystemClock.elapsedRealtime()-sampledAt)/1000));
            long free=active.optLong("free_seconds"), rate=active.optLong("rate_per_minute");
            long activeFee=((Math.max(0,elapsed-free)+59)/60)*rate;
            fee=fee-active.optLong("fee")+activeFee;
            compactStatus=WaitingClock.text(elapsed,free);
            setTextColor(WaitingClock.color(elapsed,free,DriverThemeTokens.isDark(getContext())));
            message=compactStatus+" · "+active.optString("label")+"\n"+money(rate)+"/menit";
        }else {
            message+=" · berhenti";
            setTextColor(DriverThemeTokens.textPrimary(getContext()));
        }
        message+="\nBiaya tunggu "+money(fee)+" • "+(active!=null?"Estimasi total ":"Total ")+money(state.optLong("base_total")+fee);
        if(active==null){
            for(int i=0;i<sessions.length();i++){JSONObject s=sessions.optJSONObject(i);if(s!=null)message+="\n"+s.optString("label")+" • "+s.optInt("paid_minutes")+" menit berbayar • "+money(s.optLong("fee"));}
        }
        message+="\n100% biaya tunggu untuk driver.";
        detailedMessage=message;
        String label=compactDetail!=null?"⏱  Smart Waiting · "+compactStatus+"\nBiaya tunggu "+money(fee):message;
        if (!label.contentEquals(getText())) setText(label);
    }
}
