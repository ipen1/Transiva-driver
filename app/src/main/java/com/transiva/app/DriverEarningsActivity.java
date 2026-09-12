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
public class DriverEarningsActivity extends DriverEarningsActivityLayer1 {

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
        dashboardRepository = new DriverDashboardRepositoryImpl(session);

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
        screenVisible = true;

        // Tampilkan cache terlebih dahulu agar UI tidak kosong, kemudian langsung
        // sinkronkan dengan endpoint dashboard yang juga dipakai halaman utama.
        renderCachedBalance();
        realtimeHandler.removeCallbacks(realtimeRefresh);
        realtimeHandler.post(realtimeRefresh);
    }

    @Override
    protected void onPause() {
        screenVisible = false;
        realtimeHandler.removeCallbacks(realtimeRefresh);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        screenVisible = false;
        realtimeHandler.removeCallbacksAndMessages(null);
        if (dashboardRepository != null) dashboardRepository.destroy();
        super.onDestroy();
    }

    protected void renderCachedBalance() {
        if (balanceText != null) {
            balanceText.setText(rupiah(parseLong(session.getBalance())));
        }
    }

    protected void loadRealtimeWallet() {
        if (refreshInFlight || dashboardRepository == null || !screenVisible) return;
        refreshInFlight = true;

        dashboardRepository.loadDashboard(new DriverDashboardRepository.DashboardCallback() {
            @Override
            public void onSuccess(DriverDashboardState state) {
                runOnUiThread(() -> {
                    refreshInFlight = false;
                    if (!screenVisible || state == null) return;

                    balanceText.setText(rupiah(state.balance));
                    if (todayEarningText != null) todayEarningText.setText(rupiah(state.todayEarning));
                    if (pendingDepositText != null) pendingDepositText.setText(rupiah(state.pendingDeposit));
                    if (pendingWithdrawText != null) pendingWithdrawText.setText(rupiah(state.pendingWithdraw));

                    // Simpan saldo terbaru supaya halaman withdraw/profile yang masih
                    // membaca SessionManager juga langsung memperoleh angka terbaru.
                    session.put("balance", String.valueOf(state.balance));
                });
            }

            @Override
            public void onError(int httpCode, String code, String message) {
                runOnUiThread(() -> {
                    refreshInFlight = false;
                    if (httpCode == 401 || httpCode == 403
                            || "UNAUTHORIZED".equalsIgnoreCase(code)
                            || "SESSION_EXPIRED".equalsIgnoreCase(code)) {
                        redirectLogin();
                    }
                });
            }
        });
    }

    protected boolean validDriverSession() {
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

    protected void redirectLogin() {
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

    protected View buildScreen() {
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
                        "Transaksi",
                        "Saldo masuk, saldo keluar, dan fee aplikasi"
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

        Button transfer = whiteButton("Transfer");
        transfer.setOnClickListener(view -> startActivity(
                new Intent(this, DriverTransferActivity.class)
        ));
        LinearLayout.LayoutParams transferLp = new LinearLayout.LayoutParams(0, dp(46), 1);
        transferLp.setMargins(dp(8), 0, 0, 0);
        actions.addView(transfer, transferLp);

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
                        "Hari ini",
                        0
                ),
                statLp(false)
        );

        stats.addView(
                statCard(
                        "Rp0",
                        "Deposit pending",
                        1
                ),
                statLp(true)
        );

        stats.addView(
                statCard(
                        "Rp0",
                        "Withdraw pending",
                        2
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
                        "Riwayat Transaksi",
                        16,
                        "#0B3A78",
                        true
                )
        );

        history.addView(
                text(
                        "Lihat seluruh saldo masuk, saldo keluar, fee aplikasi, deposit, withdraw, dan transfer antar-driver.",
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
}
