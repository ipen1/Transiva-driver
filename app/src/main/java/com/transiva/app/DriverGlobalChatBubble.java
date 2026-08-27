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
        if (activity instanceof SplashActivity || activity instanceof LoginActivity || activity instanceof PinActivity) return;
        View decor = activity.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) return;
        ViewGroup root=(ViewGroup)decor;
        View existing=root.findViewWithTag(TAG_KEY);
        if(existing instanceof TextView){ current=new WeakReference<>((TextView)existing); refreshCurrent(); return; }

        TextView bubble=new TextView(activity);
        bubble.setTag(TAG_KEY);
        bubble.setText("💬");
        bubble.setTextSize(20);
        bubble.setTypeface(Typeface.DEFAULT_BOLD);
        bubble.setGravity(Gravity.CENTER);
        bubble.setElevation(dp(activity,10));
        bubble.setContentDescription("Chat global driver");
        bubble.setOnClickListener(v -> {
            Intent i=new Intent(activity,DriverGlobalChatActivity.class);
            long mention=DriverGlobalChatStore.getLastMentionId(activity);
            if(DriverGlobalChatStore.getUnreadMentions(activity)>0 && mention>0)i.putExtra("jump_message_id",mention);
            i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            activity.startActivity(i);
        });
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(dp(activity,50),dp(activity,50),Gravity.START|Gravity.CENTER_VERTICAL);
        lp.leftMargin=dp(activity,5);
        if(root instanceof FrameLayout) ((FrameLayout)root).addView(bubble,lp);
        else root.addView(bubble,new ViewGroup.LayoutParams(dp(activity,50),dp(activity,50)));
        current=new WeakReference<>(bubble);
        refreshCurrent();
    }

    public static void refreshCurrent(){
        TextView b=current.get(); if(b==null)return;
        int unread=DriverGlobalChatStore.getUnreadMentions(b.getContext());
        if(unread>0){ b.setText(unread>9?"💬\n9+":"💬\n"+unread); b.setTextSize(14); b.setBackground(bg("#FFCC3D","#FFFFFF")); }
        else { b.setText("💬"); b.setTextSize(20); b.setBackground(bg("#0B7CFF","#FFFFFF")); }
    }

    private static GradientDrawable bg(String fill,String stroke){ GradientDrawable g=new GradientDrawable(); g.setColor(Color.parseColor(fill)); g.setCornerRadii(new float[]{0,0,24,24,24,24,0,0}); g.setStroke(2,Color.parseColor(stroke)); return g; }
    private static int dp(Activity a,int v){return Math.round(v*a.getResources().getDisplayMetrics().density);}
}
