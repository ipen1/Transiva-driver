package com.transiva.app;

import android.content.Intent;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.transiva.app.driver.domain.DriverDashboardState;
import com.transiva.app.driver.ui.DriverBottomNavigation;
import com.transiva.app.driver.ui.DriverPageTransition;

/** Full-screen companion to the map dashboard, using the same dashboard data and actions. */
public final class DriverMenuActivity extends DriverDashboardActivity {
    private TextView avatar;
    private Switch foregroundOffers;

    @Override protected View buildScreen() {
        page = new FrameLayout(this);
        page.setBackgroundColor(DriverThemeTokens.background(this));
        LinearLayout screen = column();
        page.addView(screen, new FrameLayout.LayoutParams(-1, -1));
        screen.addView(toolbar(), new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        content = column();
        content.setPadding(dp(13), dp(12), dp(13), dp(12));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        buildProfile();
        homeSections = column();
        content.addView(homeSections);
        buildStatusAndEmergency();
        onlineLabel.setTextSize(17);
        readinessText.setTextSize(11);
        sosButton.setTextSize(16);
        buildWalletAndPerformance();

        LinearLayout shortcuts = card();
        shortcuts.setPadding(dp(12), 0, dp(12), 0);
        shortcuts.addView(shortcut("⌁", "Peninjauan Perjalanan", "", () -> DriverOfflineOrderCases.open(this)));
        shortcuts.addView(divider(DriverThemeTokens.divider(this)));
        shortcuts.addView(shortcut("⌾", "Lokasi Driver", "Driver online & idle · radius 20 km",
                () -> startActivity(new Intent(this, DriverLocationActivity.class))));
        add(homeSections, shortcuts, 0, dp(9), 0, 0);

        buildSmartAssistant();
        buildDriverGrowth();
        combineInsights();

        LinearLayout preferences = card();
        preferences.setPadding(dp(12), dp(3), dp(12), dp(3));
        foregroundOffers = DashboardOfferForeground.setting(this);
        foregroundOffers.setTextSize(13);
        preferences.addView(foregroundOffers, new LinearLayout.LayoutParams(-1, -2));
        add(homeSections, preferences, 0, dp(9), 0, 0);

        // Keep current trip access on this page even when returning from the map.
        orderSections = column();
        orderSections.setVisibility(View.GONE);
        buildOrderSections();
        offerBox.setVisibility(View.GONE);
        add(homeSections, orderSections, 0, dp(8), 0, 0);

        bottomNavigationView = DriverBottomNavigation.buildMenu(this);
        screen.addView(bottomNavigationView, new LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT));
        loading = new ProgressBar(this);
        loading.setVisibility(View.GONE);
        page.addView(loading, new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER));
        return page;
    }

    @Override protected void onResume() {
        super.onResume();
        if (foregroundOffers != null) DashboardOfferForeground.refreshSetting(this, foregroundOffers);
    }

    @Override public void showDashboard(DriverDashboardState state) {
        super.showDashboard(state);
        updateAvatar(nameText.getText().toString());
        onlineLabel.setText(state.online ? "● Online" : "● Offline");
        hotspotText.setTextColor(DriverThemeTokens.textSecondary(this));
        clusterCurrentText.setTextColor(DriverThemeTokens.textSecondary(this));
        growthScoreText.setTextColor(DriverThemeTokens.textSecondary(this));
        boolean active = state.activeOrders != null && !state.activeOrders.isEmpty();
        boolean offers = state.online && state.offers != null && !state.offers.isEmpty();
        orderSections.setVisibility(active || offers ? View.VISIBLE : View.GONE);
        offerBox.setVisibility(offers ? View.VISIBLE : View.GONE);
    }

    private View toolbar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(DriverThemeTokens.surface(this));
        bar.setElevation(dp(2));
        TextView back = tool("‹", "Kembali ke peta", this::finish);
        bar.addView(back, new LinearLayout.LayoutParams(dp(49), dp(54)));
        bar.addView(text("Menu Driver", 21, "#0B3A78", true), new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(tool("⚙", "Pengaturan Driver", () -> startActivity(new Intent(this, DriverSettingsActivity.class))),
                new LinearLayout.LayoutParams(dp(49), dp(54)));
        return bar;
    }

    private TextView tool(String icon, String description, Runnable action) {
        TextView button = text(icon, 27, "#0B3A78", true);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(description);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private void buildProfile() {
        LinearLayout profile = new LinearLayout(this);
        profile.setGravity(Gravity.CENTER_VERTICAL);
        avatar = text("", 24, "#FFFFFF", true);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(gradient("#005AD8", "#188EFF", dp(28)));
        profile.addView(avatar, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout copy = column();
        copy.setPadding(dp(11), 0, 0, 0);
        nameText = text(first(session.getName(), session.getUsername(), "Driver"), 22, "#0B3A78", true);
        copy.addView(nameText);
        updateAvatar(nameText.getText().toString());
        verificationText = text("Memeriksa akun…", 11, "#475569", true);
        verificationText.setPadding(dp(8), dp(3), dp(8), dp(3));
        add(copy, verificationText, 0, dp(3), 0, 0);
        verificationText.getLayoutParams().width = -2;
        profile.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(profile);
        lastUpdateText = new TextView(this);
    }

    private void updateAvatar(String name) {
        String value = name.trim();
        avatar.setText(value.isEmpty() ? "D" : value.substring(0, value.offsetByCodePoints(0, 1))
                .toUpperCase(java.util.Locale.ROOT));
    }

    @Override protected void buildWalletAndPerformance() {
        wallet = column();
        wallet.setPadding(dp(14), dp(11), dp(14), dp(11));
        wallet.setBackground(gradient("#004ECE", "#0084EE", dp(20)));
        wallet.addView(walletText("Saldo Driver", 13, true));

        LinearLayout headline = new LinearLayout(this);
        headline.setGravity(Gravity.CENTER_VERTICAL);
        balanceText = walletText("Rp0", 30, true);
        headline.addView(balanceText, new LinearLayout.LayoutParams(0, -2, 2));
        TextView manage = walletText("Kelola saldo ›", 12, true);
        manage.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        headline.addView(manage, new LinearLayout.LayoutParams(0, -2, 1));
        wallet.addView(headline);
        View walletDivider = divider(0x66FFFFFF);
        LinearLayout.LayoutParams dividerParams = (LinearLayout.LayoutParams) walletDivider.getLayoutParams();
        dividerParams.setMargins(0, dp(6), 0, dp(6));
        wallet.addView(walletDivider);
        LinearLayout stats = new LinearLayout(this);
        earningText = walletMetric(stats, "Pendapatan hari ini", "Rp0");
        tripText = walletMetric(stats, "Order selesai", "0");
        wallet.addView(stats);
        wallet.setOnClickListener(v -> DriverPageTransition.open(this, DriverEarningsActivity.class,
                DriverPageTransition.HOME, DriverPageTransition.EARNINGS));
        add(homeSections, wallet, 0, dp(9), 0, 0);
        ratingText = new TextView(this);
        onlineMinutesText = new TextView(this);
        distanceText = new TextView(this);
    }

    private TextView walletText(String value, int size, boolean bold) {
        TextView view = text(value, size, "#FFFFFF", bold);
        view.setTextColor(Color.WHITE);
        return view;
    }

    private TextView walletMetric(LinearLayout row, String label, String value) {
        LinearLayout metric = column();
        metric.addView(walletText(label, 11, false));
        TextView number = walletText(value, 17, true);
        metric.addView(number);
        row.addView(metric, new LinearLayout.LayoutParams(0, -2, 1));
        return number;
    }

    private View shortcut(String icon, String title, String detail, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(53));
        TextView symbol = text(icon, 26, "#0B7CFF", true);
        symbol.setGravity(Gravity.CENTER);
        row.addView(symbol, new LinearLayout.LayoutParams(dp(34), -2));
        LinearLayout copy = column();
        copy.setPadding(dp(5), dp(6), dp(5), dp(6));
        copy.addView(text(title, 14, "#0B3A78", true));
        if (!detail.isEmpty()) copy.addView(text(detail, 11, "#64748B", false));
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(text("›", 23, "#0B3A78", true));
        row.setOnClickListener(v -> action.run());
        return row;
    }

    private void combineInsights() {
        int index = homeSections.getChildCount() - 2;
        LinearLayout smart = (LinearLayout) homeSections.getChildAt(index);
        LinearLayout growth = (LinearLayout) homeSections.getChildAt(index + 1);
        homeSections.removeView(growth);
        homeSections.removeView(smart);
        LinearLayout insights = card();
        insights.setPadding(dp(12), 0, dp(12), 0);
        for (LinearLayout row : new LinearLayout[]{smart, growth}) {
            row.setBackground(null);
            row.setPadding(0, dp(9), 0, dp(9));
            if (insights.getChildCount() > 0) insights.addView(divider(DriverThemeTokens.divider(this)));
            insights.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        for (TextView detail : new TextView[]{hotspotText, clusterCurrentText, growthScoreText}) {
            detail.setTextSize(11);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) detail.getLayoutParams();
            lp.topMargin = dp(3);
        }
        add(homeSections, insights, 0, dp(9), 0, 0);
    }

    private View divider(int color) {
        View line = new View(this);
        line.setBackgroundColor(color);
        line.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1)));
        return line;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }
}
