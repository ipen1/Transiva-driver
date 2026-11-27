package com.transiva.app;

import android.app.Activity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;

final class TivaSimpelBrief {
    private TivaSimpelBrief() {}

    static void show(Activity activity, JSONObject brief, JSONObject route) {
        if (brief==null && route==null) return;
        float density=activity.getResources().getDisplayMetrics().density;
        int gap=Math.round(14*density);
        LinearLayout content=new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(gap,gap,gap,gap);
        String kind=brief==null?"":brief.optString("kind");
        String title=brief==null?"Trans Simpel":brief.optString("title","Trans Simpel");
        String detail=value(brief,"detail");
        String task="ride".equals(kind)?"Jemput dan antar pelanggan":
                "food".equals(kind)?"Ambil pesanan makanan":
                "send".equals(kind)?"Ambil dan antar barang":
                "shop".equals(kind)?"Belanja untuk pelanggan":"Ikuti titik jemput dan antar";
        section(activity,content,task,detail,gap);
        section(activity,content,"shop".equals(kind)?"Tempat belanja":"Titik jemput",address(route,brief,"pickup"),gap);
        section(activity,content,"Titik antar",address(route,brief,"delivery"),gap);
        String recipient=value(brief,"receiver_name");
        String phone=value(brief,"receiver_phone");
        if (!recipient.isEmpty() || !phone.isEmpty())
            section(activity,content,"Penerima",recipient+(recipient.isEmpty() || phone.isEmpty()?"":" · ")+phone,gap);
        String notes=value(route,"customer_note");
        if (!notes.isEmpty() && !notes.equals(detail)) section(activity,content,"Catatan pelanggan",notes,gap);
        ScrollView scroll=new ScrollView(activity);
        scroll.addView(content);
        PremiumDialogs.builder(activity).setTitle("Detail pesanan · "+title)
                .setView(scroll).setPositiveButton("Tutup",null).show();
    }

    private static String value(JSONObject object, String key) {
        return object==null || object.isNull(key)?"":object.optString(key).trim();
    }

    private static String address(JSONObject route, JSONObject brief, String kind) {
        JSONObject live=route==null?null:route.optJSONObject(kind);
        String label=value(live,"address");
        return !label.isEmpty() || brief==null?label:value(brief.optJSONObject(kind),"address");
    }

    private static void section(Activity activity, LinearLayout content,String title,String body,int gap) {
        TextView label=new TextView(activity);
        label.setText(title);
        label.setTextSize(14);
        label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        label.setTextColor(DriverThemeTokens.accent(activity));
        label.setPadding(0,0,0,body.isEmpty()?gap:gap/3);
        content.addView(label);
        if (body.isEmpty()) return;
        TextView copy=new TextView(activity);
        copy.setText(body);
        copy.setTextSize(15);
        copy.setTextIsSelectable(true);
        copy.setTextColor(DriverThemeTokens.textPrimary(activity));
        copy.setPadding(0,0,0,gap);
        content.addView(copy);
    }
}
