package com.transiva.app.driver.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.transiva.app.DriverActivityHistoryActivity;
import com.transiva.app.DriverChatActivity;
import com.transiva.app.DriverDashboardActivity;
import com.transiva.app.DriverEarningsActivity;
import com.transiva.app.DriverProfileActivity;

/**
 * Satu-satunya sumber bottom navigation untuk seluruh halaman utama driver.
 * Visual, status aktif, animasi masuk, dan animasi tekan dikelola dari file ini.
 */
public final class DriverBottomNavigation {

    private static final String ACTIVE_BG = "#EAF4FF";
    private static final String ACTIVE_COLOR = "#0B7CFF";
    private static final String INACTIVE_COLOR = "#64748B";

    public enum ActiveItem {
        HOME,
        ACTIVITY,
        CHAT,
        EARNINGS,
        PROFILE
    }

    private DriverBottomNavigation() {
    }

    public static View build(Activity activity, ActiveItem activeItem) {
        LinearLayout navigation = new LinearLayout(activity);
        navigation.setOrientation(LinearLayout.HORIZONTAL);
        navigation.setGravity(Gravity.CENTER);
        navigation.setPadding(dp(activity, 5), dp(activity, 4), dp(activity, 5), dp(activity, 4));
        navigation.setBackgroundColor(Color.WHITE);
        navigation.setElevation(dp(activity, 8));

        add(navigation, navItem(activity, "Beranda", "ic_nav_home",
                ActiveItem.HOME, activeItem, DriverDashboardActivity.class));
        add(navigation, navItem(activity, "Aktivitas", "ic_nav_activity",
                ActiveItem.ACTIVITY, activeItem, DriverActivityHistoryActivity.class));
        add(navigation, navItem(activity, "Pesan", "ic_nav_chat",
                ActiveItem.CHAT, activeItem, DriverChatActivity.class));
        add(navigation, navItem(activity, "Pendapatan", "ic_nav_wallet",
                ActiveItem.EARNINGS, activeItem, DriverEarningsActivity.class));
        add(navigation, navItem(activity, "Akun", "ic_nav_profile",
                ActiveItem.PROFILE, activeItem, DriverProfileActivity.class));

        // Animasi masuk dibuat terpusat agar seluruh halaman driver konsisten.
        navigation.setAlpha(0f);
        navigation.setTranslationY(dp(activity, 10));
        navigation.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220L)
                .setInterpolator(new DecelerateInterpolator(1.8f))
                .start();

        return navigation;
    }

    private static void add(LinearLayout navigation, View item) {
        navigation.addView(item, new LinearLayout.LayoutParams(0, -1, 1f));
    }

    private static View navItem(
            Activity activity,
            String label,
            String iconName,
            ActiveItem item,
            ActiveItem activeItem,
            Class<?> target
    ) {
        boolean active = item == activeItem;

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(activity, 4), dp(activity, 4), dp(activity, 4), dp(activity, 4));
        root.setClickable(!active);
        root.setFocusable(!active);

        if (active) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor(ACTIVE_BG));
            bg.setCornerRadius(dp(activity, 18));
            root.setBackground(bg);
            root.setScaleX(1.02f);
            root.setScaleY(1.02f);
        }

        ImageView icon = new ImageView(activity);
        int drawableId = activity.getResources().getIdentifier(
                iconName, "drawable", activity.getPackageName());
        if (drawableId != 0) {
            icon.setImageResource(drawableId);
        } else {
            icon.setImageResource(android.R.drawable.ic_menu_help);
        }
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        icon.setAlpha(active ? 1f : 0.62f);
        root.addView(icon, new LinearLayout.LayoutParams(dp(activity, 22), dp(activity, 22)));

        TextView title = new TextView(activity);
        title.setText(label);
        title.setTextSize(9f);
        title.setGravity(Gravity.CENTER);
        title.setIncludeFontPadding(false);
        title.setTextColor(Color.parseColor(active ? ACTIVE_COLOR : INACTIVE_COLOR));
        title.setTypeface(Typeface.DEFAULT, active ? Typeface.BOLD : Typeface.NORMAL);

        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.setMargins(0, dp(activity, 2), 0, 0);
        root.addView(title, titleParams);

        if (!active) {
            root.setOnClickListener(view -> {
                // Efek tombol ditekan: mengecil cepat lalu memantul lembut sebelum pindah halaman.
                view.animate()
                        .scaleX(0.90f)
                        .scaleY(0.90f)
                        .setDuration(70L)
                        .withEndAction(() -> view.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(110L)
                                .setInterpolator(new DecelerateInterpolator(1.7f))
                                .withEndAction(() -> DriverPageTransition.open(
                                        activity,
                                        target,
                                        pageIndex(activeItem),
                                        pageIndex(item)
                                ))
                                .start())
                        .start();
            });
        }

        return root;
    }

    private static int pageIndex(ActiveItem item) {
        if (item == ActiveItem.ACTIVITY) return DriverPageTransition.ACTIVITY;
        if (item == ActiveItem.CHAT) return DriverPageTransition.CHAT;
        if (item == ActiveItem.EARNINGS) return DriverPageTransition.EARNINGS;
        if (item == ActiveItem.PROFILE) return DriverPageTransition.PROFILE;
        return DriverPageTransition.HOME;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
