package com.transiva.app;

import static org.junit.Assert.*;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real Android parent/child touch dispatch; the map proxy reproduces SDK interception requests. */
@RunWith(AndroidJUnit4.class)
public class TripMapGestureInstrumentedTest {
    private static final class MapTouchProxy extends View {
        int moves,cancels;
        MapTouchProxy(android.content.Context context){super(context);}
        @Override public boolean onTouchEvent(MotionEvent event){
            if(event.getActionMasked()==MotionEvent.ACTION_MOVE){moves++;getParent().requestDisallowInterceptTouchEvent(false);}
            if(event.getActionMasked()==MotionEvent.ACTION_CANCEL)cancels++;
            return true;
        }
    }
    private static void event(NavigationTripScrollView page,int action,long down,long at,float x,float y){
        int[] origin=new int[2];page.getLocationOnScreen(origin);
        MotionEvent e=MotionEvent.obtain(down,at,action,x+origin[0],y+origin[1],0);
        e.offsetLocation(-origin[0],-origin[1]);
        try{page.dispatchTouchEvent(e);}finally{e.recycle();}
    }
    @Test public void mapDragSurvivesChildInterceptionRequestAndPageStillScrollsOutsideMap(){
        AtomicReference<NavigationTripScrollView> pageRef=new AtomicReference<>();
        AtomicReference<NavigationGestureContainer> mapRef=new AtomicReference<>();
        AtomicReference<MapTouchProxy> proxyRef=new AtomicReference<>();
        try(ActivityScenario<TestHarnessActivity> scenario=ActivityScenario.launch(TestHarnessActivity.class)){
            scenario.onActivity(activity->{
                NavigationTripScrollView page=new NavigationTripScrollView(activity);
                LinearLayout content=new LinearLayout(activity);content.setOrientation(LinearLayout.VERTICAL);
                NavigationGestureContainer map=new NavigationGestureContainer(activity);
                MapTouchProxy proxy=new MapTouchProxy(activity);map.addView(proxy,new android.widget.FrameLayout.LayoutParams(-1,-1));
                int mapHeight=(int)(180*activity.getResources().getDisplayMetrics().density);
                content.addView(map,new LinearLayout.LayoutParams(-1,mapHeight));
                content.addView(new View(activity),new LinearLayout.LayoutParams(-1,mapHeight*8));
                page.addView(content);activity.setContentView(page);pageRef.set(page);mapRef.set(map);proxyRef.set(proxy);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity->{
                NavigationTripScrollView page=pageRef.get();NavigationGestureContainer map=mapRef.get();MapTouchProxy proxy=proxyRef.get();
                Rect rect=new Rect();assertTrue(map.getGlobalVisibleRect(rect));
                int[] origin=new int[2];page.getLocationOnScreen(origin);
                float x=rect.centerX()-origin[0],y=rect.centerY()-origin[1];long down=SystemClock.uptimeMillis();
                event(page,MotionEvent.ACTION_DOWN,down,down,x,y);
                for(int i=1;i<=5;i++)event(page,MotionEvent.ACTION_MOVE,down,down+i*20,x,y-i*12);
                event(page,MotionEvent.ACTION_UP,down,down+140,x,y-60);
                assertTrue("Map receives MOVE despite child trying to unlock parent interception",proxy.moves>0);
                assertEquals("Parent must not cancel a map drag",0,proxy.cancels);
                assertEquals("Map drag must not scroll the enclosing page",0,page.getScrollY());
                float outside=Math.min(page.getHeight()-16,map.getBottom()+160);
                assertTrue("Test requires a visible scrollable region outside map",outside>map.getBottom()+24);
                down+=500;event(page,MotionEvent.ACTION_DOWN,down,down,x,outside);
                for(int i=1;i<=6;i++)event(page,MotionEvent.ACTION_MOVE,down,down+i*20,x,outside-i*18);
                event(page,MotionEvent.ACTION_UP,down,down+150,x,outside-108);
                assertTrue("Page remains scrollable for gestures starting outside map",page.getScrollY()>0);
            });
        }
    }
}
