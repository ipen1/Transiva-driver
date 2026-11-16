package com.transiva.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class DriverDuitkuPaymentActivity extends Activity {
    private WebView web;

    @Override @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String url = getIntent().getStringExtra("payment_url");
        if (url == null || !isDuitku(Uri.parse(url))) {
            finish();
            return;
        }

        DriverSystemBars.setStatusBarColor(this, Color.parseColor("#0B3A78"));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setFitsSystemWindows(true);
        page.setBackgroundColor(DriverThemeTokens.background(this));
        TextView title = new TextView(this);
        title.setText("Deposit Driver · Duitku QRIS");
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.WHITE);
        title.setBackgroundColor(Color.parseColor("#0B3A78"));
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(16), 0, dp(16), 0);
        page.addView(title, new LinearLayout.LayoutParams(-1, dp(54)));

        web = new WebView(this);
        page.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        Button check = new Button(this);
        check.setAllCaps(false);
        check.setText("Kembali dan cek saldo");
        check.setOnClickListener(view -> returnToDeposit());
        page.addView(check, new LinearLayout.LayoutParams(-1, dp(48)));
        setContentView(page);
        DriverAppSettings.apply(this);

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return intercept(request.getUrl(), request.isForMainFrame());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return intercept(Uri.parse(url), true);
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (isReturn(Uri.parse(url))) returnToDeposit();
            }
        });
        web.loadUrl(url);
    }

    private boolean intercept(Uri url, boolean mainFrame) {
        if (mainFrame && isReturn(url)) {
            returnToDeposit();
            return true;
        }
        return !isDuitku(url);
    }

    private static boolean isTrustedHttps(Uri uri) {
        return "https".equals(uri.getScheme()) && uri.getUserInfo() == null && uri.getPort() == -1;
    }

    private static boolean isReturn(Uri url) {
        return isTrustedHttps(url) && "transiva.my.id".equalsIgnoreCase(url.getHost())
                && "/server/driver_duitku_callback.php".equals(url.getPath());
    }

    private static boolean isDuitku(Uri url) {
        if (!isTrustedHttps(url)) return false;
        String host = url.getHost();
        return "sandbox.duitku.com".equalsIgnoreCase(host) || "app-sandbox.duitku.com".equalsIgnoreCase(host)
                || "api-sandbox.duitku.com".equalsIgnoreCase(host) || "passport.duitku.com".equalsIgnoreCase(host)
                || "app.duitku.com".equalsIgnoreCase(host) || "api.duitku.com".equalsIgnoreCase(host);
    }

    private void returnToDeposit() {
        if (isFinishing()) return;
        setResult(RESULT_OK);
        finish();
    }

    @Override public void onDestroy() {
        if (web != null) {
            web.stopLoading();
            if (web.getParent() instanceof LinearLayout) ((LinearLayout) web.getParent()).removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
}
