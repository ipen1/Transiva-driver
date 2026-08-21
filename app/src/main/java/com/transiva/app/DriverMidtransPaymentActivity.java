package com.transiva.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class DriverMidtransPaymentActivity extends Activity {
    private WebView web;
    @Override protected void onCreate(Bundle b){ super.onCreate(b); String url=getIntent().getStringExtra("payment_url"); if(url==null||!(url.startsWith("https://app.sandbox.midtrans.com/")||url.startsWith("https://app.midtrans.com/"))){setResult(RESULT_CANCELED);finish();return;} try{getWindow().setStatusBarColor(Color.WHITE);}catch(Exception ignored){} web=new WebView(this);setContentView(web); WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);s.setUserAgentString(s.getUserAgentString()+" TransivaDriver/1.0");CookieManager.getInstance().setAcceptCookie(true); web.setWebChromeClient(new WebChromeClient());web.setWebViewClient(new WebViewClient(){@Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){String u=r.getUrl().toString();if(isFinish(u)){setResult(RESULT_OK);finish();return true;}return false;}@Override public void onPageFinished(WebView v,String u){super.onPageFinished(v,u);if(isFinish(u)){setResult(RESULT_OK);finish();}}}); web.loadUrl(url); }
    private boolean isFinish(String u){return u!=null&&u.startsWith("https://transiva.my.id/server/midtrans_driver_finish.php");}
    @Override public void onBackPressed(){if(web!=null&&web.canGoBack())web.goBack();else{setResult(RESULT_CANCELED);super.onBackPressed();}}
    @Override protected void onDestroy(){if(web!=null){web.stopLoading();web.loadUrl("about:blank");web.clearHistory();web.removeAllViews();web.destroy();web=null;}super.onDestroy();}
}
