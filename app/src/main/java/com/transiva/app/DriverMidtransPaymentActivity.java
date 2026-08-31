package com.transiva.app;

import android.annotation.SuppressLint;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

public class DriverMidtransPaymentActivity extends Activity {
    private WebView web;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        String url=getIntent().getStringExtra("payment_url");
        if(url==null||!(url.startsWith("https://app.sandbox.midtrans.com/")||url.startsWith("https://app.midtrans.com/"))){ setResult(RESULT_CANCELED); finish(); return; }
        try{getWindow().setStatusBarColor(Color.parseColor("#0B3A78"));getWindow().setNavigationBarColor(Color.WHITE);}catch(Exception ignored){}

        LinearLayout page=new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setBackgroundColor(Color.WHITE);
        LinearLayout bar=new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(dp(12),dp(8),dp(12),dp(8)); bar.setBackgroundColor(Color.parseColor("#0B3A78"));
        TextView back=new TextView(this); back.setText("‹"); back.setTextSize(34); back.setGravity(Gravity.CENTER); back.setTextColor(Color.WHITE); back.setOnClickListener(v->closeToDeposit()); bar.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));
        LinearLayout titleBox=new LinearLayout(this); titleBox.setOrientation(LinearLayout.VERTICAL); titleBox.setPadding(dp(4),0,0,0); bar.addView(titleBox,new LinearLayout.LayoutParams(0,-2,1));
        TextView title=new TextView(this); title.setText("Pembayaran Deposit"); title.setTextSize(18); title.setTypeface(Typeface.DEFAULT_BOLD); title.setTextColor(Color.WHITE); titleBox.addView(title);
        TextView sub=new TextView(this); sub.setText("Midtrans • transaksi tersimpan otomatis"); sub.setTextSize(11); sub.setTextColor(Color.parseColor("#DDEBFF")); titleBox.addView(sub);
        page.addView(bar,new LinearLayout.LayoutParams(-1,-2));

        web=new WebView(this); page.addView(web,new LinearLayout.LayoutParams(-1,0,1)); setContentView(page); DriverAppSettings.apply(this);
        WebSettings s=web.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setAllowFileAccess(false); s.setAllowContentAccess(false); s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW); s.setUserAgentString(s.getUserAgentString()+" TransivaDriver/1.0");
        CookieManager.getInstance().setAcceptCookie(true);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r){ return handleUrl(v,r.getUrl().toString()); }
            @Override public boolean shouldOverrideUrlLoading(WebView v,String u){ return handleUrl(v,u); }
            @Override public void onPageFinished(WebView v,String u){ super.onPageFinished(v,u); if(isFinish(u)){setResult(RESULT_OK);finish();} }
        });
        web.loadUrl(url);
    }

    private boolean handleUrl(WebView v,String u){
        if(isFinish(u)){ setResult(RESULT_OK); finish(); return true; }
        if(u==null)return false;
        if(u.startsWith("http://")||u.startsWith("https://"))return false;
        try{ startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u))); return true; }catch(Exception ignored){ return false; }
    }
    private boolean isFinish(String u){ return u!=null&&u.startsWith("https://transiva.my.id/server/midtrans_driver_finish.php"); }
    private void closeToDeposit(){ setResult(RESULT_CANCELED); finish(); }
    @Override @SuppressLint("MissingSuperCall")
    public void onBackPressed(){ closeToDeposit(); }
    @Override protected void onDestroy(){ if(web!=null){web.stopLoading();web.loadUrl("about:blank");web.clearHistory();web.removeAllViews();web.destroy();web=null;}super.onDestroy(); }
    private int dp(int v){return(int)(v*getResources().getDisplayMetrics().density+.5f);}
}
