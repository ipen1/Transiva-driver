package com.transiva.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Remote image loader with bounded memory/cache and sampled decoding for low-end devices. */
public final class RemoteImageLoader {
    private static volatile ExecutorService executor;
    private static volatile int executorWorkers = -1;
    private static final int CACHE_KB = Math.max(2048, Math.min(12288, (int)(Runtime.getRuntime().maxMemory()/1024L/12L)));
    private static final LruCache<String,Bitmap> CACHE=new LruCache<String,Bitmap>(CACHE_KB){
        @Override protected int sizeOf(String k,Bitmap b){return Math.max(1,b.getByteCount()/1024);}
    };
    private RemoteImageLoader(){}

    private static ExecutorService pool(ImageView v){
        int wanted=Math.max(1,DevicePerformanceProfile.get(v.getContext()).imageWorkerCount);
        ExecutorService e=executor;if(e!=null&&!e.isShutdown()&&executorWorkers==wanted)return e;
        synchronized(RemoteImageLoader.class){
            e=executor;
            if(e==null||e.isShutdown()||executorWorkers!=wanted){
                if(e!=null) try{e.shutdownNow();}catch(Throwable ignored){}
                executor=Executors.newFixedThreadPool(wanted); executorWorkers=wanted;
            }
            return executor;
        }
    }

    public static void loadCenterCrop(ImageView view,String imageUrl,int fallbackDrawable){
        if(view==null)return; String clean=imageUrl==null?"":imageUrl.trim();
        view.setScaleType(ImageView.ScaleType.CENTER_CROP); if(fallbackDrawable!=0)view.setImageResource(fallbackDrawable); if(clean.isEmpty())return;
        view.setTag(clean); Bitmap cached=CACHE.get(clean); if(cached!=null&&!cached.isRecycled()){view.setImageBitmap(cached);return;}
        pool(view).execute(()->load(view,clean));
    }

    private static void load(ImageView view,String clean){
        HttpURLConnection c=null;
        try{
            c=(HttpURLConnection)new URL(clean).openConnection(); c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setUseCaches(true);c.setRequestProperty("Accept","image/webp,image/*");
            int status=c.getResponseCode(); if(status<200||status>=300)return;
            BufferedInputStream in=new BufferedInputStream(c.getInputStream()); ByteArrayOutputStream out=new ByteArrayOutputStream(64*1024); byte[] buf=new byte[8192];int n,total=0; final int maxBytes=6*1024*1024;
            while((n=in.read(buf))>0){total+=n;if(total>maxBytes){in.close();return;}out.write(buf,0,n);}in.close();byte[] data=out.toByteArray();
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(data,0,data.length,bounds);
            int target=DevicePerformanceProfile.get(view.getContext()).imageMaxSidePx;int sample=1;while(bounds.outWidth/sample>target*2||bounds.outHeight/sample>target*2)sample*=2;
            BitmapFactory.Options opt=new BitmapFactory.Options();opt.inSampleSize=Math.max(1,sample);opt.inPreferredConfig=Bitmap.Config.ARGB_8888;
            Bitmap b=BitmapFactory.decodeByteArray(data,0,data.length,opt);if(b==null)return;CACHE.put(clean,b);
            view.post(()->{Object tag=view.getTag();if(tag!=null&&clean.equals(String.valueOf(tag)))view.setImageBitmap(b);});
        }catch(Throwable ignored){}finally{if(c!=null)c.disconnect();}
    }

    public static void clearMemory(){CACHE.evictAll();}
    public static void onPerformanceModeChanged(){
        synchronized(RemoteImageLoader.class){
            ExecutorService e=executor; executor=null; executorWorkers=-1;
            if(e!=null) try{e.shutdownNow();}catch(Throwable ignored){}
        }
        clearMemory();
    }
}
