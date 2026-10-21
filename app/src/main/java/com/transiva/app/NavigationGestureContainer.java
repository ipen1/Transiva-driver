package com.transiva.app;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewParent;
import android.widget.FrameLayout;

/** Keeps map drags/pinches inside the map instead of scrolling the enclosing Trip page. */
final class NavigationGestureContainer extends FrameLayout {
    NavigationGestureContainer(Context context){super(context);}
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        ViewParent parent=getParent();int action=event.getActionMasked();
        boolean ended=action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL;
        if(parent!=null)parent.requestDisallowInterceptTouchEvent(true);
        try{return super.dispatchTouchEvent(event);}
        finally{if(ended&&parent!=null)parent.requestDisallowInterceptTouchEvent(false);}
    }
    @Override protected void onDetachedFromWindow(){
        ViewParent parent=getParent();if(parent!=null)parent.requestDisallowInterceptTouchEvent(false);
        super.onDetachedFromWindow();
    }
}
