package com.transiva.app;
import android.view.*;
import android.widget.*;

final class PremiumParcelTripPanel {
    static void bind(DriverTripActivity host,LinearLayout dock){
        LinearLayout box=new LinearLayout(host);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(host.dp(5),0,host.dp(5),host.dp(6));
        LinearLayout row=new LinearLayout(host);row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=host.text(DeliveryTripPresentation.summary(host.order),15,"#0B3A78",true);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(title,new LinearLayout.LayoutParams(0,host.dp(42),1));title.setGravity(Gravity.CENTER_VERTICAL);
        TextView details=host.text(DeliveryTripPresentation.shop(host.order)?"Daftar belanja ›":"Detail ›",12,"#1677FF",true);details.setGravity(Gravity.CENTER);
        row.addView(details,new LinearLayout.LayoutParams(-2,host.dp(44)));row.setOnClickListener(v->host.showPremiumDetails());box.addView(row);
        String payment=FoodTripPresentation.payment(host.order);
        LinearLayout metrics=new LinearLayout(host);metrics.setGravity(Gravity.CENTER_VERTICAL);
        if(DeliveryTripPresentation.shop(host.order)){
            double ref=DeliveryTripPresentation.reference(host.order);
            LinearLayout column=new LinearLayout(host);column.setOrientation(LinearLayout.VERTICAL);
            column.addView(host.text("Referensi barang",11,"#64748B",false));
            column.addView(host.text(Double.isFinite(ref)?FoodTripPresentation.money(ref):"Lihat daftar",15,"#0B3A78",true));
            metrics.addView(column,new LinearLayout.LayoutParams(0,-2,1));
        }else{
            TextView note=host.text(FoodTripPresentation.first(DeliveryTripPresentation.note(host.order),"Lihat catatan paket"),12,"#64748B",false);note.setMaxLines(2);note.setEllipsize(android.text.TextUtils.TruncateAt.END);box.addView(note);
        }
        LinearLayout fee=new LinearLayout(host);fee.setOrientation(LinearLayout.VERTICAL);
        fee.addView(host.text("Ongkir",11,"#64748B",false));
        fee.addView(host.text(FoodTripPresentation.money(host.optDouble("price","fare","total")),15,"#0B3A78",true));
        metrics.addView(fee,new LinearLayout.LayoutParams(0,-2,1));
        TextView badge=host.text(payment,11,"Tunai".equals(payment)?"#92400E":"#0B3A78",true);
        badge.setPadding(host.dp(8),host.dp(5),host.dp(8),host.dp(5));
        badge.setBackground(host.round("Tunai".equals(payment)?"#FEF3C7":DriverThemeTokens.isDark(host)?"#17263A":"#DBEAFE",host.dp(14)));metrics.addView(badge);
        box.addView(metrics);
        TextView hint=host.text(DeliveryTripPresentation.shop(host.order)?"Harga barang mengikuti struk toko":"Tunai".equals(payment)?"Tagih pelanggan":"Rp0 ditagih pelanggan",11,"#64748B",false);hint.setPadding(0,host.dp(4),0,host.dp(3));box.addView(hint);
        ScrollView scroll=new ScrollView(host);scroll.addView(box,new ScrollView.LayoutParams(-1,-2));
        int width=Math.max(host.dp(220),host.getResources().getDisplayMetrics().widthPixels-host.dp(40));box.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.AT_MOST),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        dock.addView(scroll,0,new LinearLayout.LayoutParams(-1,Math.min(box.getMeasuredHeight(),Math.max(host.dp(70),(int)(host.getResources().getDisplayMetrics().heightPixels*.17f)))));
    }
}
