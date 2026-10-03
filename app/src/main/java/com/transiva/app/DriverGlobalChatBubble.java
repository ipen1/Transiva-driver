package com.transiva.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
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

        // Invisible edge gesture: swipe left-to-right, without a floating button.
        // Restrict the touch target to the first 22dp so normal page scrolling works.
        TextView edge=new TextView(activity);
        edge.setTag(TAG_KEY);
        edge.setContentDescription("Geser dari tepi kiri ke kanan untuk membuka chat global");
        edge.setBackgroundColor(Color.TRANSPARENT);
        final float[] down={0f,0f};
        edge.setOnTouchListener((v,event)->{
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    down[0]=event.getRawX();down[1]=event.getRawY();return true;
                case MotionEvent.ACTION_UP:
                    float dx=event.getRawX()-down[0];
                    float dy=event.getRawY()-down[1];
                    if(dx>dp(activity,75) && Math.abs(dy)<dp(activity,65)){
                        openChat(activity);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:return true;
                default:return true;
            }
        });
        if(root instanceof FrameLayout){
            FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(dp(activity,22),-1,Gravity.START|Gravity.TOP);
            lp.topMargin=dp(activity,90);lp.bottomMargin=dp(activity,85);
            ((FrameLayout)root).addView(edge,lp);
        }else{
            // Most Android Activity decor roots are FrameLayout. Do not place
            // a full-screen overlay in an unexpected layout.
            return;
        }
        current=new WeakReference<>(edge);
        refreshCurrent();
    }

    private static void openChat(Activity activity){
        Intent i=new Intent(activity,DriverGlobalChatActivity.class);
        long mention=DriverGlobalChatStore.getLastMentionId(activity);
        if(DriverGlobalChatStore.getUnreadMentions(activity)>0 && mention>0)
            i.putExtra("jump_message_id",mention);
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        activity.startActivity(i);
        activity.overridePendingTransition(R.anim.global_chat_enter_from_left,R.anim.global_chat_hold);
    }

    public static void detach(Activity activity){
        if(activity==null)return;
        View decor=activity.getWindow().getDecorView();
        if(decor instanceof ViewGroup){View v=((ViewGroup)decor).findViewWithTag(TAG_KEY);if(v!=null)((ViewGroup)v.getParent()).removeView(v);}
        TextView b=current.get(); if(b!=null && b.getContext()==activity)current=new WeakReference<>(null);
    }

    public static void refreshCurrent(){
        TextView edge=current.get();if(edge==null)return;
        int unread=DriverGlobalChatStore.getUnreadMentions(edge.getContext());
        edge.setContentDescription(unread>0
            ?"Geser dari tepi kiri ke kanan untuk chat global, "+unread+" mention baru"
            :"Geser dari tepi kiri ke kanan untuk membuka chat global");
    }

    private static GradientDrawable bg(String fill,String stroke){ GradientDrawable g=new GradientDrawable(); g.setColor(Color.parseColor(fill)); g.setCornerRadius(22); g.setStroke(1,Color.parseColor(stroke)); return g; }
    private static int dp(Activity a,int v){return Math.round(v*a.getResources().getDisplayMetrics().density);}
}
