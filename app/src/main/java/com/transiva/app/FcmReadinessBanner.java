package com.transiva.app;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.WeakHashMap;
import org.json.JSONObject;
/** Nonblocking dashboard status. It disappears after registration and notification permission are ready. */
final class FcmReadinessBanner {
    private static final WeakHashMap<Activity,State> STATES=new WeakHashMap<>();
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    static void attach(Activity a){
        String name=a.getClass().getSimpleName();if(!name.equals("CustomerDashboardActivity")&&!name.equals("DriverDashboardActivity"))return;
        detach(a);if(FcmTokenCoordinator.disabled())return;
        View root=a.findViewById(android.R.id.content);if(!(root instanceof FrameLayout))return;
        State state=new State(a,(FrameLayout)root);STATES.put(a,state);state.tick.run();
    }
    static void detach(Activity a){State s=STATES.remove(a);if(s!=null){MAIN.removeCallbacks(s.tick);s.root.removeView(s.label);}}
    private static final class State {
        final Activity a;final FrameLayout root;final TextView label;final Runnable tick;
        State(Activity activity,FrameLayout parent){
            a=activity;root=parent;label=new TextView(a);float d=a.getResources().getDisplayMetrics().density;
            label.setTextColor(Color.rgb(16,61,105));label.setTextSize(14);label.setMaxLines(3);label.setPadding((int)(14*d),(int)(10*d),(int)(14*d),(int)(10*d));label.setElevation(6*d);
            GradientDrawable bg=new GradientDrawable();bg.setColor(Color.rgb(232,245,255));bg.setCornerRadius(12*d);bg.setStroke((int)Math.max(1,d),Color.rgb(129,186,233));label.setBackground(bg);
            FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);lp.leftMargin=lp.rightMargin=(int)(12*d);lp.bottomMargin=(int)(88*d);
            label.setLayoutParams(lp);label.setVisibility(View.GONE);root.addView(label);
            label.setOnClickListener(v->a.startActivity(new Intent(a,FcmRegistrationDiagnosticsActivity.class)));
            tick=new Runnable(){public void run(){
                if(a.isFinishing()||a.isDestroyed())return;
                JSONObject j=FcmTokenCoordinator.diagnostics(a);
                boolean permission=CallDeliveryTrace.notificationBlockReason(a,IncomingCallNotifier.SILENT).isEmpty();
                String text=CallDeliveryPolicy.readiness(j.optBoolean("session_ready"),j.optBoolean("current_session_acknowledged"),permission,j.optString("last_error"));
                label.setText(text);label.setContentDescription(text);label.setVisibility(text.isEmpty()?View.GONE:View.VISIBLE);
                MAIN.postDelayed(this,2000);
            }};
        }
    }
}
