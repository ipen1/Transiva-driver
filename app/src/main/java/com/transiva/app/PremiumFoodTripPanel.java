package com.transiva.app;

import android.view.*;
import android.widget.*;
import org.json.JSONObject;

/** Small food summary above the shared action dock, leaving the map unobstructed. */
final class PremiumFoodTripPanel {
    private final DriverTripActivity host;
    private final FrameLayout page;
    private final LinearLayout dock;
    private final TextView kitchen;
    PremiumFoodTripPanel(DriverTripActivity host,FrameLayout page,LinearLayout dock){
        this.host=host;this.page=page;this.dock=dock;
        kitchen=host.text("",12,"#047857",true);
        kitchen.setGravity(Gravity.CENTER);kitchen.setMaxLines(2);
        kitchen.setPadding(host.dp(12),host.dp(7),host.dp(12),host.dp(7));
        kitchen.setBackground(host.round("#DCFCE7",host.dp(22)));kitchen.setElevation(host.dp(3));
        kitchen.setOnClickListener(v->host.showPremiumDetails());
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
        p.setMargins(host.dp(64),0,host.dp(64),host.dp(210));page.addView(kitchen,p);
        dock.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
            FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)kitchen.getLayoutParams();
            int bottom=page.getHeight()-t+host.dp(7);
            if(lp.bottomMargin!=bottom){lp.bottomMargin=bottom;kitchen.setLayoutParams(lp);}
        });
    }
    void bind(){
        JSONObject order=host.order,food=FoodTripPresentation.food(order);
        LinearLayout summary=new LinearLayout(host);summary.setOrientation(LinearLayout.VERTICAL);
        summary.setPadding(host.dp(5),0,host.dp(5),host.dp(7));
        LinearLayout line=new LinearLayout(host);line.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=host.text(FoodTripPresentation.items(order),14,"#0B3A78",true);
        title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        line.addView(title,new LinearLayout.LayoutParams(0,host.dp(40),1));title.setGravity(Gravity.CENTER_VERTICAL);
        TextView details=host.text("Lihat detail ›",12,"#005DDE",true);details.setGravity(Gravity.CENTER);
        details.setPadding(host.dp(6),0,host.dp(3),0);line.addView(details,new LinearLayout.LayoutParams(-2,host.dp(44)));
        summary.addView(line);line.setOnClickListener(v->host.showPremiumDetails());
        String payment=FoodTripPresentation.payment(order);
        LinearLayout paymentRow=new LinearLayout(host);paymentRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView pay=host.text("Tunai".equals(payment)?"Tagih pelanggan "+FoodTripPresentation.money(FoodTripPresentation.total(order)):"Rp0 ditagih",14,"#0B3A78",true);
        pay.setMaxLines(2);paymentRow.addView(pay,new LinearLayout.LayoutParams(0,-2,1));
        TextView badge=host.text(payment,11,"Tunai".equals(payment)?"#92400E":"#0B3A78",true);
        badge.setPadding(host.dp(7),host.dp(4),host.dp(7),host.dp(4));
        badge.setBackground(host.round("Tunai".equals(payment)?"#FEF3C7":DriverThemeTokens.isDark(host)?"#17263A":"#DBEAFE",host.dp(12)));
        paymentRow.addView(badge);summary.addView(paymentRow);
        TextView cost=host.text("Makanan "+FoodTripPresentation.money(food.optDouble("food_total"))+" · Ongkir "+FoodTripPresentation.money(food.optDouble("delivery_fee",order.optDouble("price"))),11,"#64748B",false);
        cost.setMaxLines(2);cost.setPadding(0,host.dp(3),0,host.dp(5));summary.addView(cost);
        View rule=new View(host);rule.setBackgroundColor(DriverThemeTokens.border(host));summary.addView(rule,new LinearLayout.LayoutParams(-1,host.dp(1)));
        ScrollView summaryScroll=new ScrollView(host);summaryScroll.setFillViewport(false);
        summaryScroll.addView(summary,new ScrollView.LayoutParams(-1,-2));
        int width=Math.max(host.dp(220),host.getResources().getDisplayMetrics().widthPixels-host.dp(40));
        summary.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.AT_MOST),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        int cap=Math.max(host.dp(64),(int)(host.getResources().getDisplayMetrics().heightPixels*.16f));
        dock.addView(summaryScroll,0,new LinearLayout.LayoutParams(-1,Math.min(cap,summary.getMeasuredHeight())));
        kitchen.setText("◷  "+FoodTripPresentation.kitchen(order));
        String state=DriverOrderStateMachine.normalize(order.optString("status"));
        kitchen.setVisibility(state.equals("taken")||state.equals("arrived_pickup")?View.VISIBLE:View.GONE);
        // Only the summary can scroll with larger fonts; action controls stay reachable.
        dock.setMinimumHeight(0);
    }
}
