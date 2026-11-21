package com.transiva.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

final class TivaSimpelBrief {
    private TivaSimpelBrief() {}

    static void show(Activity activity,JSONObject brief) {
        if (brief==null) return;
        LinearLayout transcript=new LinearLayout(activity);
        transcript.setOrientation(LinearLayout.VERTICAL);
        float density=activity.getResources().getDisplayMetrics().density;
        int gap=Math.round(14*density);
        transcript.setPadding(gap,gap,gap,gap);
        JSONArray messages=brief.optJSONArray("messages");
        if (messages!=null) for (int i=0;i<messages.length();i++) {
            JSONArray line=messages.optJSONArray(i);
            if (line==null || line.length()!=2) continue;
            boolean fromTiva="tiva".equals(line.optString(0));
            TextView row=new TextView(activity);
            row.setText((fromTiva?"Tiva AI":"Customer")+"\n"+line.optString(1));
            row.setTextSize(14);
            row.setTextColor(fromTiva?0xFF154B84:0xFF263644);
            row.setPadding(0,0,0,gap);
            row.setTextIsSelectable(true);
            transcript.addView(row);
        }
        ScrollView scroll=new ScrollView(activity);
        scroll.addView(transcript);
        new AlertDialog.Builder(activity)
                .setTitle("Percakapan Tiva AI · "+brief.optString("title"))
                .setView(scroll)
                .setPositiveButton("Tutup",null)
                .show();
    }
}
