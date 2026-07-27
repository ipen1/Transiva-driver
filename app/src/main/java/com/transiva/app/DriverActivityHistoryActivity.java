package com.transiva.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.transiva.app.driver.ui.DriverBottomNavigation;

public class DriverActivityHistoryActivity extends Activity {

    private SessionManager session;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(
                Color.parseColor("#0B7CFF")
        );

        getWindow().setNavigationBarColor(
                Color.parseColor("#071426")
        );

        session = new SessionManager(this);

        if (!validDriverSession()) {
            redirectLogin();
            return;
        }

        setContentView(buildScreen());
        DriverAppSettings.apply(this);
    }

    private boolean validDriverSession() {
        return session != null
                && session.isLoggedIn()
                && "driver".equals(
                session.normalizeRole(
                        session.getRole()
                )
        )
                && !clean(
                session.getToken()
        ).isEmpty();
    }

    private void redirectLogin() {
        Intent intent =
                new Intent(
                        this,
                        LoginActivity.class
                );

        intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
        );

        startActivity(intent);
        finish();
    }

    private View buildScreen() {
        FrameLayout page =
                new FrameLayout(this);

        page.setBackgroundColor(
                Color.parseColor("#F6F9FE")
        );

        LinearLayout shell =
                new LinearLayout(this);

        shell.setOrientation(
                LinearLayout.VERTICAL
        );

        page.addView(
                shell,
                new FrameLayout.LayoutParams(
                        -1,
                        -1
                )
        );

        ScrollView scroll =
                new ScrollView(this);

        scroll.setFillViewport(true);

        shell.addView(
                scroll,
                new LinearLayout.LayoutParams(
                        -1,
                        0,
                        1
                )
        );

        LinearLayout content =
                new LinearLayout(this);

        content.setOrientation(
                LinearLayout.VERTICAL
        );

        content.setPadding(
                dp(14),
                dp(14),
                dp(14),
                dp(24)
        );

        scroll.addView(
                content,
                new ScrollView.LayoutParams(
                        -1,
                        -2
                )
        );

        content.addView(
                header(
                        "Aktivitas",
                        "Riwayat pekerjaan dan perjalanan driver"
                )
        );

        LinearLayout summary =
                new LinearLayout(this);

        summary.setOrientation(
                LinearLayout.HORIZONTAL
        );

        summary.addView(
                statCard(
                        "0",
                        "Berjalan"
                ),
                statLp(false)
        );

        summary.addView(
                statCard(
                        "0",
                        "Selesai"
                ),
                statLp(true)
        );

        summary.addView(
                statCard(
                        "0",
                        "Dibatalkan"
                ),
                statLp(true)
        );

        LinearLayout.LayoutParams summaryLp =
                new LinearLayout.LayoutParams(
                        -1,
                        -2
                );

        summaryLp.setMargins(
                0,
                dp(14),
                0,
                dp(14)
        );

        content.addView(
                summary,
                summaryLp
        );

        LinearLayout filter =
                card();

        filter.addView(
                text(
                        "Filter Aktivitas",
                        15,
                        "#0B3A78",
                        true
                )
        );

        filter.addView(
                text(
                        "Semua  •  Berjalan  •  Selesai  •  Dibatalkan",
                        11,
                        "#64748B",
                        false
                )
        );

        content.addView(
                filter,
                sectionLp()
        );

        LinearLayout empty =
                card();

        empty.setGravity(Gravity.CENTER);
        empty.setPadding(
                dp(18),
                dp(28),
                dp(18),
                dp(28)
        );

        TextView emptyTitle =
                text(
                        "Riwayat aktivitas driver",
                        15,
                        "#0B3A78",
                        true
                );

        emptyTitle.setGravity(Gravity.CENTER);
        empty.addView(emptyTitle);

        TextView emptyBody =
                text(
                        "Halaman ini sudah siap sebagai pusat aktivitas. Data order real akan dihubungkan pada tahap berikutnya.",
                        11,
                        "#718096",
                        false
                );

        emptyBody.setGravity(Gravity.CENTER);
        emptyBody.setPadding(
                0,
                dp(7),
                0,
                0
        );

        empty.addView(emptyBody);
        content.addView(empty);

        shell.addView(
                DriverBottomNavigation.build(
                        this,
                        DriverBottomNavigation.ActiveItem.ACTIVITY
                ),
                new LinearLayout.LayoutParams(
                        -1,
                        dp(66)
                )
        );

        return page;
    }

    private View header(
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

    private View statCard(
            String value,
            String label
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

        TextView number =
                text(
                        value,
                        18,
                        "#0B7CFF",
                        true
                );

        number.setGravity(Gravity.CENTER);
        box.addView(number);

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

    private LinearLayout.LayoutParams statLp(
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

    private LinearLayout card() {
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

    private LinearLayout.LayoutParams sectionLp() {
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(
                        -1,
                        -2
                );

        lp.setMargins(
                0,
                0,
                0,
                dp(14)
        );

        return lp;
    }

    private TextView text(
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

    private GradientDrawable roundStroke(
            String fill,
            String stroke,
            int radius,
            int width
    ) {
        GradientDrawable drawable =
                new GradientDrawable();

        drawable.setColor(
                Color.parseColor(fill)
        );

        drawable.setCornerRadius(
                dp(radius)
        );

        drawable.setStroke(
                dp(width),
                Color.parseColor(stroke)
        );

        return drawable;
    }

    private String clean(
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

    private int dp(
            int value
    ) {
        return Math.round(
                value
                        * getResources()
                        .getDisplayMetrics()
                        .density
        );
    }
}
