package com.transiva.app;
import android.content.Context;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;

/** The page never steals a gesture that began inside the visible map region. */
final class NavigationTripScrollView extends ScrollView {
    private boolean mapGesture;private final Rect bounds=new Rect();
    NavigationTripScrollView(Context context){super(context);}
    @Override public boolean onInterceptTouchEvent(MotionEvent event){
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){View map=findViewWithTag("navigation_trip_map");mapGesture=map!=null&&map.getGlobalVisibleRect(bounds)&&bounds.contains((int)event.getRawX(),(int)event.getRawY());}
        if(mapGesture){if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)mapGesture=false;return false;}
        return super.onInterceptTouchEvent(event);
    }
}
