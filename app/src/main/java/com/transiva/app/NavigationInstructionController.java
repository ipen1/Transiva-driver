package com.transiva.app;
import android.os.SystemClock;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

public final class NavigationInstructionController {
    public interface Voice {void instruction(int index,double distance,String speech,boolean arrival);}
    private final TextView badge;
    private String destination;
    private long lastUiAt;
    private Voice voice;
    public NavigationInstructionController(TextView badge){this(badge,"tujuan");}
    public NavigationInstructionController(TextView badge,String mode){this.badge=badge;destination=destinationLabel(mode);}
    public void setVoice(Voice value){voice=value;}
    public void setDestination(String mode){destination=destinationLabel(mode);lastUiAt=0;}
    public static String destinationLabel(String mode){String value=mode==null?"":mode.trim().toLowerCase(Locale.US);if(value.matches("waypoint_[0-9]+"))return "tujuan tambahan";if("pickup".equals(value))return "penjemputan";if("delivery".equals(value))return "pengantaran";return "tujuan";}
    public void update(JSONArray maneuvers,double progress){update(maneuvers,progress,true);}
    public void update(JSONArray maneuvers,double progress,boolean arrivalAllowed){
        if(badge==null)return;long now=SystemClock.elapsedRealtime();if(now-lastUiAt<250)return;lastUiAt=now;
        JSONObject next=null;double remaining=Double.MAX_VALUE;int index=-1;
        if(maneuvers!=null)for(int i=0;i<maneuvers.length();i++){JSONObject m=maneuvers.optJSONObject(i);if(m==null)continue;double at=m.optDouble("distance_from_start",-1),d=at-progress;if(at>=0&&d>=-8&&Math.max(0,d)<remaining){remaining=Math.max(0,d);next=m;index=i;}}
        if(next==null){badge.setText("↑ Terus ikuti rute");return;}
        String type=next.optString("type").toLowerCase(Locale.US),modifier=next.optString("modifier").toLowerCase(Locale.US);
        String action=action(next,destination,arrivalAllowed),arrow="↑";
        if(type.equals("arrive"))arrow="⚑";else if(type.contains("roundabout")||type.contains("rotary"))arrow="↻";else if(modifier.contains("uturn"))arrow="↶";else if(modifier.contains("left"))arrow="↰";else if(modifier.contains("right"))arrow="↱";
        String lanes=laneText(next.optJSONArray("lanes")),road=next.optString("name").trim();
        String detail=lanes.isEmpty()?road:lanes;
        badge.setText(arrow+" "+distanceText(remaining)+" • "+action+(detail.isEmpty()?"":"\n"+detail));
        if(voice!=null)voice.instruction(index,remaining,action+(lanes.isEmpty()?"":". "+lanes),type.equals("arrive"));
    }
    static String action(JSONObject m,String destination,boolean arrivalAllowed){
        String type=m.optString("type").toLowerCase(Locale.US),modifier=m.optString("modifier").toLowerCase(Locale.US);
        if(type.equals("arrive"))return arrivalAllowed?"Tiba di "+destination:"Tujuan di depan";
        if(type.equals("exit roundabout")||type.equals("exit rotary"))return "Keluar bundaran";
        if(type.equals("roundabout turn"))return modifier.contains("left")?"Di bundaran, belok kiri":modifier.contains("right")?"Di bundaran, belok kanan":"Lurus melalui bundaran";
        if(type.contains("roundabout")||type.equals("rotary")){int exit=m.optInt("exit",0);return exit>0?"Bundaran, keluar ke-"+exit:"Masuk bundaran";}
        if(type.equals("merge"))return modifier.contains("left")?"Bergabung ke kiri":"Bergabung ke kanan";
        if(type.equals("fork"))return modifier.contains("left")?"Ambil cabang kiri":modifier.contains("right")?"Ambil cabang kanan":"Ikuti cabang lurus";
        if(type.equals("on ramp"))return modifier.contains("left")?"Masuk jalur penghubung kiri":"Masuk jalur penghubung kanan";
        if(type.contains("ramp"))return modifier.contains("left")?"Ambil jalur keluar kiri":"Ambil jalur keluar kanan";
        if(modifier.contains("uturn"))return "Putar balik";
        if(modifier.contains("left"))return modifier.contains("slight")?"Serong kiri":modifier.contains("sharp")?"Belok tajam kiri":"Belok kiri";
        if(modifier.contains("right"))return modifier.contains("slight")?"Serong kanan":modifier.contains("sharp")?"Belok tajam kanan":"Belok kanan";
        return "Terus lurus";
    }
    static String laneText(JSONArray lanes){
        if(lanes==null||lanes.length()<2)return "";StringBuilder selected=new StringBuilder();int count=0;
        for(int i=0;i<lanes.length();i++){JSONObject lane=lanes.optJSONObject(i);if(lane!=null&&lane.optBoolean("valid",false)){if(count++>0)selected.append(" atau ");selected.append(i+1);}}
        if(count==0||count==lanes.length())return "";
        return "Gunakan lajur "+selected+" dari kiri";
    }
    static String distanceText(double meters){if(meters>=1000)return String.format(Locale.US,"%.1f km",meters/1000);return Math.round(Math.max(0,meters)/10)*10+" m";}
}
