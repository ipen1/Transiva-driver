package com.transiva.app;

import android.app.Activity;
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
        boolean dark=DriverThemeTokens.isDark(activity);
        String detail=brief.optString("detail").trim();
        if (!detail.isEmpty()) {
            TextView summary=new TextView(activity);
            summary.setText(detail);
            summary.setTextColor(DriverThemeTokens.textPrimary(activity));
            summary.setTextSize(15);
            summary.setTextIsSelectable(true);
            summary.setPadding(0,0,0,gap);
            transcript.addView(summary);
        }
        JSONArray messages=brief.optJSONArray("messages");
        if (detail.isEmpty() && (messages==null || messages.length()==0)) {
            TextView summary=new TextView(activity);
            summary.setText("Alamat dan titik perjalanan tersedia di panel rute chat.");
            summary.setTextColor(DriverThemeTokens.textSecondary(activity));
            transcript.addView(summary);
        }
        if (messages!=null) for (int i=0;i<messages.length();i++) {
            JSONArray line=messages.optJSONArray(i);
            if (line==null || line.length()!=2) continue;
            boolean fromTiva="tiva".equals(line.optString(0));
            TextView row=new TextView(activity);
            row.setText((fromTiva?"Tiva AI":"Customer")+"\n"+line.optString(1));
            row.setTextSize(14);
            row.setTextColor(fromTiva?(dark?0xFFA9CFFF:0xFF154B84):DriverThemeTokens.textPrimary(activity));
            row.setPadding(0,0,0,gap);
            row.setTextIsSelectable(true);
            transcript.addView(row);
        }
        ScrollView scroll=new ScrollView(activity);
        scroll.addView(transcript);
        PremiumDialogs.builder(activity)
                .setTitle("Detail pesanan · "+brief.optString("title"))
                .setView(scroll)
                .setPositiveButton("Tutup",null)
                .show();
    }
}
