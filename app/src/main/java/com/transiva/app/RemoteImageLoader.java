package com.transiva.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Remote image loader with memory + persistent disk cache and sampled decoding.
 * Disk cache prevents profile/document images from visually resetting every time
 * an Activity is recreated or the memory cache is cleared.
 */
public final class RemoteImageLoader {
    private static volatile ExecutorService executor;
    private static volatile int executorWorkers = -1;
    private static final int CACHE_KB = Math.max(2048, Math.min(12288, (int)(Runtime.getRuntime().maxMemory()/1024L/12L)));
    private static final int MAX_DOWNLOAD_BYTES = 6 * 1024 * 1024;
    private static final long MAX_DISK_BYTES = 32L * 1024L * 1024L;
    private static final long MAX_DISK_AGE_MS = 30L * 24L * 60L * 60L * 1000L;

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
        if(view==null)return;
        String clean=imageUrl==null?"":imageUrl.trim();
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);

        if(clean.isEmpty()){
            if(fallbackDrawable!=0)view.setImageResource(fallbackDrawable);
            view.setTag(null);
            return;
        }

        view.setTag(clean);
        Bitmap cached=CACHE.get(clean);
        if(cached!=null&&!cached.isRecycled()){
            view.setImageBitmap(cached);
            return;
        }

        // Keep fallback only while there is genuinely no cached image yet.
        if(fallbackDrawable!=0)view.setImageResource(fallbackDrawable);
        pool(view).execute(()->load(view,clean));
    }

    private static void load(ImageView view,String clean){
        try {
            byte[] disk = readDisk(view, clean);
            if (disk != null && disk.length > 0) {
                Bitmap b = decode(view, disk);
                if (b != null) {
                    CACHE.put(clean, b);
                    publish(view, clean, b);
                    return;
                }
            }
        } catch (Throwable ignored) {}

        HttpURLConnection c=null;
        try{
            c=com.transiva.app.driver.data.DriverConnectionRepository.open(clean);
            c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setUseCaches(true);
            c.setRequestProperty("Accept","image/webp,image/*");
            int status=c.getResponseCode(); if(status<200||status>=300)return;
            BufferedInputStream in=new BufferedInputStream(c.getInputStream());
            ByteArrayOutputStream out=new ByteArrayOutputStream(64*1024);
            byte[] buf=new byte[8192];int n,total=0;
            while((n=in.read(buf))>0){
                total+=n;if(total>MAX_DOWNLOAD_BYTES){in.close();return;}
                out.write(buf,0,n);
            }
            in.close();
            byte[] data=out.toByteArray();
            Bitmap b=decode(view,data);if(b==null)return;
            CACHE.put(clean,b);
            writeDisk(view,clean,data);
            publish(view,clean,b);
        }catch(Throwable ignored){}finally{if(c!=null)c.disconnect();}
    }

    private static Bitmap decode(ImageView view, byte[] data){
        if(data==null||data.length==0)return null;
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(data,0,data.length,bounds);
        if(bounds.outWidth<=0||bounds.outHeight<=0)return null;
        int target=DevicePerformanceProfile.get(view.getContext()).imageMaxSidePx;
        int sample=1;while(bounds.outWidth/sample>target*2||bounds.outHeight/sample>target*2)sample*=2;
        BitmapFactory.Options opt=new BitmapFactory.Options();
        opt.inSampleSize=Math.max(1,sample);opt.inPreferredConfig=Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(data,0,data.length,opt);
    }

    private static void publish(ImageView view,String clean,Bitmap b){
        view.post(()->{
            Object tag=view.getTag();
            if(tag!=null&&clean.equals(String.valueOf(tag))&&!b.isRecycled())view.setImageBitmap(b);
        });
    }

    private static File dir(ImageView view){
        File d=new File(view.getContext().getCacheDir(),"transiva_remote_images_v1");
        if(!d.exists()) d.mkdirs();
        return d;
    }

    private static File file(ImageView view,String key){ return new File(dir(view), sha256(key)+".img"); }

    private static byte[] readDisk(ImageView view,String key){
        File f=file(view,key);
        if(!f.isFile())return null;
        long age=System.currentTimeMillis()-f.lastModified();
        if(age<0||age>MAX_DISK_AGE_MS){f.delete();return null;}
        if(f.length()<=0||f.length()>MAX_DOWNLOAD_BYTES){f.delete();return null;}
        try(FileInputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream((int)f.length())){
            byte[] buf=new byte[8192];int n;while((n=in.read(buf))>0)out.write(buf,0,n);
            // touch for LRU-like cleanup
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(System.currentTimeMillis());
            return out.toByteArray();
        }catch(Throwable e){f.delete();return null;}
    }

    private static void writeDisk(ImageView view,String key,byte[] data){
        if(data==null||data.length==0||data.length>MAX_DOWNLOAD_BYTES)return;
        File target=file(view,key);File tmp=new File(target.getParentFile(),target.getName()+".tmp");
        try(FileOutputStream out=new FileOutputStream(tmp,false)){
            out.write(data);out.flush();
            if(target.exists())target.delete();
            if(!tmp.renameTo(target)){
                try(FileOutputStream direct=new FileOutputStream(target,false)){direct.write(data);}
                tmp.delete();
            }
            cleanup(target.getParentFile());
        }catch(Throwable ignored){tmp.delete();}
    }

    private static void cleanup(File dir){
        try{
            File[] files=dir.listFiles();if(files==null||files.length==0)return;
            long total=0;for(File f:files)if(f.isFile())total+=f.length();
            if(total<=MAX_DISK_BYTES)return;
            java.util.Arrays.sort(files,(a,b)->Long.compare(a.lastModified(),b.lastModified()));
            for(File f:files){if(total<=MAX_DISK_BYTES)return;if(f.isFile()){long n=f.length();if(f.delete())total-=n;}}
        }catch(Throwable ignored){}
    }

    private static String sha256(String s){
        try{
            MessageDigest md=MessageDigest.getInstance("SHA-256");byte[] d=md.digest(s.getBytes("UTF-8"));
            StringBuilder b=new StringBuilder();for(byte x:d)b.append(String.format(java.util.Locale.US,"%02x",x));return b.toString();
        }catch(Throwable ignored){return Integer.toHexString(s.hashCode());}
    }

    /** Memory can be reclaimed without deleting stable image copies on disk. */
    public static void clearMemory(){CACHE.evictAll();}
    public static void onPerformanceModeChanged(){
        synchronized(RemoteImageLoader.class){
            ExecutorService e=executor; executor=null; executorWorkers=-1;
            if(e!=null) try{e.shutdownNow();}catch(Throwable ignored){}
        }
        clearMemory();
    }
}
