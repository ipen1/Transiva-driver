package com.transiva.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.transiva.app.driver.ui.DriverBottomNavigation;
import com.transiva.app.driver.data.DriverDashboardRepositoryImpl;
import com.transiva.app.driver.domain.DriverDashboardRepository;
import com.transiva.app.driver.domain.DriverDashboardState;

import java.text.NumberFormat;
import java.util.Locale;
abstract class DriverEarningsActivityLayer1 extends Activity {

    protected static final long REFRESH_INTERVAL_MS = 30000L;

    protected SessionManager session;
    protected TextView balanceText;
    protected TextView todayEarningText;
    protected TextView pendingDepositText;
    protected TextView pendingWithdrawText;
    protected DriverDashboardRepository dashboardRepository;
    protected final Handler realtimeHandler = new Handler(Looper.getMainLooper());
    protected boolean refreshInFlight = false;
    protected boolean screenVisible = false;

    protected final Runnable realtimeRefresh = new Runnable() {
        @Override
        public void run() {
            if (!screenVisible) return;
            loadRealtimeWallet();
            realtimeHandler.postDelayed(this, WaveLoadGuard.jitter(DriverPollingCoordinator.interval(DriverEarningsActivityLayer1.this, REFRESH_INTERVAL_MS)));
        }
    };

    protected View header(
            String title,
            String subtitle
    ) {
        LinearLayout box =
                new LinearLayout(this);

        box.setOrientation(
                LinearLayout.VERTICAL
        );

        box.addView(
                text(
                        title,
                        24,
                        "#0B3A78",
                        true
                )
        );

        box.addView(
                text(
                        subtitle,
                        11,
                        "#718096",
                        false
                )
        );

        return box;
    }

    protected View statCard(
            String value,
            String label,
            int slot
    ) {
        LinearLayout box =
                card();

        box.setGravity(Gravity.CENTER);
        box.setPadding(
                dp(8),
                dp(13),
                dp(8),
                dp(13)
        );

        TextView amount =
                text(
                        value,
                        13,
                        "#0B7CFF",
                        true
                );

        amount.setGravity(Gravity.CENTER);
        box.addView(amount);

        if (slot == 0) todayEarningText = amount;
        else if (slot == 1) pendingDepositText = amount;
        else if (slot == 2) pendingWithdrawText = amount;

        TextView caption =
                text(
                        label,
                        9,
                        "#64748B",
                        false
                );

        caption.setGravity(Gravity.CENTER);
        box.addView(caption);

        return box;
    }

    protected LinearLayout.LayoutParams statLp(
            boolean margin
    ) {
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(
                        0,
                        dp(74),
                        1
                );

        if (margin) {
            lp.setMargins(
                    dp(7),
                    0,
                    0,
                    0
            );
        }

        return lp;
    }

    protected LinearLayout card() {
        LinearLayout box =
                new LinearLayout(this);

        box.setOrientation(
                LinearLayout.VERTICAL
        );

        box.setPadding(
                dp(15),
                dp(15),
                dp(15),
                dp(15)
        );

        box.setBackground(
                roundStroke(
                        "#FFFFFF",
                        "#E1EAF5",
                        18,
                        1
                )
        );

        box.setElevation(dp(1));
        return box;
    }

    protected Button whiteButton(
            String value
    ) {
        Button button =
                new Button(this);

        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(12);

        button.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
        );

        button.setTextColor(
                Color.parseColor("#0B7CFF")
        );

        button.setBackground(
                round(
                        "#FFFFFF",
                        14
                )
        );

        return button;
    }

    protected Button outlineButton(
            String value
    ) {
        Button button =
                whiteButton(value);

        button.setBackground(
                roundStroke(
                        "#FFFFFF",
                        "#A9D1FF",
                        14,
                        1
                )
        );

        return button;
    }

    protected TextView text(
            String value,
            int size,
            String color,
            boolean bold
    ) {
        TextView view =
                new TextView(this);

        view.setText(value);
        view.setTextSize(size);

        view.setTextColor(
                Color.parseColor(color)
        );

        view.setIncludeFontPadding(false);

        if (bold) {
            view.setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD
            );
        }

        return view;
    }

    protected GradientDrawable round(
            String fill,
            int radius
    ) {
        GradientDrawable drawable =
                new GradientDrawable();

        drawable.setColor(
                Color.parseColor(fill)
        );

        drawable.setCornerRadius(
                dp(radius)
        );

        return drawable;
    }

    protected GradientDrawable roundStroke(
            String fill,
            String stroke,
            int radius,
            int width
    ) {
        GradientDrawable drawable =
                round(
                        fill,
                        radius
                );

        drawable.setStroke(
                dp(width),
                Color.parseColor(stroke)
        );

        return drawable;
    }

    protected GradientDrawable gradient(
            String start,
            String end,
            int radius
    ) {
        GradientDrawable drawable =
                new GradientDrawable(
                        GradientDrawable
                                .Orientation
                                .LEFT_RIGHT,
                        new int[]{
                                Color.parseColor(start),
                                Color.parseColor(end)
                        }
                );

        drawable.setCornerRadius(
                dp(radius)
        );

        return drawable;
    }

    protected String rupiah(
            long amount
    ) {
        NumberFormat format =
                NumberFormat.getCurrencyInstance(
                        new Locale(
                                "id",
                                "ID"
                        )
                );

        format.setMaximumFractionDigits(0);
        format.setMinimumFractionDigits(0);

        return format.format(amount);
    }

    protected long parseLong(
            String value
    ) {
        try {
            return Long.parseLong(
                    clean(value)
                            .replaceAll(
                                    "[^0-9-]",
                                    ""
                            )
            );
        } catch (Exception ignored) {
            return 0L;
        }
    }

    protected String clean(
            String value
    ) {
        if (value == null) {
            return "";
        }

        value = value.trim();

        return "null".equalsIgnoreCase(value)
                ? ""
                : value;
    }

    protected int dp(
            int value
    ) {
        return Math.round(
                value
                        * getResources()
                        .getDisplayMetrics()
                        .density
        );
    }
    // Cross-layer contracts keep the split type-safe without duplicating state.
    protected abstract void renderCachedBalance();
    protected abstract void loadRealtimeWallet();
    protected abstract boolean validDriverSession();
    protected abstract void redirectLogin();
    protected abstract View buildScreen();

}
