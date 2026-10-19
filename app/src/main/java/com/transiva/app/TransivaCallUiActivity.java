package com.transiva.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.*;
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
    private FrameLayout stage,identity,previewFrame;
    private CallVisuals.Avatar avatar;
    private TextView brand,stageHint;
    private boolean compact;
    private String boundPhoto="",boundName="";
    private CallTextureRenderer local,remote;
    private TextView name,status;
    private Button accept,end,mute,speaker,camera,swap;
    private WebRtcSessionEngine attached;
    private boolean serviceRequested,startedSession,incoming,video,registered,rendererReady,resumed,backToChat,pipEntering;
    private boolean startPending,permissionsInFlight,unlockRequested,permissionSettingsRequired,settingsOpen;
    private String failureMessage="";
    private final Runnable advanceStart=()->drainStart();
    private android.window.OnBackInvokedCallback backCallback;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){
        if(i.hasExtra("error")){showFailure(i.getStringExtra("error"));return;}
        if(!failureMessage.isEmpty())return;
        if(ACTION_CALL_STATE.equals(i.getAction())&&WebRtcCallForegroundService.current()==null&&getIntent().getStringExtra("call_id")!=null&&getIntent().getStringExtra("call_id").equals(i.getStringExtra("call_id"))){String s=i.getStringExtra("call_status");if("ended".equals(s)||"rejected".equals(s)||"missed".equals(s)){stopRing();finish();return;}}
        render();
    }};
    private final Runnable refresh=new Runnable(){public void run(){render();if(!isFinishing())main.postDelayed(this,1000);}};
    @Override protected void onCreate(Bundle saved){super.onCreate(saved);ensureOperationId(getIntent(),saved);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);incoming=getIntent().getBooleanExtra("incoming",false);if(incoming)CallDeliveryTrace.record(this,getIntent(),"CALL_UI_OPENED","");video="video".equals(getIntent().getStringExtra("call_type"));
        WebRtcSessionEngine e=WebRtcCallForegroundService.current();if(e!=null&&e.active()){video=e.video;incoming=e.incoming;startedSession=true;}
        build();IntentFilter f=new IntentFilter(WebRtcSessionEngine.CHANGED);f.addAction(ACTION_CALL_STATE);ContextCompat.registerReceiver(this,receiver,f,ContextCompat.RECEIVER_NOT_EXPORTED);registered=true;
        cancelIncomingNotification();
        if(e!=null&&e.active())render();else if(incoming){startRing();if(getIntent().getBooleanExtra("auto_accept",false))requestStart();}else if(getIntent().getStringExtra("order_id")!=null)requestStart();else{status.setText("Tidak ada panggilan aktif");accept.setVisibility(View.GONE);}
        if(Build.VERSION.SDK_INT>=33){backCallback=()->onBackPressed();getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,backCallback);}
    }
    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);ensureOperationId(i,null);if(WebRtcCallForegroundService.current()!=null){render();return;}failureMessage="";startPending=false;setIntent(i);incoming=i.getBooleanExtra("incoming",false);if(incoming)CallDeliveryTrace.record(this,i,"CALL_UI_OPENED","");video="video".equals(i.getStringExtra("call_type"));serviceRequested=false;startedSession=false;releaseRenderers();build();cancelIncomingNotification();if(incoming){startRing();if(i.getBooleanExtra("auto_accept",false))requestStart();}else requestStart();}
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
    private Button button(String text,String icon,int kind){
        Button b=new Button(this);b.setText(text);b.setTextSize(11);b.setMaxLines(2);
        CallVisuals.style(b,icon,false,kind,dp(kind==0?22:28));return b;
    }
    private void addControl(LinearLayout row,Button b){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(76),1);p.setMargins(dp(4),0,dp(4),0);row.addView(b,p);
    }
    private void build(){
        boundPhoto="";boundName="";compact=false;
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18),dp(10),dp(18),dp(16));
        root.setBackground(CallVisuals.surface(CallVisuals.TOP,CallVisuals.BOTTOM,0,0));
        // Call artwork keeps its own contrast in either application theme.
        // Driver screen uses its own dark call palette in both themes.
        header=new LinearLayout(this);header.setOrientation(LinearLayout.VERTICAL);
        brand=label("T R A N S I V A  /  "+(video?"VIDEO CALL":"VOICE CALL"),10);
        brand.setTextColor(0xff71d8c2);brand.setPadding(0,0,0,dp(10));header.addView(brand);
        name=label(CallPeerIdentity.first(getIntent().getStringExtra("peer_name"),getIntent().getStringExtra("caller_name"),"Transiva"),28);
        name.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));name.setMaxLines(2);header.addView(name);
        status=label(incoming?"Panggilan masuk":"Menyiapkan panggilan",13);status.setTextColor(CallVisuals.MUTED);
        status.setPadding(0,dp(4),0,dp(4));header.addView(status);root.addView(header);
        stage=new FrameLayout(this);stage.setClipChildren(true);stage.setClipToPadding(true);stage.setClipToOutline(true);stage.setBackground(CallVisuals.surface(0xff10263b,0xff091525,dp(28),0xff213b50));
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,0,1);sp.setMargins(0,dp(12),0,dp(16));root.addView(stage,sp);
        identity=new FrameLayout(this);identity.setBackground(CallVisuals.surface(0xff10263b,0xff091525,dp(28),0xff213b50));identity.addView(new CallVisuals.Halo(this),new FrameLayout.LayoutParams(-1,-1));
        avatar=new CallVisuals.Avatar(this);identity.addView(avatar,new FrameLayout.LayoutParams(dp(144),dp(144),Gravity.CENTER));
        stageHint=label(video?"Menunggu video lawan bicara":"Panggilan suara",12);stageHint.setTextColor(CallVisuals.MUTED);
        FrameLayout.LayoutParams hint=new FrameLayout.LayoutParams(-1,dp(40),Gravity.BOTTOM);hint.bottomMargin=dp(12);identity.addView(stageHint,hint);
        stage.addView(identity,new FrameLayout.LayoutParams(-1,-1));
        stage.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->resizeLocalPreview());
        controls=new LinearLayout(this);controls.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER);
        mute=button("Mikrofon","mic",0);speaker=button("Speaker","speaker",0);
        camera=button("Kamera","video",0);swap=button("Putar kamera","swap",0);
        for(Button control:new Button[]{mute,speaker,camera,swap}){control.setEnabled(false);control.setAlpha(.45f);}
        addControl(row,mute);addControl(row,speaker);if(video){addControl(row,camera);addControl(row,swap);}
        controls.addView(row);root.addView(controls);
        actions=new LinearLayout(this);actions.setPadding(0,dp(14),0,0);
        accept=button(video?"Terima video":"Terima","phone",1);end=button(incoming?"Tolak":"Akhiri","end",2);
        accept.setVisibility(incoming?View.VISIBLE:View.GONE);
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(0,dp(68),1);ap.setMargins(dp(4),0,dp(4),0);
        actions.addView(accept,ap);LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(ap);actions.addView(end,ep);root.addView(actions);
        setContentView(root);DriverResponsiveUi.apply(this);applyCallBars();
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{boolean small=(b-t)<dp(480);if(small!=compact){compact=small;resizeChrome();}});
        bindIdentity(name.getText().toString(),CallPeerIdentity.first(getIntent().getStringExtra("peer_photo"),getIntent().getStringExtra("caller_photo")));
        accept.setOnClickListener(v->requestStart());end.setOnClickListener(v->hangup());
        mute.setOnClickListener(v->{WebRtcSessionEngine e=WebRtcCallForegroundService.current();if(e!=null){e.toggleMute();render();}});
        speaker.setOnClickListener(v->{WebRtcSessionEngine e=WebRtcCallForegroundService.current();if(e!=null){e.toggleSpeaker();render();}});
        camera.setOnClickListener(v->{WebRtcSessionEngine e=WebRtcCallForegroundService.current();if(e!=null){e.toggleCamera();render();}});
        swap.setOnClickListener(v->{WebRtcSessionEngine e=WebRtcCallForegroundService.current();if(e!=null)e.switchCamera();});
    }
    private void bindIdentity(String peer,String photo){
        if(!peer.equals(boundName)){boundName=peer;name.setText(peer);avatar.name(peer);}
        String url=CallPeerIdentity.photoUrl(photo);
        if(!url.equals(boundPhoto)){boundPhoto=url;avatar.setImageDrawable(null);RemoteImageLoader.loadCenterCrop(avatar,url,0);}
    }
    private void resizeChrome(){
        brand.setVisibility(compact?View.GONE:View.VISIBLE);name.setTextSize(compact?20:28);
        for(Button b:new Button[]{mute,speaker,camera,swap,accept,end}){
            ViewGroup.LayoutParams p=b.getLayoutParams();if(p!=null){p.height=dp(compact?64:(b==accept||b==end)?68:76);b.setLayoutParams(p);}
        }
        actions.setPadding(0,dp(compact?6:14),0,0);applyPadding();
    }
    private void updateControls(WebRtcSessionEngine e){
        control(mute,"mic",e.muted,"Mic mati","Mikrofon",e.muted?"Aktifkan mikrofon":"Matikan mikrofon");
        control(speaker,"speaker",e.speaker,"Speaker aktif","Speaker",e.speaker?"Matikan speaker":"Aktifkan speaker");
        control(camera,"video",!e.cameraEnabled,"Kamera mati","Kamera",e.cameraEnabled?"Matikan kamera":"Aktifkan kamera");
        boolean ready=e.connected;mute.setEnabled(ready);speaker.setEnabled(ready);camera.setEnabled(ready);swap.setEnabled(ready&&e.cameraEnabled&&!e.cameraSwitching);
        for(Button b:new Button[]{mute,speaker,camera,swap})b.setAlpha(b.isEnabled()?1f:.45f);
    }
    private void control(Button b,String icon,boolean selected,String on,String off,String description){
        if(b.isSelected()!=selected)CallVisuals.style(b,icon,selected,0,dp(22));
        b.setText(selected?on:off);b.setContentDescription(description);
    }
    // Called after the global theme applies its window colors.
    void applyCallBars(){DriverSystemBars.setStatusBarColor(this,CallVisuals.TOP);DriverSystemBars.setNavigationBarColor(this,CallVisuals.BOTTOM);}
    private boolean permission(){return checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED&&(!video||checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED);}
    private void requestStart(){if(incoming)CallDeliveryTrace.record(this,getIntent(),"ACCEPT_REQUESTED","USER_ACTION");
        if(serviceRequested||!failureMessage.isEmpty())return;
        startPending=true;
        scheduleStart();
    }
    private void scheduleStart(){main.removeCallbacks(advanceStart);main.postDelayed(advanceStart,120);}
    private void drainStart(){
        if(!startPending||serviceRequested||permissionsInFlight||!failureMessage.isEmpty()
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
        if(status==null||!failureMessage.isEmpty())return;
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
        startedSession=true;bindIdentity(e.peer,e.peerPhoto);status.setText(e.status+(e.connectedAt>0?" • "+duration(e.connectedAt):"")+(e.error.isEmpty()?"":"\n"+e.error));accept.setVisibility(View.GONE);end.setText("Akhiri");updateControls(e);
        if(video&&e.eglContext()!=null&&!rendererReady){
            attached=e;local=new CallTextureRenderer(this);remote=new CallTextureRenderer(this);
            final CallTextureRenderer target=remote;
            remote.init(e.eglContext(),()->{
                if(!rendererReady||attached!=e||remote!=target||isDestroyed())return;
                identity.setVisibility(View.GONE);
            });
            stage.addView(remote,0,new FrameLayout.LayoutParams(-1,-1));
            previewFrame=new FrameLayout(this);previewFrame.setPadding(dp(2),dp(2),dp(2),dp(2));
            previewFrame.setBackground(CallVisuals.surface(0xffa4cbd3,0xff426f86,dp(18),0));
            previewFrame.setClipToOutline(true);previewFrame.setClipChildren(true);previewFrame.setClipToPadding(true);
            local.init(e.eglContext(),null);local.setMirror(e.frontCamera);
            local.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View view,Outline outline){outline.setRoundRect(0,0,view.getWidth(),view.getHeight(),dp(16));}});
            local.setClipToOutline(true);
            previewFrame.addView(local,new FrameLayout.LayoutParams(-1,-1));
            FrameLayout.LayoutParams preview=new FrameLayout.LayoutParams(dp(92),dp(128),Gravity.TOP|Gravity.END);
            preview.setMargins(dp(12),dp(12),dp(12),dp(12));stage.addView(previewFrame,preview);
            rendererReady=true;e.attach(local,remote);resizeLocalPreview();
        }
        if(local!=null)local.setMirror(e.frontCamera);
        if(previewFrame!=null)previewFrame.setVisibility(e.cameraEnabled&&!(Build.VERSION.SDK_INT>=26&&isInPictureInPictureMode())?View.VISIBLE:View.INVISIBLE);
        updatePip();
    }
    private String duration(long base){long seconds=Math.max(0,(SystemClock.elapsedRealtime()-base)/1000);return String.format(java.util.Locale.US,"%02d:%02d",seconds/60,seconds%60);}
    private void releaseRenderers(){if(attached!=null)attached.detach(local,remote);attached=null;if(local!=null){local.release();local=null;}if(remote!=null){stage.removeView(remote);remote.release();remote=null;}rendererReady=false;if(previewFrame!=null){stage.removeView(previewFrame);previewFrame=null;}if(identity!=null)identity.setVisibility(View.VISIBLE);}
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
    @Override public void onPictureInPictureModeChanged(boolean pip,Configuration config){super.onPictureInPictureModeChanged(pip,config);pipEntering=false;DriverResponsiveUi.apply(this);applyCallBars();header.setVisibility(pip?View.GONE:View.VISIBLE);controls.setVisibility(pip?View.GONE:View.VISIBLE);actions.setVisibility(pip?View.GONE:View.VISIBLE);applyPadding();if(previewFrame!=null)previewFrame.setVisibility(!pip&&WebRtcCallForegroundService.current()!=null&&WebRtcCallForegroundService.current().cameraEnabled?View.VISIBLE:View.INVISIBLE);WebRtcSessionEngine e=WebRtcCallForegroundService.current();if(e!=null)e.setVisible(pip||resumed);if(pip&&backToChat){backToChat=false;openChat();}}
    private void resizeLocalPreview(){
        if(stage.getHeight()<=0||stage.getWidth()<=0)return;
        int edge=Math.max(1,Math.min(dp(144),Math.min(stage.getWidth()-dp(32),stage.getHeight()-dp(64))));
        FrameLayout.LayoutParams avatarSize=(FrameLayout.LayoutParams)avatar.getLayoutParams();
        if(avatarSize.width!=edge){avatarSize.width=edge;avatarSize.height=edge;avatar.setLayoutParams(avatarSize);}
        stageHint.setVisibility(stage.getHeight()<dp(180)?View.GONE:View.VISIBLE);
        if(previewFrame==null)return;
        FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)previewFrame.getLayoutParams();
        CallPreviewBounds bounds=CallPreviewBounds.fit(stage.getWidth(),stage.getHeight(),dp(92),dp(128),dp(12));
        if(p.width!=bounds.width||p.height!=bounds.height||p.topMargin!=bounds.margin||p.rightMargin!=bounds.margin){
            p.width=bounds.width;p.height=bounds.height;p.setMargins(bounds.margin,bounds.margin,bounds.margin,bounds.margin);
            previewFrame.setLayoutParams(p);
        }
    }
    private void applyPadding(){boolean pip=Build.VERSION.SDK_INT>=26&&isInPictureInPictureMode();root.setPadding(pip?0:dp(18),pip?0:dp(compact?4:10),pip?0:dp(18),pip?0:dp(compact?6:16));
        LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)stage.getLayoutParams();
        int top=pip?0:dp(compact?6:12),bottom=pip?0:dp(compact?6:16);
        if(p.topMargin!=top||p.bottomMargin!=bottom){p.setMargins(0,top,0,bottom);stage.setLayoutParams(p);}
    }
    private void openChat(){WebRtcSessionEngine e=WebRtcCallForegroundService.current();String order=e!=null?e.orderId:getIntent().getStringExtra("order_id");if(order==null||order.isEmpty())return;Intent i=new Intent();i.setClass(this,DriverChatRoomActivity.class);i.putExtra("order_id",order).putExtra("order_source",e!=null?e.source:getIntent().getStringExtra("source")).putExtra("source",e!=null?e.source:getIntent().getStringExtra("source")).putExtra("participant_name",e!=null?e.peer:getIntent().getStringExtra("peer_name"));i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);startActivity(i);}
    @Override protected void onResume(){super.onResume();resumed=true;
        if(settingsOpen){settingsOpen=false;startPending=permission();if(!startPending){permissionRetry("Izin belum diberikan. Anda dapat membukanya lagi melalui Setelan.");}}
        WebRtcSessionEngine e=WebRtcCallForegroundService.current();if(e!=null)e.setVisible(true);render();
        main.removeCallbacks(refresh);main.post(refresh);scheduleStart();
    }
    @Override protected void onPause(){resumed=false;main.removeCallbacks(refresh);main.removeCallbacks(advanceStart);super.onPause();/* Service media remains active; only UI refresh/start is deferred. */}
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
