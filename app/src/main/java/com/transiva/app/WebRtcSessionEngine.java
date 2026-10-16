package com.transiva.app;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.*;
import org.webrtc.*;
import org.webrtc.audio.JavaAudioDeviceModule;
import java.util.*;
import java.util.concurrent.*;

/** Service-owned media/signaling. Holds application context only, never an Activity; view sinks are attached/detached by the UI. */
final class WebRtcSessionEngine {
    static final String CHANGED="com.transiva.app.CALL_SESSION_CHANGED";
    final Context context;
    final SessionManager session;
    final String role,orderId,source,peer;
    final boolean video,incoming;
    final String operationId;
    volatile String callId="",status="Menyiapkan panggilan",error="";
    volatile boolean ended,accepted,connected,muted,speaker=true,cameraEnabled=true;
    volatile long connectedAt;
    volatile boolean frontCamera=true,cameraSwitching;
    private boolean mediaRecoveryPending;
    private static final String CAMERA_ERROR="Kamera terhenti; suara tetap aktif";
    private static final String SWITCH_ERROR="Gagal mengganti kamera. Coba lagi.";
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final ExecutorService heartbeatIo=Executors.newSingleThreadExecutor();
    private boolean heartbeatBusy;
    private final Runnable stopped;
    private PeerConnectionFactory factory;
    private PeerConnection pc;
    private JavaAudioDeviceModule audioDevice;
    private AudioSource audioSource;
    private AudioTrack audioTrack;
    private EglBase egl;
    private CameraVideoCapturer camera;
    private SurfaceTextureHelper texture;
    private VideoSource videoSource;
    VideoTrack localVideo,remoteVideo;
    private final List<VideoSink> localSinks=new ArrayList<>(),remoteSinks=new ArrayList<>();
    private final List<IceCandidate> pendingIce=new ArrayList<>();
    private boolean cameraRunning,videoVisible=true,remoteSet,answerPending,offerMade,pollBusy,closing;
    private int after,networkFailures;
    private List<PeerConnection.IceServer> iceServers;
    WebRtcSessionEngine(Context c,Intent i,Runnable stop){
        context=c.getApplicationContext();session=new SessionManager(context);stopped=stop;
        role=context.getPackageName().endsWith(".driver")?"driver":"customer";
        orderId=clean(i.getStringExtra("order_id"));source=first(i.getStringExtra("source"),"orders");peer=first(i.getStringExtra("peer_name"),i.getStringExtra("caller_name"),"Transiva");
        operationId=first(i.getStringExtra("operation_id"),java.util.UUID.randomUUID().toString());
        video="video".equals(i.getStringExtra("call_type"));incoming=i.getBooleanExtra("incoming",false);callId=clean(i.getStringExtra("call_id"));
    }
    boolean active(){return !ended;}
    private static String clean(String s){return s==null?"":s.trim();}
    private static String first(String...v){for(String s:v)if(!clean(s).isEmpty())return clean(s);return "";}
    private JSONObject payload(String action)throws JSONException{return new JSONObject().put("action",action).put("role",role).put("call_id",callId);}
    private void async(Runnable job){if(io.isShutdown())return;try{io.execute(job);}catch(RejectedExecutionException ignored){}}
    private JSONObject startWithRetry(JSONObject payload)throws Exception {
        for(int attempt=0;;attempt++){
            try{return WebRtcSignalApi.post(session,payload);}
            catch(java.io.IOException error){
                if(attempt>=2||ended)throw error;
                Thread.sleep(attempt==0?350L:1000L);
            }
        }
    }
    void start(){
        changed();async(()->{try{
            if(incoming){WebRtcSignalApi.post(session,payload("accept"));accepted=true;status="Menghubungkan";}
            else{JSONObject p=payload("start").put("order_id",orderId).put("source",source).put("call_type",video?"video":"audio").put("operation_id",operationId);JSONObject r=startWithRetry(p);callId=r.optString("call_id");if(callId.isEmpty())throw new IllegalStateException("Server tidak memberikan ID panggilan");if(Arrays.asList("ended","rejected","missed","cancelled").contains(r.optString("status")))throw new IllegalStateException("Panggilan sebelumnya sudah berakhir. Mulai panggilan baru dari chat.");status="Memanggil "+peer;}
            main.post(()->{if(!ended){changed();main.post(poll);main.post(heartbeat);}});
        }catch(Exception e){main.post(()->fail(e.getMessage()));}});
    }
    // Separate from SDP/ICE queue: a slow candidate upload cannot starve liveness.
    private final Runnable heartbeat=new Runnable(){public void run(){
        if(ended)return;
        if(!callId.isEmpty()&&!heartbeatBusy&&!heartbeatIo.isShutdown()){
            heartbeatBusy=true;
            try{heartbeatIo.execute(()->{
                try{
                    JSONObject response=WebRtcSignalApi.post(session,payload("heartbeat"));
                    main.post(()->{heartbeatBusy=false;if(!ended)terminal(callId,response.optString("status"));});
                }catch(Exception error){main.post(()->{heartbeatBusy=false;});}
            });}catch(RejectedExecutionException error){heartbeatBusy=false;}
        }
        main.postDelayed(this,15000);
    }};
    private final Runnable poll=new Runnable(){public void run(){
        if(ended)return;
        if(!pollBusy&&!callId.isEmpty()){
            pollBusy=true;
            async(()->{try{
                JSONObject r=WebRtcSignalApi.post(session,payload("poll").put("candidate_after",after));
                main.post(()->{pollBusy=false;if(ended)return;networkFailures=0;apply(r);});
            }catch(Exception e){main.post(()->{pollBusy=false;if(ended)return;networkFailures++;if(!reconcileMediaConnection()){status="Menyambungkan kembali…";changed();}if(networkFailures>=12)fail("Koneksi signaling terputus. Coba hubungi kembali.");});}});
        }
        main.postDelayed(this,connected?2000:900);
    }};
    private boolean reconcileMediaConnection(){
        if(ended||pc==null)return false;
        if(!CallMediaState.isConnected(pc.iceConnectionState().name(),pc.connectionState().name()))return false;
        boolean notify=!connected||!"Terhubung".equals(status);
        connected=true;mediaRecoveryPending=false;main.removeCallbacks(mediaRecoveryDeadline);
        if(connectedAt==0)connectedAt=SystemClock.elapsedRealtime();
        status="Terhubung";if(notify)changed();return true;
    }
    private final Runnable mediaRecoveryDeadline=()->{
        if(ended||reconcileMediaConnection())return;
        fail("Koneksi media belum pulih. Coba hubungi kembali.");
    };
    private void mediaStateChanged(){
        if(ended||pc==null||reconcileMediaConnection())return;
        PeerConnection.IceConnectionState ice=pc.iceConnectionState();
        PeerConnection.PeerConnectionState transport=pc.connectionState();
        if(ice==PeerConnection.IceConnectionState.FAILED||ice==PeerConnection.IceConnectionState.DISCONNECTED
                ||transport==PeerConnection.PeerConnectionState.FAILED||transport==PeerConnection.PeerConnectionState.DISCONNECTED){
            connected=false;status="Menyambungkan kembali…";changed();
            if(!mediaRecoveryPending){mediaRecoveryPending=true;main.postDelayed(mediaRecoveryDeadline,25000);}
        }
    }
    private void apply(JSONObject r){
        String state=r.optString("status");
        if(Arrays.asList("ended","rejected","missed","cancelled").contains(state)){end(false,state.equals("rejected")?"Panggilan ditolak":"Panggilan berakhir");return;}
        if("accepted".equals(state)){accepted=true;if(pc==null&&iceServers==null)loadIce();}
        if(pc!=null){
            String sdp=r.optString(incoming?"offer_sdp":"answer_sdp");
            if(!remoteSet&&!answerPending&&!sdp.isEmpty()){
                answerPending=true;
                pc.setRemoteDescription(new Sdp(){public void onSetSuccess(){main.post(()->{if(ended)return;remoteSet=true;flushIce();if(incoming)answer();});}public void onSetFailure(String e){main.post(()->fail(e));}},new SessionDescription(incoming?SessionDescription.Type.OFFER:SessionDescription.Type.ANSWER,sdp));
            }
        }
        JSONArray candidates=r.optJSONArray("candidates");if(candidates!=null)for(int n=0;n<candidates.length();n++){JSONObject c=candidates.optJSONObject(n);if(c!=null){IceCandidate ice=new IceCandidate(c.optString("sdp_mid"),c.optInt("sdp_mline_index"),c.optString("candidate"));if(pc!=null&&remoteSet)pc.addIceCandidate(ice);else pendingIce.add(ice);}}
        after=r.optInt("candidate_last",after);reconcileMediaConnection();changed();
    }
    private void loadIce(){
        iceServers=new ArrayList<>();
        async(()->{try{
            JSONObject r=WebRtcSignalApi.post(session,payload("ice_config"));JSONArray servers=r.optJSONArray("ice_servers");List<PeerConnection.IceServer> list=new ArrayList<>();
            if(servers!=null)for(int n=0;n<servers.length();n++){JSONObject server=servers.optJSONObject(n);if(server==null)continue;JSONArray urls=server.optJSONArray("urls");if(urls!=null)for(int j=0;j<urls.length();j++){PeerConnection.IceServer.Builder b=PeerConnection.IceServer.builder(urls.optString(j));if(!server.optString("username").isEmpty())b.setUsername(server.optString("username"));if(!server.optString("credential").isEmpty())b.setPassword(server.optString("credential"));list.add(b.createIceServer());}}
            if(list.isEmpty())throw new IllegalStateException("Konfigurasi koneksi panggilan tidak tersedia");main.post(()->{if(!ended){iceServers=list;initialize();}});
        }catch(Exception e){main.post(()->fail(e.getMessage()));}});
    }
    private void initialize(){try{
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions());
        audioDevice=JavaAudioDeviceModule.builder(context).setUseHardwareAcousticEchoCanceler(false).setUseHardwareNoiseSuppressor(false).createAudioDeviceModule();
        PeerConnectionFactory.Builder builder=PeerConnectionFactory.builder().setAudioDeviceModule(audioDevice);
        if(video){egl=EglBase.create();builder.setVideoEncoderFactory(new DefaultVideoEncoderFactory(egl.getEglBaseContext(),true,true)).setVideoDecoderFactory(new DefaultVideoDecoderFactory(egl.getEglBaseContext()));}
        factory=builder.createPeerConnectionFactory();PeerConnection.RTCConfiguration config=new PeerConnection.RTCConfiguration(iceServers);config.sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN;
        pc=factory.createPeerConnection(config,new Observer());if(pc==null)throw new IllegalStateException("Koneksi media tidak dapat dibuat");
        audioSource=factory.createAudioSource(new MediaConstraints());audioTrack=factory.createAudioTrack("TRANSIVA_AUDIO",audioSource);audioTrack.setEnabled(!muted);pc.addTrack(audioTrack,Collections.singletonList("transiva_audio"));
        AudioManager audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);audio.setMode(AudioManager.MODE_IN_COMMUNICATION);audio.setSpeakerphoneOn(speaker);
        if(video)startCamera();status="Menghubungkan";changed();if(!incoming)offer();
    }catch(Exception e){fail(e.getMessage());}}
    EglBase.Context eglContext(){return egl==null?null:egl.getEglBaseContext();}
    private void startCamera(){
        CameraEnumerator devices=Camera2Enumerator.isSupported(context)?new Camera2Enumerator(context):new Camera1Enumerator(true);
        for(String name:devices.getDeviceNames())if(devices.isFrontFacing(name)){camera=devices.createCapturer(name,null);if(camera!=null){frontCamera=true;break;}}
        if(camera==null)for(String name:devices.getDeviceNames()){camera=devices.createCapturer(name,null);if(camera!=null){frontCamera=devices.isFrontFacing(name);break;}}
        if(camera==null)throw new IllegalStateException("Kamera tidak tersedia");
        videoSource=factory.createVideoSource(false);texture=SurfaceTextureHelper.create("TransivaCallCamera",egl.getEglBaseContext());camera.initialize(texture,context,videoSource.getCapturerObserver());localVideo=factory.createVideoTrack("TRANSIVA_VIDEO",videoSource);pc.addTrack(localVideo,Collections.singletonList("transiva_video"));for(VideoSink sink:localSinks)localVideo.addSink(sink);updateCamera();
    }
    void setVisible(boolean visible){videoVisible=visible;updateCamera();}
    void toggleCamera(){cameraEnabled=!cameraEnabled;updateCamera();changed();}
    void switchCamera(){
        if(ended||camera==null||!cameraRunning||cameraSwitching)return;
        cameraSwitching=true;changed();
        try{camera.switchCamera(new CameraVideoCapturer.CameraSwitchHandler(){
            public void onCameraSwitchDone(boolean isFrontCamera){main.post(()->{
                if(ended)return;cameraSwitching=false;frontCamera=isFrontCamera;
                if(SWITCH_ERROR.equals(error))error="";
                changed();
            });}
            public void onCameraSwitchError(String reason){main.post(()->{
                if(ended)return;cameraSwitching=false;error=SWITCH_ERROR;changed();
            });}
        });}catch(RuntimeException unavailable){cameraSwitching=false;error=SWITCH_ERROR;changed();}
    }
    private void updateCamera(){if(camera==null||ended)return;boolean run=videoVisible&&cameraEnabled;try{if(run&&!cameraRunning){camera.startCapture(640,480,20);cameraRunning=true;}else if(!run&&cameraRunning){camera.stopCapture();cameraRunning=false;}localVideo.setEnabled(run);if(run&&CAMERA_ERROR.equals(error)){error="";changed();}}catch(InterruptedException e){Thread.currentThread().interrupt();}catch(RuntimeException e){localVideo.setEnabled(false);error=CAMERA_ERROR;changed();}}
    void attach(VideoSink local,VideoSink remote){if(local!=null&&!localSinks.contains(local)){localSinks.add(local);if(localVideo!=null)localVideo.addSink(local);}if(remote!=null&&!remoteSinks.contains(remote)){remoteSinks.add(remote);if(remoteVideo!=null)remoteVideo.addSink(remote);}}
    void detach(VideoSink local,VideoSink remote){localSinks.remove(local);remoteSinks.remove(remote);if(localVideo!=null&&local!=null)localVideo.removeSink(local);if(remoteVideo!=null&&remote!=null)remoteVideo.removeSink(remote);}
    void toggleMute(){muted=!muted;if(audioTrack!=null)audioTrack.setEnabled(!muted);changed();}
    void toggleSpeaker(){speaker=!speaker;((AudioManager)context.getSystemService(Context.AUDIO_SERVICE)).setSpeakerphoneOn(speaker);changed();}
    private MediaConstraints constraints(){MediaConstraints c=new MediaConstraints();c.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio","true"));c.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo",video?"true":"false"));return c;}
    private void offer(){if(offerMade)return;offerMade=true;pc.createOffer(new Sdp(){public void onCreateSuccess(SessionDescription d){main.post(()->localDescription(d,"offer"));}public void onCreateFailure(String e){main.post(()->fail(e));}},constraints());}
    private void answer(){pc.createAnswer(new Sdp(){public void onCreateSuccess(SessionDescription d){main.post(()->localDescription(d,"answer"));}public void onCreateFailure(String e){main.post(()->fail(e));}},constraints());}
    private void localDescription(SessionDescription d,String action){if(ended||pc==null)return;pc.setLocalDescription(new Sdp(){public void onSetSuccess(){signal(action,d.description);}public void onSetFailure(String e){main.post(()->fail(e));}},d);}
    private void signal(String action,String sdp){if(ended)return;async(()->{try{WebRtcSignalApi.post(session,payload(action).put("sdp",sdp));}catch(Exception e){main.post(()->fail(e.getMessage()));}});}
    private void flushIce(){if(pc==null||!remoteSet)return;for(IceCandidate c:pendingIce)pc.addIceCandidate(c);pendingIce.clear();}
    void terminal(String id,String state){if(callId.equals(id)&&Arrays.asList("ended","rejected","missed").contains(state))end(false,"Panggilan berakhir");}
    private void fail(String message){error=message==null?"Panggilan gagal":message;end(true,error);}
    void end(boolean notify,String message){
        if(closing)return;closing=true;ended=true;status=message;main.removeCallbacks(poll);main.removeCallbacks(mediaRecoveryDeadline);main.removeCallbacks(heartbeat);heartbeatIo.shutdownNow();changed();release();
        // Queue behind start/accept so even an early cancel waits for the real call ID.
        async(()->{if(notify&&!callId.isEmpty())try{WebRtcSignalApi.post(session,payload("end"));}catch(Exception ignored){}main.post(stopped);io.shutdown();});
    }
    void destroy(){if(!ended){ended=true;main.removeCallbacks(poll);main.removeCallbacks(mediaRecoveryDeadline);main.removeCallbacks(heartbeat);heartbeatIo.shutdownNow();release();}io.shutdown();main.removeCallbacksAndMessages(null);}
    private void release(){
        if(localVideo!=null)for(VideoSink s:localSinks)localVideo.removeSink(s);if(remoteVideo!=null)for(VideoSink s:remoteSinks)remoteVideo.removeSink(s);localSinks.clear();remoteSinks.clear();
        if(camera!=null){try{if(cameraRunning)camera.stopCapture();}catch(InterruptedException e){Thread.currentThread().interrupt();}catch(RuntimeException ignored){}camera.dispose();camera=null;}cameraRunning=false;
        if(pc!=null){pc.close();pc.dispose();pc=null;}if(localVideo!=null){localVideo.dispose();localVideo=null;}remoteVideo=null;
        if(videoSource!=null){videoSource.dispose();videoSource=null;}if(texture!=null){texture.dispose();texture=null;}
        if(audioTrack!=null){audioTrack.dispose();audioTrack=null;}if(audioSource!=null){audioSource.dispose();audioSource=null;}if(factory!=null){factory.dispose();factory=null;}if(audioDevice!=null){audioDevice.release();audioDevice=null;}if(egl!=null){egl.release();egl=null;}
        AudioManager audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);audio.setSpeakerphoneOn(false);audio.setMode(AudioManager.MODE_NORMAL);
    }
    private void changed(){context.sendBroadcast(new Intent(CHANGED).setPackage(context.getPackageName()));}
    private static class Sdp implements SdpObserver{public void onCreateSuccess(SessionDescription d){}public void onSetSuccess(){}public void onCreateFailure(String e){}public void onSetFailure(String e){}}
    private final class Observer implements PeerConnection.Observer{
        public void onSignalingChange(PeerConnection.SignalingState s){}
        public void onIceConnectionChange(PeerConnection.IceConnectionState s){main.post(()->mediaStateChanged());}
        @Override public void onConnectionChange(PeerConnection.PeerConnectionState s){main.post(()->mediaStateChanged());}
        public void onIceConnectionReceivingChange(boolean b){}public void onIceGatheringChange(PeerConnection.IceGatheringState s){}
        public void onIceCandidate(IceCandidate c){if(ended)return;async(()->{try{WebRtcSignalApi.post(session,payload("candidate").put("candidate",c.sdp).put("sdp_mid",c.sdpMid==null?"":c.sdpMid).put("sdp_mline_index",c.sdpMLineIndex));}catch(Exception ignored){}});}
        public void onIceCandidatesRemoved(IceCandidate[] c){}public void onAddStream(MediaStream s){}public void onRemoveStream(MediaStream s){}public void onDataChannel(DataChannel d){}public void onRenegotiationNeeded(){}
        public void onTrack(RtpTransceiver t){remote(t.getReceiver().track());}public void onAddTrack(RtpReceiver r,MediaStream[] s){remote(r.track());}
        private void remote(MediaStreamTrack t){if(t instanceof VideoTrack)main.post(()->{if(ended)return;if(remoteVideo!=null)for(VideoSink s:remoteSinks)remoteVideo.removeSink(s);remoteVideo=(VideoTrack)t;for(VideoSink s:remoteSinks)remoteVideo.addSink(s);changed();});}
    }
}
