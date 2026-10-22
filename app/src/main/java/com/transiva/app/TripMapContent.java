package com.transiva.app;

import android.view.View;
import android.widget.LinearLayout;

/** Reconcile surrounding cards without removing an already attached map/surface from its parent. */
final class TripMapContent {
    private TripMapContent(){}
    static void apply(LinearLayout display,LinearLayout fresh,View mapCard,View mapSlot){
        for(int i=display.getChildCount()-1;i>=0;i--)if(display.getChildAt(i)!=mapCard)display.removeViewAt(i);
        int position=0;
        while(fresh.getChildCount()>0){
            View child=fresh.getChildAt(0);fresh.removeViewAt(0);
            if(child==mapSlot){position++;continue;}
            display.addView(child,position++);
        }
    }
}
