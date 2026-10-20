package com.transiva.app;
import org.json.JSONArray;
import org.json.JSONObject;

/** Distance along geometry scaled onto provider distances, with per-step time interpolation. */
public final class NavigationProgress {
    public final double meters,seconds;
    private NavigationProgress(double m,double s){meters=m;seconds=s;}
    public static NavigationProgress remaining(StableRouteEngine.Result route,double progress,double geometryLength){
        double fraction=geometryLength>0&&Double.isFinite(progress)?Math.max(0,Math.min(1,progress/geometryLength)):0;
        double travelled=route.distanceMeters*fraction,totalTime=0,remainingTime=0;
        JSONArray maneuvers=route.maneuvers;
        for(int i=0;i<maneuvers.length();i++){
            JSONObject step=maneuvers.optJSONObject(i);if(step==null)continue;
            double start=step.optDouble("distance_from_start",0),length=Math.max(0,step.optDouble("step_distance",0)),time=Math.max(0,step.optDouble("step_duration",0));
            totalTime+=time;
            if(travelled<=start)remainingTime+=time;
            else if(length>0&&travelled<start+length)remainingTime+=time*(1-(travelled-start)/length);
        }
        if(totalTime<=0)remainingTime=route.durationSeconds*(1-fraction);
        else if(totalTime>0)remainingTime*=route.durationSeconds/totalTime;
        return new NavigationProgress(Math.max(0,route.distanceMeters-travelled),Math.max(0,remainingTime));
    }
}
