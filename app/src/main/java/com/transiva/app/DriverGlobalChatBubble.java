package com.transiva.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.lang.ref.WeakReference;

public final class DriverGlobalChatBubble {
    private static final int TAG_KEY = 0x54524348;
    private static WeakReference<TextView> current = new WeakReference<>(null);
    private DriverGlobalChatBubble() {}

    public static void attach(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        if (activity instanceof SplashActivity || activity instanceof LoginActivity || activity instanceof PinActivity || activity instanceof DriverGlobalChatActivity) {
            detach(activity); return;
        }
        View decor = activity.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) return;
        ViewGroup root=(ViewGroup)decor;
        View existing=root.findViewWithTag(TAG_KEY);
        if(existing instanceof TextView){ current=new WeakReference<>((TextView)existing); refreshCurrent(); return; }

        TextView bubble=new TextView(activity);
        bubble.setTag(TAG_KEY);
        bubble.setText(">");
        bubble.setTextSize(24);
        bubble.setTypeface(Typeface.DEFAULT_BOLD);
        bubble.setGravity(Gravity.CENTER);
        bubble.setElevation(dp(activity,12));
        bubble.setContentDescription("Buka chat global driver");
        bubble.setOnClickListener(v -> {
            Intent i=new Intent(activity,DriverGlobalChatActivity.class);
            long mention=DriverGlobalChatStore.getLastMentionId(activity);
            if(DriverGlobalChatStore.getUnreadMentions(activity)>0 && mention>0)i.putExtra("jump_message_id",mention);
            i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            activity.startActivity(i);
            activity.overridePendingTransition(R.anim.global_chat_enter_from_left,R.anim.global_chat_hold);
        });
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(dp(activity,28),dp(activity,58),Gravity.START|Gravity.CENTER_VERTICAL);
        lp.leftMargin=0;
        if(root instanceof FrameLayout) ((FrameLayout)root).addView(bubble,lp);
        else root.addView(bubble,new ViewGroup.LayoutParams(dp(activity,28),dp(activity,58)));
        current=new WeakReference<>(bubble);
        refreshCurrent();
    }

    public static void detach(Activity activity){
        if(activity==null)return;
        View decor=activity.getWindow().getDecorView();
        if(decor instanceof ViewGroup){View v=((ViewGroup)decor).findViewWithTag(TAG_KEY);if(v!=null)((ViewGroup)v.getParent()).removeView(v);}
        TextView b=current.get(); if(b!=null && b.getContext()==activity)current=new WeakReference<>(null);
    }

    public static void refreshCurrent(){
        TextView b=current.get(); if(b==null)return;
        int unread=DriverGlobalChatStore.getUnreadMentions(b.getContext());
        b.setText(">"); b.setTextSize(24);
        b.setTextColor(Color.WHITE);
        b.setBackground(bg(unread>0?"#F59E0B":"#0B7CFF","#FFFFFF"));
        b.setContentDescription(unread>0?"Chat global, ada "+unread+" mention baru":"Buka chat global driver");
    }

    private static GradientDrawable bg(String fill,String stroke){ GradientDrawable g=new GradientDrawable(); g.setColor(Color.parseColor(fill)); g.setCornerRadii(new float[]{0,0,26,26,26,26,0,0}); g.setStroke(1,Color.parseColor(stroke)); return g; }
    private static int dp(Activity a,int v){return Math.round(v*a.getResources().getDisplayMetrics().density);}
}
