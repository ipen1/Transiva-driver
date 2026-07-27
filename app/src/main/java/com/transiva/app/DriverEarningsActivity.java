package com.transiva.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.transiva.app.driver.ui.DriverBottomNavigation;

import java.text.NumberFormat;
import java.util.Locale;

public class DriverEarningsActivity extends Activity {

    private SessionManager session;
    private TextView balanceText;

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

    @Override
    protected void onResume() {
        super.onResume();

        if (balanceText != null) {
            balanceText.setText(
                    rupiah(
                            parseLong(
                                    session.getBalance()
                            )
                    )
            );
        }
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
                        "Pendapatan",
                        "Kelola saldo dan hasil kerja driver"
                )
        );

        LinearLayout wallet =
                new LinearLayout(this);

        wallet.setOrientation(
                LinearLayout.VERTICAL
        );

        wallet.setPadding(
                dp(18),
                dp(17),
                dp(18),
                dp(17)
        );

        wallet.setBackground(
                gradient(
                        "#075EF4",
                        "#22A4FF",
                        22
                )
        );

        wallet.setElevation(dp(3));

        wallet.addView(
                text(
                        "Saldo Driver",
                        13,
                        "#EAF4FF",
                        true
                )
        );

        balanceText =
                text(
                        rupiah(
                                parseLong(
                                        session.getBalance()
                                )
                        ),
                        29,
                        "#FFFFFF",
                        true
                );

        LinearLayout.LayoutParams balanceLp =
                new LinearLayout.LayoutParams(
                        -1,
                        -2
                );

        balanceLp.setMargins(
                0,
                dp(3),
                0,
                dp(13)
        );

        wallet.addView(
                balanceText,
                balanceLp
        );

        LinearLayout actions =
                new LinearLayout(this);

        Button deposit =
                whiteButton("Deposit");

        deposit.setOnClickListener(
                view -> startActivity(
                        new Intent(
                                this,
                                DriverTopUpActivity.class
                        )
                )
        );

        actions.addView(
                deposit,
                new LinearLayout.LayoutParams(
                        0,
                        dp(46),
                        1
                )
        );

        Button withdraw =
                whiteButton("Withdraw");

        withdraw.setOnClickListener(
                view -> startActivity(
                        new Intent(
                                this,
                                DriverWithdrawActivity.class
                        )
                )
        );

        LinearLayout.LayoutParams withdrawLp =
                new LinearLayout.LayoutParams(
                        0,
                        dp(46),
                        1
                );

        withdrawLp.setMargins(
                dp(8),
                0,
                0,
                0
        );

        actions.addView(
                withdraw,
                withdrawLp
        );

        wallet.addView(actions);

        LinearLayout.LayoutParams walletLp =
                new LinearLayout.LayoutParams(
                        -1,
                        -2
                );

        walletLp.setMargins(
                0,
                dp(14),
                0,
                dp(14)
        );

        content.addView(
                wallet,
                walletLp
        );

        LinearLayout stats =
                new LinearLayout(this);

        stats.setOrientation(
                LinearLayout.HORIZONTAL
        );

        stats.addView(
                statCard(
                        "Rp0",
                        "Hari ini"
                ),
                statLp(false)
        );

        stats.addView(
                statCard(
                        "Rp0",
                        "Minggu ini"
                ),
                statLp(true)
        );

        stats.addView(
                statCard(
                        "Rp0",
                        "Diproses"
                ),
                statLp(true)
        );

        LinearLayout.LayoutParams statsLp =
                new LinearLayout.LayoutParams(
                        -1,
                        -2
                );

        statsLp.setMargins(
                0,
                0,
                0,
                dp(14)
        );

        content.addView(
                stats,
                statsLp
        );

        LinearLayout history =
                card();

        history.addView(
                text(
                        "Mutasi Pendapatan",
                        16,
                        "#0B3A78",
                        true
                )
        );

        history.addView(
                text(
                        "Pendapatan order, komisi, deposit, dan withdraw akan ditampilkan pada tahap berikutnya.",
                        11,
                        "#718096",
                        false
                )
        );

        Button receipt =
                outlineButton(
                        "Lihat Riwayat Bukti"
                );

        receipt.setOnClickListener(
                view -> startActivity(
                        new Intent(
                                this,
                                DriverReceiptHistoryActivity.class
                        )
                )
        );

        LinearLayout.LayoutParams receiptLp =
                new LinearLayout.LayoutParams(
                        -1,
                        dp(46)
                );

        receiptLp.setMargins(
                0,
                dp(13),
                0,
                0
        );

        history.addView(
                receipt,
                receiptLp
        );

        content.addView(history);

        shell.addView(
                DriverBottomNavigation.build(
                        this,
                        DriverBottomNavigation.ActiveItem.EARNINGS
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

        TextView amount =
                text(
                        value,
                        13,
                        "#0B7CFF",
                        true
                );

        amount.setGravity(Gravity.CENTER);
        box.addView(amount);

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

    private Button whiteButton(
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

    private Button outlineButton(
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

    private GradientDrawable round(
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

    private GradientDrawable roundStroke(
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

    private GradientDrawable gradient(
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

    private String rupiah(
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

    private long parseLong(
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
