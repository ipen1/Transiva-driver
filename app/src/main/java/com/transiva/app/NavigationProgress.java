package com.transiva.app;
import org.json.JSONArray;
import org.json.JSONObject;

/** Distance along geometry scaled onto provider distances, with per-step time interpolation. */
public final class NavigationProgress {
    public final double meters,seconds;
    private NavigationProgress(double m,double s){meters=m;seconds=s;}
    public static double expectedSpeed(StableRouteEngine.Result route,double progress,double geometryLength){
        if(route==null||!Double.isFinite(progress)||geometryLength<=0)return Double.NaN;
        double travelled=route.distanceMeters*Math.max(0,Math.min(1,progress/geometryLength));
        for(int i=0;i<route.maneuvers.length();i++){
            JSONObject step=route.maneuvers.optJSONObject(i);if(step==null)continue;
            double start=step.optDouble("distance_from_start",Double.NaN),length=step.optDouble("step_distance",0),time=step.optDouble("step_duration",0);
            if(Double.isFinite(start)&&Double.isFinite(length)&&Double.isFinite(time)&&length>0&&time>0&&travelled>=start&&travelled<start+length)return length/time;
        }
        return Double.NaN;
    }
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
