package com.transiva.app;

import android.content.res.ColorStateList;
import android.widget.Button;

final class FoodTripButtons {
    static void decorate(DriverTripActivityLayer1 host,Button button,String role){
        if((!host.isFoodOrder()&&!DeliveryTripPresentation.special(host.order))||!host.premiumRideTrip())return;
        int icon="list".equals(role)?android.R.drawable.ic_menu_agenda:"navigation".equals(role)?R.drawable.ic_food_navigation:"merchant".equals(role)?R.drawable.ic_food_merchant:R.drawable.ic_food_chat;
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(icon,0,0,0);
        button.setCompoundDrawablePadding(host.dp(3));
        int color="navigation".equals(role)?android.graphics.Color.WHITE:"merchant".equals(role)&&DriverThemeTokens.isDark(host)?android.graphics.Color.parseColor("#86EFAC"):DriverThemeTokens.textPrimary(host);
        button.setTextColor(color);button.setCompoundDrawableTintList(ColorStateList.valueOf(color));
        button.setPadding(host.dp(7),0,host.dp(4),0);
        button.setTextSize(11);
    }
}
