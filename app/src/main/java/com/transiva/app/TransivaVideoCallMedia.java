package com.transiva.app;

import android.app.Activity;
import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.webrtc.*;
import java.util.Collections;

/** Camera starts only after permission + accepted call. Background keeps voice and pauses camera. */
final class TransivaVideoCallMedia {
    private final Activity activity;
    private final EglBase egl=EglBase.create();
    private final SurfaceViewRenderer remote,local;
    private final TextView hint;
    private CameraVideoCapturer capturer;
    private SurfaceTextureHelper texture;
    private VideoSource source;
    private VideoTrack track,remoteTrack;
    private boolean enabled=true,visible=true,capturing,disposed,front=true;
    private Button cameraButton;
    TransivaVideoCallMedia(Activity a,LinearLayout root){
        activity=a;
        FrameLayout stage=new FrameLayout(a);stage.setBackgroundColor(Color.parseColor("#102337"));
        LinearLayout.LayoutParams stageLp=new LinearLayout.LayoutParams(-1,0,1);stageLp.setMargins(0,dp(10),0,dp(10));root.addView(stage,stageLp);
        remote=new SurfaceViewRenderer(a);remote.init(egl.getEglBaseContext(),null);remote.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);stage.addView(remote,new FrameLayout.LayoutParams(-1,-1));
        hint=new TextView(a);hint.setText("Video ditampilkan setelah panggilan diterima");hint.setTextColor(Color.WHITE);hint.setTextSize(12);hint.setGravity(Gravity.CENTER);stage.addView(hint,new FrameLayout.LayoutParams(-1,-1));
        local=new SurfaceViewRenderer(a);local.init(egl.getEglBaseContext(),null);local.setMirror(true);local.setZOrderMediaOverlay(true);local.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
        FrameLayout.LayoutParams preview=new FrameLayout.LayoutParams(dp(88),dp(116),Gravity.TOP|Gravity.END);preview.setMargins(dp(8),dp(8),dp(8),dp(8));stage.addView(local,preview);
    }
    private int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
    PeerConnectionFactory.Builder codecs(PeerConnectionFactory.Builder b){return b.setVideoEncoderFactory(new DefaultVideoEncoderFactory(egl.getEglBaseContext(),true,true)).setVideoDecoderFactory(new DefaultVideoDecoderFactory(egl.getEglBaseContext()));}
    void addControls(LinearLayout root){
        LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER);
        cameraButton=new Button(activity);cameraButton.setText("Kamera aktif");cameraButton.setTextSize(11);cameraButton.setAllCaps(false);cameraButton.setEnabled(false);
        Button swap=new Button(activity);swap.setText("Ganti kamera");swap.setTextSize(11);swap.setAllCaps(false);
        row.addView(cameraButton,new LinearLayout.LayoutParams(0,dp(48),1));row.addView(swap,new LinearLayout.LayoutParams(0,dp(48),1));root.addView(row);
        cameraButton.setOnClickListener(v->{enabled=!enabled;updateCapture();});
        swap.setOnClickListener(v->{if(capturer!=null&&capturing)capturer.switchCamera(new CameraVideoCapturer.CameraSwitchHandler(){public void onCameraSwitchDone(boolean isFront){front=isFront;activity.runOnUiThread(()->{if(!disposed)local.setMirror(front);});}public void onCameraSwitchError(String error){activity.runOnUiThread(()->{if(!disposed)hint.setText("Kamera tidak dapat diganti");});}});});
    }
    void start(PeerConnectionFactory factory,PeerConnection peer){
        if(disposed||track!=null)return;
        if(activity.checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)throw new IllegalStateException("Izin kamera diperlukan");
        CameraEnumerator enumerator=Camera2Enumerator.isSupported(activity)?new Camera2Enumerator(activity):new Camera1Enumerator(true);
        for(String name:enumerator.getDeviceNames())if(enumerator.isFrontFacing(name)){capturer=enumerator.createCapturer(name,null);if(capturer!=null){front=true;break;}}
        if(capturer==null)for(String name:enumerator.getDeviceNames()){capturer=enumerator.createCapturer(name,null);if(capturer!=null){front=enumerator.isFrontFacing(name);break;}}
        if(capturer==null)throw new IllegalStateException("Kamera tidak tersedia. Gunakan panggilan suara.");
        source=factory.createVideoSource(false);texture=SurfaceTextureHelper.create("TransivaVideoCapture",egl.getEglBaseContext());
        capturer.initialize(texture,activity.getApplicationContext(),source.getCapturerObserver());track=factory.createVideoTrack("TRANSIVA_VIDEO",source);track.addSink(local);local.setMirror(front);
        peer.addTrack(track,Collections.singletonList("transiva_video"));cameraButton.setEnabled(true);updateCapture();
    }
    void remote(MediaStreamTrack media){
        if(disposed||!(media instanceof VideoTrack))return;
        if(remoteTrack!=null)remoteTrack.removeSink(remote);remoteTrack=(VideoTrack)media;remoteTrack.addSink(remote);hint.setText("");
    }
    void visible(boolean value){visible=value;updateCapture();}
    private void updateCapture(){
        if(disposed||capturer==null)return;
        boolean desired=visible&&enabled;
        try{
            if(desired&&!capturing){capturer.startCapture(640,480,20);capturing=true;}
            else if(!desired&&capturing){capturer.stopCapture();capturing=false;}
            if(track!=null)track.setEnabled(desired);local.setVisibility(desired?android.view.View.VISIBLE:android.view.View.INVISIBLE);
            if(cameraButton!=null)cameraButton.setText(enabled?"Kamera aktif":"Kamera mati");
        }catch(InterruptedException e){Thread.currentThread().interrupt();}catch(RuntimeException e){if(track!=null)track.setEnabled(false);hint.setText("Kamera terhenti • suara tetap aktif");}
    }
    void releaseMedia(){
        if(remoteTrack!=null){try{remoteTrack.removeSink(remote);}catch(RuntimeException ignored){}remoteTrack=null;}
        if(capturer!=null){try{if(capturing)capturer.stopCapture();}catch(InterruptedException e){Thread.currentThread().interrupt();}catch(RuntimeException ignored){}capturing=false;try{capturer.dispose();}catch(RuntimeException ignored){}capturer=null;}
        if(track!=null){track.removeSink(local);track.dispose();track=null;}
        if(source!=null){source.dispose();source=null;}if(texture!=null){texture.dispose();texture=null;}
    }
    void dispose(){if(disposed)return;releaseMedia();disposed=true;local.release();remote.release();egl.release();}
}
