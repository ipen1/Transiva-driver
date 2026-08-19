package com.transiva.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deteksi root/hooking berbasis skor agar satu indikasi ringan tidak memblokir perangkat normal. */
public final class RootSecurityGuard {
    public interface Callback { void onSafe(); void onBlocked(String reason); }
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final AtomicBoolean RUNNING=new AtomicBoolean();
    private static volatile AlertDialog ACTIVE_DIALOG;
    private RootSecurityGuard(){}
    public static void checkAsync(Context c, Callback cb){
        checkAsync(c, cb, false);
    }

    public static void checkAsync(Context c, Callback cb, boolean forcePolicyRefresh){
        if(c==null)return; Context app=c.getApplicationContext();
        if(!RUNNING.compareAndSet(false,true)){
            MAIN.postDelayed(()->checkAsync(app,cb,forcePolicyRefresh),250);
            return;
        }
        DriverNetworkExecutor.execute(() -> {
            DriverSecurityPolicy.Policy policy = forcePolicyRefresh
                    ? DriverSecurityPolicy.resolveFresh(app)
                    : DriverSecurityPolicy.resolve(app);
            final Result result = policy.rootEnabled ? safeDetect(app) : new Result(false, "");
            RUNNING.set(false);
            MAIN.post(() -> {
                if (cb != null) {
                    if (result.blocked) cb.onBlocked(result.reason);
                    else cb.onSafe();
                }
            });
        });
    }

    public static void enforce(Activity a){
        if(a==null||a.isFinishing()||a.isDestroyed())return;
        checkAsync(a,new Callback(){
            public void onSafe(){ dismiss(); }
            public void onBlocked(String r){ show(a,r); }
        });
    }

    public static void enforceFresh(Activity a){
        if(a==null||a.isFinishing()||a.isDestroyed())return;
        checkAsync(a,new Callback(){
            public void onSafe(){ dismiss(); }
            public void onBlocked(String r){ show(a,r); }
        }, true);
    }
    private static Result safeDetect(Context context) {
        try {
            return detect(context);
        } catch (Throwable ignored) {
            // Gagal memeriksa bukan alasan untuk memblokir perangkat normal.
            return new Result(false, "");
        }
    }
    private static Result detect(Context c){ int score=0; StringBuilder why=new StringBuilder();
        String tags=Build.TAGS==null?"":Build.TAGS; if(tags.contains("test-keys")){score+=1;why.append("test-keys, ");}
        String[] paths={"/system/bin/su","/system/xbin/su","/sbin/su","/data/adb/magisk","/data/adb/ksu","/data/adb/ap","/system/framework/XposedBridge.jar"};
        for(String p:paths)if(new File(p).exists()){score+=3;why.append(p).append(", ");}
        String[] pkgs={"com.topjohnwu.magisk","me.weishu.kernelsu","me.bmax.apatch","org.lsposed.manager","de.robv.android.xposed.installer"};
        PackageManager pm=c.getPackageManager(); for(String p:pkgs)try{pm.getPackageInfo(p,0);score+=1;why.append(p).append(", ");}catch(Throwable ignored){}
        try{Process x=Runtime.getRuntime().exec(new String[]{"sh","-c","command -v su"}); BufferedReader br=new BufferedReader(new InputStreamReader(x.getInputStream())); if(br.readLine()!=null){score+=4;why.append("su executable, ");} br.close();}catch(Throwable ignored){}
        try{BufferedReader br=new BufferedReader(new InputStreamReader(new java.io.FileInputStream("/proc/self/maps")));String line;while((line=br.readLine())!=null){String l=line.toLowerCase();if(l.contains("frida")||l.contains("xposed")||l.contains("zygisk")||l.contains("substrate")){score+=4;why.append("hook framework, ");break;}}br.close();}catch(Throwable ignored){}
        return score>=5?new Result(true,"Perangkat terindikasi dimodifikasi (skor "+score+"). "+why):new Result(false,"");
    }
    private static void dismiss(){
        MAIN.post(() -> {
            try {
                AlertDialog d = ACTIVE_DIALOG;
                ACTIVE_DIALOG = null;
                if(d != null && d.isShowing()) d.dismiss();
            } catch(Throwable ignored){}
        });
    }

    private static void show(Activity a,String reason){
        try{
            if(ACTIVE_DIALOG != null && ACTIVE_DIALOG.isShowing()) return;
            AlertDialog d = new AlertDialog.Builder(a)
                    .setTitle("Perangkat tidak aman")
                    .setMessage(reason+"\n\nDemi keamanan order dan saldo, aplikasi Driver tidak dapat dijalankan pada perangkat yang di-root atau memakai framework hooking.")
                    .setCancelable(false)
                    .setPositiveButton("Tutup aplikasi",(dialog,w)->a.finishAffinity())
                    .create();
            d.setOnDismissListener(x -> {
                if(ACTIVE_DIALOG == d) ACTIVE_DIALOG = null;
            });
            ACTIVE_DIALOG = d;
            d.show();
        }catch(Throwable e){
            a.finishAffinity();
        }
    }
    private static final class Result{final boolean blocked;final String reason;Result(boolean b,String r){blocked=b;reason=r;}}
}
