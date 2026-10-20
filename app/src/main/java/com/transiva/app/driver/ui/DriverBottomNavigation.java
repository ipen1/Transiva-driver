package com.transiva.app.driver.ui;

import android.app.Activity;
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
import com.transiva.app.DriverMessageUnreadRepository;
import com.transiva.app.DevicePerformanceProfile;
import com.transiva.app.DriverThemeTokens;

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

    public static int height(Activity activity){
        float font=Math.max(1f,activity.getResources().getConfiguration().fontScale);
        return dp(activity,Math.max(72,Math.round(52+14*font)));
    }
    public static View build(Activity activity, ActiveItem activeItem) {
        LinearLayout navigation = new LinearLayout(activity);
        navigation.setOrientation(LinearLayout.HORIZONTAL);
        navigation.setGravity(Gravity.CENTER);
        navigation.setPadding(dp(activity, 5), dp(activity, 4), dp(activity, 5), dp(activity, 4));
        navigation.setBackgroundColor(DriverThemeTokens.surface(activity));
        navigation.setElevation(dp(activity, 8));

        add(navigation, navItem(activity, "Beranda", "ic_nav_home",
                ActiveItem.HOME, activeItem, DriverDashboardActivity.class));
        add(navigation, navItem(activity, "Aktivitas", "ic_nav_activity",
                ActiveItem.ACTIVITY, activeItem, DriverActivityHistoryActivity.class));
        add(navigation, navItem(activity, "Pesan", "ic_nav_chat",
                ActiveItem.CHAT, activeItem, DriverChatActivity.class));
        add(navigation, navItem(activity, "Transaksi", "ic_nav_wallet",
                ActiveItem.EARNINGS, activeItem, DriverEarningsActivity.class));
        add(navigation, navItem(activity, "Akun", "ic_nav_profile",
                ActiveItem.PROFILE, activeItem, DriverProfileActivity.class));

        // LOW mode benar-benar menekan animasi UI untuk mengurangi render/GPU wakeups.
        DevicePerformanceProfile perf = DevicePerformanceProfile.get(activity);
        if (perf.reduceMapMotion) {
            navigation.setAlpha(1f);
            navigation.setTranslationY(0f);
        } else {
            navigation.setAlpha(0f);
            navigation.setTranslationY(dp(activity, 10));
            navigation.animate().alpha(1f).translationY(0f)
                    .setDuration(perf.targetFps >= 60 ? 220L : 150L)
                    .setInterpolator(new DecelerateInterpolator(1.8f)).start();
        }

        return navigation;
    }

    /** Refresh only the Pesan label without rebuilding the whole navigation bar. */
    public static void refreshUnread(Activity activity,View navigationView){
        if(activity==null||navigationView==null)return;
        TextView badge=navigationView.findViewWithTag("driver_nav_unread");
        if(badge!=null){int count=DriverMessageUnreadRepository.totalUnread(activity);badge.setText(count>99?"99+":String.valueOf(count));badge.setVisibility(count>0?View.VISIBLE:View.GONE);}
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
        root.setPadding(dp(activity, 2), dp(activity, 4), dp(activity, 2), dp(activity, 4));
        root.setMinimumHeight(dp(activity,64));
        root.setContentDescription(label);
        root.setClickable(!active);
        root.setFocusable(!active);

        if (active) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(DriverThemeTokens.color(activity, ACTIVE_BG));
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
        android.widget.FrameLayout iconBox=new android.widget.FrameLayout(activity);
        iconBox.addView(icon,new android.widget.FrameLayout.LayoutParams(dp(activity,24),dp(activity,24),Gravity.CENTER));
        if(item==ActiveItem.CHAT){
            TextView badge=new TextView(activity);badge.setTag("driver_nav_unread");badge.setTextSize(9);badge.setTextColor(android.graphics.Color.WHITE);badge.setGravity(Gravity.CENTER);
            GradientDrawable badgeBg=new GradientDrawable();badgeBg.setColor(android.graphics.Color.parseColor("#DC2626"));badgeBg.setCornerRadius(dp(activity,10));badge.setBackground(badgeBg);
            int count=DriverMessageUnreadRepository.totalUnread(activity);badge.setText(count>99?"99+":String.valueOf(count));badge.setVisibility(count>0?View.VISIBLE:View.GONE);
            iconBox.addView(badge,new android.widget.FrameLayout.LayoutParams(dp(activity,22),dp(activity,18),Gravity.TOP|Gravity.END));
        }
        root.addView(iconBox,new LinearLayout.LayoutParams(-1,dp(activity,30)));

        TextView title = new TextView(activity);
        title.setText(label);
        title.setTextSize(11f);
        title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER);
        title.setIncludeFontPadding(false);
        title.setTextColor(active ? DriverThemeTokens.accent(activity) : DriverThemeTokens.textSecondary(activity));
        title.setTypeface(Typeface.DEFAULT, active ? Typeface.BOLD : Typeface.NORMAL);

        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.setMargins(0, dp(activity, 2), 0, 0);
        root.addView(title, titleParams);

        if (!active) {
            root.setOnClickListener(view -> {
                if (DevicePerformanceProfile.get(activity).reduceMapMotion) {
                    DriverPageTransition.open(activity, target, pageIndex(activeItem), pageIndex(item));
                    return;
                }
                view.animate().scaleX(0.90f).scaleY(0.90f).setDuration(70L)
                        .withEndAction(() -> view.animate().scaleX(1f).scaleY(1f).setDuration(110L)
                                .setInterpolator(new DecelerateInterpolator(1.7f))
                                .withEndAction(() -> DriverPageTransition.open(activity, target, pageIndex(activeItem), pageIndex(item)))
                                .start()).start();
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
