package com.transiva.app;
import android.app.Activity;
import android.graphics.*;
import android.media.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.HttpURLConnection;

/** Small, bounded, authenticated preview. Resources are released on message/screen change. */
final class TripBubbleMedia {
    private final Activity activity;private final LinearLayout parent;private DriverUiScope work;
    private MediaPlayer player;private File audio;private long version;
    private final java.util.Set<File> pending=new java.util.HashSet<>();
    TripBubbleMedia(Activity a,LinearLayout parent){activity=a;this.parent=parent;}
    boolean busy(){return player!=null;}
    void fit(int height){for(int i=0;i<parent.getChildCount();i++)if(parent.getChildAt(i) instanceof ImageView){View image=parent.getChildAt(i);int size=Math.max(dp(32),Math.min(dp(96),height));if(image.getLayoutParams().height!=size){image.getLayoutParams().height=size;image.requestLayout();}}}
    void close(){version++;synchronized(pending){for(File file:pending)file.delete();pending.clear();}if(work!=null)work.close();work=null;if(player!=null){player.release();player=null;}if(audio!=null){audio.delete();audio=null;}parent.removeAllViews();}
    void show(String raw,Runnable acknowledge,Runnable open){
        close();work=new DriverUiScope(activity);final DriverUiScope scope=work;final long gen=version;
        String url=TripBubbleContent.mediaUrl(raw);
        if(TripBubbleContent.image(raw)){
            ImageView image=new ImageView(activity);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setContentDescription("Gambar pesan · ketuk untuk membuka chat");
            parent.addView(image,new LinearLayout.LayoutParams(-1,dp(96)));image.setOnClickListener(v->open.run());
            TextView hint=label("Memuat gambar…");parent.addView(hint);
            String[] candidates=TripBubbleContent.mediaUrls(raw);
            if(candidates.length==0){hint.setText("Gambar tidak tersedia · buka chat");return;}
            if(!scope.execute(()->{
                Bitmap result=null;
                for (String candidate:candidates) {
                    if (!scope.alive() || Thread.currentThread().isInterrupted()) return;
                    try {
                        result=decodeImage(download(scope,candidate,6*1024*1024));
                        if (result!=null) break;
                    } catch (Exception ignored) { }
                }
                Bitmap bitmap=result;
                scope.post(()->{
                    if(gen!=version)return;
                    image.setImageBitmap(bitmap);
                    hint.setText(bitmap==null?"Gambar belum termuat · buka chat":"Ketuk untuk membuka chat");
                });
            })) hint.setText("Gambar belum termuat · buka chat");
        }else{
            TextView play=label("▶  Putar pesan suara");play.setTextSize(13);play.setMinHeight(dp(44));play.setGravity(Gravity.CENTER_VERTICAL);play.setContentDescription("Putar atau hentikan pesan suara");parent.addView(play);
            play.setOnClickListener(v->{
                acknowledge.run();
                if(player!=null){player.release();player=null;if(audio!=null){audio.delete();audio=null;}play.setText("▶  Putar pesan suara");return;}
                if(url.isEmpty()){play.setText("Audio tidak tersedia · buka chat");return;}
                play.setEnabled(false);play.setText("Memuat suara…");
                if(!scope.execute(()->{File temp=null;try{
                    byte[] bytes=download(scope,url,15*1024*1024);temp=File.createTempFile("bubble-voice-",".m4a",activity.getCacheDir());
                    try(FileOutputStream out=new FileOutputStream(temp)){out.write(bytes);}
                    final File ready=temp;
                    synchronized(pending){if(!scope.alive()){ready.delete();return;}pending.add(ready);}
                    scope.post(()->{synchronized(pending){pending.remove(ready);}if(gen!=version){ready.delete();return;}audio=ready;start(play);});
                }catch(Exception e){if(temp!=null)temp.delete();scope.post(()->{play.setEnabled(true);play.setText("▶  Coba putar lagi");});}})){play.setEnabled(true);play.setText("▶  Coba putar lagi");}
            });
        }
    }
    private void start(TextView play){
        try{
            MediaPlayer current=new MediaPlayer();player=current;
            current.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            current.setDataSource(audio.getAbsolutePath());
            current.setOnPreparedListener(mp->{if(player!=mp)return;mp.start();play.setEnabled(true);play.setText("■  Hentikan suara");});
            current.setOnCompletionListener(mp->{if(player==mp){player=null;mp.release();if(audio!=null){audio.delete();audio=null;}play.setText("▶  Putar lagi");}});
            current.setOnErrorListener((mp,w,e)->{if(player==mp){player=null;mp.release();if(audio!=null){audio.delete();audio=null;}play.setEnabled(true);play.setText("▶  Coba putar lagi");}return true;});current.prepareAsync();
        }catch(Exception e){if(player!=null){player.release();player=null;}if(audio!=null){audio.delete();audio=null;}play.setEnabled(true);play.setText("▶  Coba putar lagi");}
    }
    private static Bitmap decodeImage(byte[] bytes) {
        BitmapFactory.Options options=new BitmapFactory.Options();
        options.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
        if(options.outWidth<=0 || options.outHeight<=0) return null;
        options.inSampleSize=NetworkSafetyRules.bitmapSample(options.outWidth,options.outHeight,400,160000);
        options.inJustDecodeBounds=false;
        return BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
    }

    private byte[] download(DriverUiScope scope,String url,int limit)throws Exception{
        HttpURLConnection connection=com.transiva.app.driver.data.DriverConnectionRepository.open(url);
        try{
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(10000);connection.setReadTimeout(15000);
            if(NetworkSafetyRules.maySendCredentials(url))scope.owner.pin(connection);
            scope.owner.check();if(connection.getResponseCode()!=200)throw new IOException("Media unavailable");
            try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){
                byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1){scope.owner.check();if(output.size()+n>limit)throw new IOException("Media too large");output.write(buffer,0,n);}return output.toByteArray();
            }
        }finally{scope.owner.release(connection);connection.disconnect();}
    }
    private TextView label(String text){TextView v=new TextView(activity);v.setText(text);v.setTextSize(11);v.setTextColor(DriverThemeTokens.textSecondary(activity));return v;}
    private int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
}
