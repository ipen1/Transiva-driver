package com.transiva.app;

import android.app.Activity;
import android.view.Display;
import android.view.WindowManager;

/** Request only a supported refresh mode at the existing resolution. Android retains final control. */
final class NavigationDisplayPolicy {
    static void prefer(Activity activity,long frameMs){
        try{
            Display display=activity.getWindowManager().getDefaultDisplay();if(display==null)return;
            Display.Mode current=display.getMode();float ceiling=frameMs<=8?120:60,best=0;int chosen=0;
            for(Display.Mode mode:display.getSupportedModes()){
                float hz=mode.getRefreshRate();
                if(mode.getPhysicalWidth()==current.getPhysicalWidth()&&mode.getPhysicalHeight()==current.getPhysicalHeight()&&hz>=59&&hz<=ceiling+.5f&&hz>best){best=hz;chosen=mode.getModeId();}
            }
            if(chosen==0)return;
            WindowManager.LayoutParams params=activity.getWindow().getAttributes();
            if(params.preferredDisplayModeId==chosen&&Math.abs(params.preferredRefreshRate-best)<.1f)return;
            params.preferredDisplayModeId=chosen;params.preferredRefreshRate=best;activity.getWindow().setAttributes(params);
        }catch(RuntimeException e){NavigationDiagnostics.error(activity,"NAV_REFRESH_PREFERENCE_FAILED",e);}
    }
}
