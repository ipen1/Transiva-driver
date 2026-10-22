package com.transiva.app;
import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewParent;
import android.widget.FrameLayout;

/** Owns the entire gesture, including child SDK requests to re-enable parent interception. */
final class NavigationGestureContainer extends FrameLayout {
    private boolean touching;
    private java.util.function.Consumer<Boolean> listener;
    NavigationGestureContainer(Context context){super(context);setTag("navigation_trip_map");}
    void interaction(java.util.function.Consumer<Boolean> callback){listener=callback;}
    private void touching(boolean value){if(touching==value)return;touching=value;if(listener!=null)listener.accept(value);}
    @Override public void requestDisallowInterceptTouchEvent(boolean value){super.requestDisallowInterceptTouchEvent(touching||value);}
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        int action=event.getActionMasked();boolean ended=action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL;
        if(action==MotionEvent.ACTION_DOWN)touching(true);
        super.requestDisallowInterceptTouchEvent(true);
        try{return super.dispatchTouchEvent(event);}
        finally{if(ended){touching(false);super.requestDisallowInterceptTouchEvent(false);}}
    }
    @Override protected void onDetachedFromWindow(){touching(false);super.requestDisallowInterceptTouchEvent(false);super.onDetachedFromWindow();}
}
