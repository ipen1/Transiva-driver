package com.transiva.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.media.*;
import android.os.*;
import android.util.Rational;
import android.view.*;
import android.widget.*;
import androidx.core.content.ContextCompat;
import androidx.annotation.ChecksSdkIntAtLeast;
import androidx.annotation.RequiresApi;
import org.webrtc.*;

/** A replaceable view of the service session. Back never owns or tears down media. */
public class TransivaCallUiActivity extends Activity {
    public static final String ACTION_CALL_STATE="com.transiva.app.WEBRTC_CALL_STATE",EXTRA_CALL_ID="call_id",EXTRA_CALL_STATUS="call_status";
    private static final int PERMISSIONS=7101;
    private LinearLayout root,header,controls,actions;
    private FrameLayout stage;
    private SurfaceViewRenderer local,remote;
    private TextView name,status;
    private Button accept,end,mute,speaker,camera,swap;
    private WebRtcSessionEngine attached;
    private boolean serviceRequested,startedSession,incoming,video,registered,rendererReady,resumed,backToChat,pipEntering;
    private int insetLeft,insetTop,insetRight,insetBottom;
    private android.window.OnBackInvokedCallback backCallback;
    private boolean startPending,permissionsInFlight,unlockRequested,permissionSettingsRequired,settingsOpen;
    private String failureMessage="";
    private final Runnable advanceStart=()->drainStart();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){
        if(ACTION_CALL_STATE.equals(i.getAction())&&WebRtcCallForegroundService.current()==null&&getIntent().getStringExtra("call_id")!=null&&getIntent().getStringExtra("call_id").equals(i.getStringExtra("call_id"))){String s=i.getStringExtra("call_status");if("ended".equals(s)||"rejected".equals(s)||"missed".equals(s)){stopRing();finish();return;}}
        if(i.hasExtra("error")){showFailure(i.getStringExtra("error"));return;}
        render();
    }};
    private final Runnable refresh=new Runnable(){public void run(){render();if(!isFinishing())main.postDelayed(this,1000);}};
    @Override protected void onCreate(Bundle saved){super.onCreate(saved);ensureOperationId(getIntent(),saved);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);incoming=getIntent().getBooleanExtra("incoming",false);if(incoming)CallDeliveryTrace.record(this,getIntent(),"CALL_UI_OPENED","");video="video".equals(getIntent().getStringExtra("call_type"));
        WebRtcSessionEngine e=WebRtcCallForegroundService.current();if(e!=null&&e.active()){video=e.video;incoming=e.incoming;startedSession=true;}
        build();IntentFilter f=new IntentFilter(WebRtcSessionEngine.CHANGED);f.addAction(ACTION_CALL_STATE);ContextCompat.registerReceiver(this,receiver,f,ContextCompat.RECEIVER_NOT_EXPORTED);registered=true;
        cancelIncomingNotification();
        if(e!=null&&e.active())render();else if(incoming){startRing();if(getIntent().getBooleanExtra("auto_accept",false))requestStart();}else if(getIntent().getStringExtra("order_id")!=null)requestStart();else{status.setText("Tidak ada panggilan aktif");accept.setVisibility(View.GONE);}
        if(Build.VERSION.SDK_INT>=33){backCallback=()->onBackPressed();getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,backCallback);}
        main.post(refresh);
    }
    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);ensureOperationId(i,null);if(WebRtcCallForegroundService.current()!=null){render();return;}failureMessage="";setIntent(i);incoming=i.getBooleanExtra("incoming",false);if(incoming)CallDeliveryTrace.record(this,i,"CALL_UI_OPENED","");video="video".equals(i.getStringExtra("call_type"));serviceRequested=false;startedSession=false;startPending=false;releaseRenderers();build();cancelIncomingNotification();if(incoming){startRing();if(i.getBooleanExtra("auto_accept",false))requestStart();}else requestStart();}
    private static void ensureOperationId(Intent intent,Bundle saved){
        if(intent.getBooleanExtra("incoming",false))return;
        String id=intent.getStringExtra("operation_id");
        if(id==null||id.isEmpty())id=saved==null?null:saved.getString("call_operation_id");
        if(id==null||id.isEmpty())id=java.util.UUID.randomUUID().toString();
        intent.putExtra("operation_id",id);
    }
    @Override protected void onSaveInstanceState(Bundle out){
        out.putString("call_operation_id",getIntent().getStringExtra("operation_id"));
        super.onSaveInstanceState(out);
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView label(String text,int size){TextView t=new TextView(this);t.setText(text);t.setTextSize(size);t.setTextColor(Color.WHITE);t.setGravity(Gravity.CENTER);return t;}
    private Button button(String text){Button b=new Button(this);b.setText(text);b.setTextSize(12);b.setAllCaps(false);return b;}
    private void build(){root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(12),dp(12),dp(12),dp(12));root.setBackgroundColor(0xFF07131F);
        header=new LinearLayout(this);header.setOrientation(LinearLayout.VERTICAL);name=label(getIntent().getStringExtra("peer_name")==null?"Panggilan Transiva":getIntent().getStringExtra("peer_name"),21);status=label(incoming?"Panggilan masuk":"Menyiapkan panggilan",13);header.addView(name);header.addView(status);root.addView(header);
        stage=new FrameLayout(this);stage.setBackgroundColor(0xFF102337);LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,0,1);sp.setMargins(0,dp(10),0,dp(10));root.addView(stage,sp);
        TextView placeholder=label(video?"Video ditampilkan setelah terhubung":"☎",video?13:64);stage.addView(placeholder,new FrameLayout.LayoutParams(-1,-1));
        controls=new LinearLayout(this);controls.setOrientation(LinearLayout.VERTICAL);
        LinearLayout audioRow=new LinearLayout(this);mute=button("Mic aktif");speaker=button("Speaker");audioRow.addView(mute,new LinearLayout.LayoutParams(0,dp(48),1));audioRow.addView(speaker,new LinearLayout.LayoutParams(0,dp(48),1));controls.addView(audioRow);
        LinearLayout cameraRow=new LinearLayout(this);camera=button("Kamera aktif");swap=button("Ganti kamera");cameraRow.addView(camera,new LinearLayout.LayoutParams(0,dp(48),1));cameraRow.addView(swap,new LinearLayout.LayoutParams(0,dp(48),1));cameraRow.setVisibility(video?View.VISIBLE:View.GONE);controls.addView(cameraRow);root.addView(controls);
        actions=new LinearLayout(this);accept=button("Terima");end=button(incoming?"Tolak":"Akhiri");accept.setVisibility(incoming?View.VISIBLE:View.GONE);actions.addView(accept,new LinearLayout.LayoutParams(0,dp(48),1));actions.addView(end,new LinearLayout.LayoutParams(0,dp(48),1));root.addView(actions);setContentView(root);
        root.setOnApplyWindowInsetsListener((v,insets)->{if(Build.VERSION.SDK_INT>=30){Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());insetLeft=bars.left;insetTop=bars.top;insetRight=bars.right;insetBottom=bars.bottom;}else{insetLeft=insets.getSystemWindowInsetLeft();insetTop=insets.getSystemWindowInsetTop();insetRight=insets.getSystemWindowInsetRight();insetBottom=insets.getSystemWindowInsetBottom();}applyPadding();return insets;});root.requestApplyInsets();
        accept.setOnClickListener(v->requestStart());end.setOnClickListener(v->hangup());mute.setOnClickListener(v->{WebRtcSessionEngine e=WebRtcCallForegroundService.current();if(e!=null)e.toggleMute();});speaker.setOnClickListener(v->{WebRtcSessionEngine e=WebRtcCallForegroundService.current();if(e!=null)e.toggleSpeaker();});camera.setOnClickListener(v->{WebRtcSessionEngine e=WebRtcCallForegroundService.current();if(e!=null)e.toggleCamera();});swap.setOnClickListener(v->{WebRtcSessionEngine e=WebRtcCallForegroundService.current();if(e!=null)e.switchCamera();});
    }
    private boolean permission(){return checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED&&(!video||checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED);}
    private void requestStart(){if(incoming)CallDeliveryTrace.record(this,getIntent(),"ACCEPT_REQUESTED","USER_ACTION");
        if(serviceRequested||!failureMessage.isEmpty())return;
        startPending=true;
        scheduleStart();
    }
    private void scheduleStart(){main.removeCallbacks(advanceStart);main.postDelayed(advanceStart,120);}
    private void drainStart(){
        if(!startPending||serviceRequested||permissionsInFlight
                ||!resumed||!hasWindowFocus()||isFinishing()||isDestroyed())return;
        KeyguardManager keyguard=(KeyguardManager)getSystemService(KEYGUARD_SERVICE);
        if(!permission()&&keyguard!=null&&keyguard.isKeyguardLocked()){
            permissionRetry("Buka kunci layar untuk mengizinkan dan memulai panggilan.");
            if(Build.VERSION.SDK_INT>=26&&!unlockRequested){
                unlockRequested=true;
                keyguard.requestDismissKeyguard(this,new KeyguardManager.KeyguardDismissCallback(){
                    @Override public void onDismissSucceeded(){unlockRequested=false;scheduleStart();}
                    @Override public void onDismissCancelled(){unlockRequested=false;startPending=false;}
                    @Override public void onDismissError(){unlockRequested=false;startPending=false;permissionRetry("Buka kunci layar, lalu ketuk Terima/Coba lagi.");}
                });
            }
            return;
        }
        if(!permission()){
            if(permissionSettingsRequired){
                if(settingsOpen)return;
                settingsOpen=true;startPending=false;
                Intent settings=new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:"+getPackageName()));
                try{startActivity(settings);}catch(RuntimeException e){settingsOpen=false;permissionRetry("Buka Setelan aplikasi untuk mengizinkan mikrofon/kamera.");}
                return;
            }
            // One system dialog at a time, only after the call Activity is visible and focused.
            permissionsInFlight=true;startPending=false;accept.setEnabled(false);
            status.setText(video?"Izinkan mikrofon dan kamera pada dialog Android":"Izinkan mikrofon pada dialog Android");
            if(Build.VERSION.SDK_INT>=31)getWindow().setHideOverlayWindows(true);
            try{requestPermissions(video?new String[]{Manifest.permission.RECORD_AUDIO,Manifest.permission.CAMERA}
                    :new String[]{Manifest.permission.RECORD_AUDIO},PERMISSIONS);}
            catch(RuntimeException e){permissionsInFlight=false;permissionRetry("Dialog izin belum dapat dibuka. Ketuk Coba lagi.");}
            return;
        }
        if(Build.VERSION.SDK_INT>=31)getWindow().setHideOverlayWindows(false);
        startPending=false;permissionSettingsRequired=false;resolveRing();stopRing();serviceRequested=true;
        cancelIncomingNotification();status.setText("Menyiapkan koneksi…");accept.setEnabled(false);
        Intent i=new Intent(this,WebRtcCallForegroundService.class).setAction(WebRtcCallForegroundService.CONNECT);
        i.putExtras(getIntent());i.putExtra("incoming",incoming);i.putExtra("call_type",video?"video":"audio");
        try{ContextCompat.startForegroundService(this,i);}
        catch(RuntimeException e){serviceRequested=false;permissionRetry("Panggilan belum dapat dimulai. Pastikan aplikasi terbuka dan izin sudah diberikan.");}
    }
    private void permissionRetry(String message){
        if(status==null)return;
        status.setText(message);accept.setVisibility(View.VISIBLE);accept.setEnabled(true);
        accept.setText(permissionSettingsRequired?"Buka Setelan":incoming?"Terima / izinkan":"Coba lagi / izinkan");
    }
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused)scheduleStart();}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] result){
        super.onRequestPermissionsResult(request,permissions,result);
        if(request!=PERMISSIONS)return;
        permissionsInFlight=false;
        if(permission()){permissionSettingsRequired=false;startPending=true;scheduleStart();}
        else{
            startPending=false;permissionSettingsRequired=false;
            // An empty callback can be cancellation; it must not be treated as permanent denial.
            for(int n=0;n<Math.min(permissions.length,result.length);n++)
                if(result[n]!=PackageManager.PERMISSION_GRANTED&&!shouldShowRequestPermissionRationale(permissions[n]))permissionSettingsRequired=true;
            permissionRetry(permissionSettingsRequired?"Izin ditolak. Buka Setelan aplikasi untuk mengaktifkannya."
                    :video?"Izin mikrofon dan kamera diperlukan. Ketuk untuk mencoba lagi.":"Izin mikrofon diperlukan. Ketuk untuk mencoba lagi.");
        }
    }
    private void showFailure(String message){
        failureMessage=message==null||message.trim().isEmpty()?"Panggilan gagal. Kembali ke chat dan coba panggilan baru.":message;
        startPending=false;serviceRequested=false;startedSession=false;main.removeCallbacks(advanceStart);
        stopRing();releaseRenderers();status.setText(failureMessage);accept.setVisibility(View.GONE);
        end.setText("Tutup");end.setOnClickListener(v->finish());
    }
    private void render(){if(!failureMessage.isEmpty())return;WebRtcSessionEngine e=WebRtcCallForegroundService.current();if(e==null||!e.active()){
        if(e!=null&&e.ended&&e.failed){showFailure(e.status);return;}
        if(e!=null&&e.ended&&serviceRequested&&!startedSession){Toast.makeText(this,e.status,Toast.LENGTH_LONG).show();finish();}
        if(startedSession){updatePip();stopRing();releaseRenderers();finish();}return;}
        startedSession=true;name.setText(e.peer);status.setText(e.status+(e.connectedAt>0?" • "+duration(e.connectedAt):"")+(e.error.isEmpty()?"":"\n"+e.error));accept.setVisibility(View.GONE);end.setText("Akhiri");mute.setText(e.muted?"Mic mati":"Mic aktif");speaker.setText(e.speaker?"Speaker aktif":"Speaker mati");camera.setText(e.cameraEnabled?"Kamera aktif":"Kamera mati");
        if(video&&e.eglContext()!=null&&!rendererReady){attached=e;local=new SurfaceViewRenderer(this);remote=new SurfaceViewRenderer(this);remote.init(e.eglContext(),null);remote.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);stage.addView(remote,new FrameLayout.LayoutParams(-1,-1));local.init(e.eglContext(),null);local.setZOrderMediaOverlay(true);local.setMirror(e.frontCamera);FrameLayout.LayoutParams preview=new FrameLayout.LayoutParams(dp(84),dp(112),Gravity.TOP|Gravity.END);stage.addView(local,preview);rendererReady=true;e.attach(local,remote);}
        if(local!=null)local.setMirror(e.frontCamera);
        if(local!=null)local.setVisibility(e.cameraEnabled&&!(Build.VERSION.SDK_INT>=26&&isInPictureInPictureMode())?View.VISIBLE:View.INVISIBLE);
        updatePip();
    }
    private String duration(long base){long seconds=Math.max(0,(SystemClock.elapsedRealtime()-base)/1000);return String.format(java.util.Locale.US,"%02d:%02d",seconds/60,seconds%60);}
    private void releaseRenderers(){if(attached!=null)attached.detach(local,remote);attached=null;if(local!=null){local.release();local=null;}if(remote!=null){remote.release();remote=null;}rendererReady=false;}
    private void hangup(){if(incoming&&!serviceRequested&&!startedSession)CallDeliveryTrace.record(this,getIntent(),"REJECT_REQUESTED","CALL_SCREEN");resolveRing();stopRing();cancelIncomingNotification(true);WebRtcSessionEngine e=WebRtcCallForegroundService.current();if(e!=null&&e.active()){e.end(true,"Panggilan berakhir");}else if(serviceRequested){startService(new Intent(this,WebRtcCallForegroundService.class).setAction(WebRtcCallForegroundService.END));finish();}else{final String id=getIntent().getStringExtra("call_id");final SessionManager session=new SessionManager(getApplicationContext());final String role=getPackageName().endsWith(".driver")?"driver":"customer";if(id!=null&&!id.isEmpty())new Thread(()->{try{WebRtcSignalApi.post(session,new org.json.JSONObject().put("action","reject").put("role",role).put("call_id",id));}catch(Exception ignored){}},"TransivaCallReject").start();finish();}}
    @ChecksSdkIntAtLeast(api = 26)
    private boolean pipAvailable(){return Build.VERSION.SDK_INT>=26&&getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);}
    @RequiresApi(26)
    private PictureInPictureParams pipParams(){PictureInPictureParams.Builder b=new PictureInPictureParams.Builder().setAspectRatio(new Rational(3,4));if(stage!=null){Rect r=new Rect();if(stage.getGlobalVisibleRect(r))b.setSourceRectHint(r);}if(Build.VERSION.SDK_INT>=31)b.setAutoEnterEnabled(video&&startedSession&&WebRtcCallForegroundService.current()!=null&&WebRtcCallForegroundService.current().active());return b.build();}
    private void updatePip(){if(Build.VERSION.SDK_INT>=26&&video&&pipAvailable())try{setPictureInPictureParams(pipParams());}catch(RuntimeException ignored){}}
    private boolean enterPip(){if(Build.VERSION.SDK_INT<26||!video||!startedSession||!pipAvailable())return false;try{pipEntering=true;boolean success=enterPictureInPictureMode(pipParams());if(!success)pipEntering=false;return success;}catch(RuntimeException e){pipEntering=false;return false;}}
    @Override public void onBackPressed(){if(video&&startedSession){backToChat=true;if(enterPip())return;backToChat=false;Toast.makeText(this,"PiP tidak tersedia; suara tetap berjalan",Toast.LENGTH_SHORT).show();}openChat();finish();}
    @Override protected void onUserLeaveHint(){super.onUserLeaveHint();if(video&&startedSession&&Build.VERSION.SDK_INT<31)enterPip();}
    @RequiresApi(26)
    @Override public void onPictureInPictureModeChanged(boolean pip,Configuration config){super.onPictureInPictureModeChanged(pip,config);pipEntering=false;header.setVisibility(pip?View.GONE:View.VISIBLE);controls.setVisibility(pip?View.GONE:View.VISIBLE);actions.setVisibility(pip?View.GONE:View.VISIBLE);applyPadding();if(local!=null)local.setVisibility(!pip&&WebRtcCallForegroundService.current()!=null&&WebRtcCallForegroundService.current().cameraEnabled?View.VISIBLE:View.INVISIBLE);WebRtcSessionEngine e=WebRtcCallForegroundService.current();if(e!=null)e.setVisible(pip||resumed);if(pip&&backToChat){backToChat=false;openChat();}}
    private void applyPadding(){boolean pip=Build.VERSION.SDK_INT>=26&&isInPictureInPictureMode();root.setPadding(pip?0:dp(12)+insetLeft,pip?0:dp(12)+insetTop,pip?0:dp(12)+insetRight,pip?0:dp(12)+insetBottom);}
    private void openChat(){WebRtcSessionEngine e=WebRtcCallForegroundService.current();String order=e!=null?e.orderId:getIntent().getStringExtra("order_id");if(order==null||order.isEmpty())return;Intent i=new Intent();i.setClassName(this,getPackageName().endsWith(".driver")?"com.transiva.app.DriverChatRoomActivity":"com.transiva.app.CustomerChatRoomActivity");i.putExtra("order_id",order).putExtra("order_source",e!=null?e.source:getIntent().getStringExtra("source")).putExtra("source",e!=null?e.source:getIntent().getStringExtra("source")).putExtra("participant_name",e!=null?e.peer:getIntent().getStringExtra("peer_name"));i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);startActivity(i);}
    @Override protected void onResume(){super.onResume();resumed=true;
        if(settingsOpen){settingsOpen=false;startPending=permission();if(!startPending)permissionRetry("Izin belum diberikan. Anda dapat membukanya lagi melalui Setelan.");}
        scheduleStart();WebRtcSessionEngine e=WebRtcCallForegroundService.current();if(e!=null)e.setVisible(true);render();}
    @Override protected void onPause(){super.onPause();resumed=false;main.removeCallbacks(advanceStart);/* Camera remains active during PiP transition; onStop decides visibility. */}
    @Override protected void onStop(){super.onStop();WebRtcSessionEngine e=WebRtcCallForegroundService.current();if(e!=null)e.setVisible(Build.VERSION.SDK_INT>=26&&isInPictureInPictureMode());}
    @Override protected void onDestroy(){if(!incoming||startedSession||serviceRequested)stopRing();main.removeCallbacksAndMessages(null);releaseRenderers();if(registered)unregisterReceiver(receiver);if(Build.VERSION.SDK_INT>=33&&backCallback!=null)getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);super.onDestroy();/* Never end or release the service session here. */}
    private long incomingRemaining(){
        if(getIntent().hasExtra(IncomingCallNotifier.DEADLINE))return Math.max(1,IncomingCallNotifier.remaining(getIntent()));
        return Math.max(1,Math.min(45,getIntent().getIntExtra("remaining_seconds",45)))*1000L;
    }
    private void startRing(){if(incoming){
        if(getIntent().getBooleanExtra("incoming_system_alert",false))IncomingCallNotifier.cancel(this,getIntent().getStringExtra("call_id"));
        IncomingCallNotifier.attachVisible(this,getIntent());
        IncomingCallAlertManager.start(this,getIntent().getStringExtra("call_id"),incomingRemaining());
        final String ringingId=getIntent().getStringExtra("call_id");
        main.postDelayed(()->{
            if(!isFinishing()&&!serviceRequested&&!startedSession
                    &&ringingId!=null&&ringingId.equals(getIntent().getStringExtra("call_id"))){
                IncomingCallAlertManager.resolve(ringingId);finish();
            }
        },incomingRemaining());
    }}
    private void stopRing(){if(incoming){String id=getIntent().getStringExtra("call_id");if(id!=null&&!id.trim().isEmpty())IncomingCallAlertManager.stop(id);}}
    private void resolveRing(){if(incoming)IncomingCallAlertManager.resolve(getIntent().getStringExtra("call_id"));}
    private void cancelIncomingNotification(){cancelIncomingNotification(false);}
    private void cancelIncomingNotification(boolean force){if(!force&&incoming&&!serviceRequested&&!startedSession)return;String id=getIntent().getStringExtra("call_id");if(id!=null&&!id.isEmpty()){NotificationManager n=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);n.cancel(Math.abs(("webrtc_call|"+id).hashCode()));}}
}
