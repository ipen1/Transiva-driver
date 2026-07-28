package com.transiva.app;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Chronometer;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WebRtcCallActivity extends Activity {
    private static final int REQ_MIC = 7101;
    private static final long POLL_MS = 900L;
    public static final String ACTION_CALL_STATE = "com.transiva.app.WEBRTC_CALL_STATE";
    public static final String EXTRA_CALL_ID = "call_id";
    public static final String EXTRA_CALL_STATUS = "call_status";
    private static final String STATE_CALL_ID = "wr_call_id";
    private static final String STATE_ORDER_ID = "wr_order_id";
    private static final String STATE_SOURCE = "wr_source";
    private static final String STATE_PEER = "wr_peer";
    private static final String STATE_INCOMING = "wr_incoming";
    private static final String STATE_ACCEPTED = "wr_accepted";
    private static final Object RTC_INIT_LOCK = new Object();
    private static boolean rtcFactoryInitialized;


    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<IceCandidate> pendingRemoteCandidates = new ArrayList<>();

    private SessionManager session;
    private String role;
    private String orderId = "";
    private String orderSource = "orders";
    private String callId = "";
    private String peerName = "";
    private boolean incoming;
    private boolean accepted;
    private boolean ended;
    private boolean peerStarted;
    private boolean offerCreated;
    private boolean answerCreated;
    private boolean remoteDescriptionSet;
    private boolean rtcStartRequested;
    private boolean muted;
    private boolean speaker = true;
    private int lastCandidateId;

    private PeerConnectionFactory factory;
    private PeerConnection peerConnection;
    private JavaAudioDeviceModule audioDeviceModule;
    private AudioSource audioSource;
    private AudioTrack localAudioTrack;
    private AudioManager audioManager;
    private Ringtone ringtone;

    private TextView titleView;
    private TextView statusView;
    private Chronometer timerView;
    private Button acceptButton;
    private Button endButton;
    private Button muteButton;
    private Button speakerButton;

    private boolean receiverRegistered;
    private volatile boolean destroyed;
    private int rtcRetryCount;
    private static final int MAX_RTC_RETRIES = 5;

    private final Runnable rtcRetryTask = new Runnable() {
        @Override public void run() {
            if (destroyed || ended || !accepted || callId.isEmpty()) return;
            status("Mencoba menyambungkan audio kembali...");
            resetRtcForRetry();
            loadIceAndStartPeer();
        }
    };

    private final BroadcastReceiver callStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || ended) return;
            String eventCallId = clean(intent.getStringExtra(EXTRA_CALL_ID));
            String eventStatus = clean(intent.getStringExtra(EXTRA_CALL_STATUS)).toLowerCase();
            if (eventCallId.isEmpty() || !eventCallId.equals(callId)) return;
            handleTerminalStatus(eventStatus, true);
        }
    };

    private final Runnable pollTask = new Runnable() {
        @Override public void run() {
            if (ended || destroyed || callId.isEmpty()) return;
            pollSignal();
            if (!ended && !destroyed) main.postDelayed(this, POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);

        session = new SessionManager(this);
        role = getApplicationContext().getPackageName().endsWith(".driver") ? "driver" : "customer";

        if (savedInstanceState != null) {
            callId = clean(savedInstanceState.getString(STATE_CALL_ID, ""));
            orderId = clean(savedInstanceState.getString(STATE_ORDER_ID, ""));
            orderSource = first(savedInstanceState.getString(STATE_SOURCE, ""), "orders");
            peerName = first(
                    savedInstanceState.getString(STATE_PEER, ""),
                    role.equals("driver") ? "Customer" : "Driver"
            );
            incoming = savedInstanceState.getBoolean(STATE_INCOMING, false);
            accepted = savedInstanceState.getBoolean(STATE_ACCEPTED, false);
        } else {
            readIntent();
        }

        cancelOwnCallNotification();
        registerCallStateReceiver();
        setContentView(buildUi());
        configureAudioRoute();

        if (incoming) {
            if (accepted) {
                acceptButton.setVisibility(View.GONE);
                endButton.setText("Akhiri");
                status("Menyambungkan kembali...");
                main.post(pollTask);
                ensureMicrophoneThenResume();
            } else {
                status("Panggilan masuk");
                startRingtone();
                main.post(pollTask);
            }
        } else if (!callId.isEmpty()) {
            // Existing outgoing call after Android recreated this Activity.
            status("Menyambungkan kembali...");
            main.post(pollTask);
            ensureMicrophoneThenResume();
        } else {
            status("Menghubungkan...");
            ensureMicrophoneThenStart();
        }
    }

    private void readIntent() {
        Intent i = getIntent();
        callId = clean(i.getStringExtra("call_id"));
        orderId = clean(i.getStringExtra("order_id"));
        orderSource = first(i.getStringExtra("source"), i.getStringExtra("order_source"), "orders");
        peerName = first(i.getStringExtra("peer_name"), i.getStringExtra("caller_name"), role.equals("driver") ? "Customer" : "Driver");
        incoming = i.getBooleanExtra("incoming", !callId.isEmpty());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null) return;
        setIntent(intent);

        cancelOwnCallNotification();

        String nextCallId = clean(intent.getStringExtra("call_id"));
        if (!nextCallId.isEmpty() && !callId.isEmpty() && !nextCallId.equals(callId)) {
            // Never mix signaling from two calls in one Activity instance.
            finishCall(callId.isEmpty() ? "" : (incoming && !accepted ? "reject" : "end"), true);
            return;
        }

        if (!nextCallId.isEmpty()) callId = nextCallId;
        orderId = first(intent.getStringExtra("order_id"), orderId);
        orderSource = first(intent.getStringExtra("source"), intent.getStringExtra("order_source"), orderSource, "orders");
        peerName = first(intent.getStringExtra("peer_name"), intent.getStringExtra("caller_name"), peerName);
        incoming = intent.getBooleanExtra("incoming", incoming || !callId.isEmpty());

        if (titleView != null && !peerName.isEmpty()) titleView.setText(peerName);
        if (incoming && !accepted && !ended) {
            status("Panggilan masuk");
            startRingtone();
            main.removeCallbacks(pollTask);
            main.post(pollTask);
        }
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(28), dp(52), dp(28), dp(28));
        root.setBackgroundColor(Color.parseColor("#07131F"));

        TextView badge = text("T", 36, Color.WHITE, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(circle("#0B7CFF"));
        root.addView(badge, new LinearLayout.LayoutParams(dp(92), dp(92)));

        titleView = text(peerName, 24, Color.WHITE, true);
        titleView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(-1, -2);
        nameLp.setMargins(0, dp(24), 0, 0);
        root.addView(titleView, nameLp);

        statusView = text("Menyiapkan panggilan...", 15, Color.parseColor("#B7C7D8"), false);
        statusView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
        statusLp.setMargins(0, dp(8), 0, 0);
        root.addView(statusView, statusLp);

        timerView = new Chronometer(this);
        timerView.setTextColor(Color.WHITE);
        timerView.setTextSize(18);
        timerView.setGravity(Gravity.CENTER);
        timerView.setVisibility(View.GONE);
        LinearLayout.LayoutParams timerLp = new LinearLayout.LayoutParams(-1, -2);
        timerLp.setMargins(0, dp(10), 0, 0);
        root.addView(timerView, timerLp);

        View spacer = new View(this);
        root.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);

        muteButton = button("🎙 Mic", "#18334A");
        speakerButton = button("🔊 Speaker", "#18334A");
        controls.addView(muteButton, controlLp());
        controls.addView(speakerButton, controlLp());
        root.addView(controls, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(-1, -2);
        actionsLp.setMargins(0, dp(22), 0, dp(18));
        root.addView(actions, actionsLp);

        acceptButton = button("Terima", "#16A765");
        endButton = button(incoming ? "Tolak" : "Batalkan", "#E44343");
        if (incoming) actions.addView(acceptButton, actionLp());
        actions.addView(endButton, actionLp());

        acceptButton.setOnClickListener(v -> acceptIncoming());
        endButton.setOnClickListener(v -> finishCall(incoming && !accepted ? "reject" : "end", true));
        muteButton.setOnClickListener(v -> toggleMute());
        speakerButton.setOnClickListener(v -> toggleSpeaker());
        return root;
    }

    private void ensureMicrophoneThenResume() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }

        // callId already exists, therefore never create another server-side call.
        loadIceAndStartPeer();
    }

    private void ensureMicrophoneThenStart() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (incoming && accepted) loadIceAndStartPeer();
        else if (!incoming) startOutgoingCall();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (incoming && accepted) {
                    loadIceAndStartPeer();
                } else if (!incoming) {
                    if (callId.isEmpty()) startOutgoingCall();
                    else loadIceAndStartPeer();
                }
            } else {
                toast("Izin mikrofon diperlukan untuk panggilan online.");
                finishCall(callId.isEmpty() ? "" : (incoming ? "reject" : "end"), true);
            }
        }
    }

    private void startOutgoingCall() {
        if (orderId.isEmpty()) { toast("Order tidak valid"); finish(); return; }
        safeIo(() -> {
            try {
                JSONObject p = basePayload("start");
                p.put("order_id", orderId);
                p.put("source", orderSource);
                JSONObject r = WebRtcSignalApi.post(session, p);
                callId = r.optString("call_id", "");
                peerName = first(r.optString("peer_name", ""), peerName);
                runOnUiThread(() -> {
                    titleView.setText(peerName);
                    status("Memanggil " + peerName + "...");
                    // Keep the caller on the ringing screen. WebRTC/audio is
                    // started only after the callee has actually accepted.
                    main.removeCallbacks(pollTask);
                    main.post(pollTask);
                });
            } catch (Throwable e) { fail(e); }
        });
    }

    private void acceptIncoming() {
        if (accepted || callId.isEmpty()) return;
        accepted = true;
        stopRingtone();
        acceptButton.setVisibility(View.GONE);
        endButton.setText("Akhiri");
        status("Menghubungkan audio...");
        safeIo(() -> {
            try {
                WebRtcSignalApi.post(session, basePayload("accept"));
                runOnUiThread(this::ensureMicrophoneThenStart);
            } catch (Throwable e) { fail(e); }
        });
    }

    private void loadIceAndStartPeer() {
        if (peerStarted || ended) return;
        peerStarted = true;
        safeIo(() -> {
            List<PeerConnection.IceServer> servers = new ArrayList<>();
            try {
                JSONObject r = WebRtcSignalApi.post(session, basePayload("ice_config"));
                JSONArray arr = r.optJSONArray("ice_servers");
                if (arr != null) {
                    for (int n = 0; n < arr.length(); n++) {
                        JSONObject s = arr.optJSONObject(n); if (s == null) continue;
                        JSONArray urls = s.optJSONArray("urls");
                        String username = s.optString("username", "");
                        String credential = s.optString("credential", "");
                        if (urls != null && urls.length() > 0) {
                            for (int j = 0; j < urls.length(); j++) {
                                String url = urls.optString(j, "");
                                if (url.isEmpty()) continue;
                                PeerConnection.IceServer.Builder b = PeerConnection.IceServer.builder(url);
                                if (!username.isEmpty()) b.setUsername(username);
                                if (!credential.isEmpty()) b.setPassword(credential);
                                servers.add(b.createIceServer());
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
            if (servers.isEmpty()) {
                servers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
                servers.add(PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer());
            }
            List<PeerConnection.IceServer> finalServers = servers;
            runOnUiThread(() -> initializePeer(finalServers));
        });
    }

    private void initializePeer(List<PeerConnection.IceServer> iceServers) {
        if (ended || peerConnection != null || factory != null) return;
        try {
            ensureRtcFactoryInitialized();
            audioDeviceModule = JavaAudioDeviceModule.builder(getApplicationContext())
                    .setUseHardwareAcousticEchoCanceler(false)
                    .setUseHardwareNoiseSuppressor(false)
                    .createAudioDeviceModule();
            factory = PeerConnectionFactory.builder().setAudioDeviceModule(audioDeviceModule).createPeerConnectionFactory();
            audioSource = factory.createAudioSource(new MediaConstraints());
            localAudioTrack = factory.createAudioTrack("TRANSIVA_AUDIO", audioSource);
            localAudioTrack.setEnabled(true);

            PeerConnection.RTCConfiguration cfg = new PeerConnection.RTCConfiguration(iceServers);
            cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
            peerConnection = factory.createPeerConnection(cfg, new PeerObserver());
            if (peerConnection == null) throw new IllegalStateException("PeerConnection gagal dibuat");
            peerConnection.addTrack(localAudioTrack, Collections.singletonList("transiva_audio"));
            configureAudioRoute();
            if (!incoming) createOffer();
        } catch (Throwable e) { fail(e); }
    }

    private void createOffer() {
        if (offerCreated || peerConnection == null) return;
        offerCreated = true;
        MediaConstraints c = new MediaConstraints();
        c.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        c.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));
        peerConnection.createOffer(new SimpleSdpObserver() {
            @Override public void onCreateSuccess(SessionDescription sdp) {
                peerConnection.setLocalDescription(new SimpleSdpObserver() {
                    @Override public void onSetSuccess() { postSdp("offer", sdp.description); }
                }, sdp);
            }
        }, c);
    }

    private void createAnswer() {
        if (answerCreated || peerConnection == null) return;
        answerCreated = true;
        MediaConstraints c = new MediaConstraints();
        c.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        peerConnection.createAnswer(new SimpleSdpObserver() {
            @Override public void onCreateSuccess(SessionDescription sdp) {
                peerConnection.setLocalDescription(new SimpleSdpObserver() {
                    @Override public void onSetSuccess() { postSdp("answer", sdp.description); }
                }, sdp);
            }
        }, c);
    }

    private void postSdp(String action, String sdp) {
        safeIo(() -> {
            try { JSONObject p = basePayload(action); p.put("sdp", sdp); WebRtcSignalApi.post(session, p); }
            catch (Throwable e) { fail(e); }
        });
    }

    private void postCandidate(IceCandidate c) {
        safeIo(() -> {
            try {
                JSONObject p = basePayload("candidate");
                p.put("candidate", c.sdp);
                p.put("sdp_mid", c.sdpMid == null ? "" : c.sdpMid);
                p.put("sdp_mline_index", c.sdpMLineIndex);
                WebRtcSignalApi.post(session, p);
            } catch (Throwable ignored) {}
        });
    }

    private void pollSignal() {
        safeIo(() -> {
            try {
                JSONObject p = basePayload("poll");
                p.put("candidate_after", lastCandidateId);
                JSONObject r = WebRtcSignalApi.post(session, p);
                String st = r.optString("status", "");
                String offer = r.optString("offer_sdp", "");
                String answer = r.optString("answer_sdp", "");
                JSONArray candidates = r.optJSONArray("candidates");
                lastCandidateId = r.optInt("candidate_last", lastCandidateId);
                String serverPeer = r.optString("peer_name", "");
                runOnUiThread(() -> {
                    if (!serverPeer.isEmpty()) { peerName = serverPeer; titleView.setText(peerName); }
                    handleStatus(st);
                    if (peerConnection != null) {
                        if (incoming && accepted && !offer.isEmpty() && !remoteDescriptionSet) {
                            setRemote(new SessionDescription(SessionDescription.Type.OFFER, offer), this::createAnswer);
                        } else if (!incoming && !answer.isEmpty() && !remoteDescriptionSet) {
                            setRemote(new SessionDescription(SessionDescription.Type.ANSWER, answer), null);
                        }
                        if (candidates != null) {
                            for (int n = 0; n < candidates.length(); n++) {
                                JSONObject x = candidates.optJSONObject(n); if (x == null) continue;
                                IceCandidate ice = new IceCandidate(x.optString("sdp_mid", ""), x.optInt("sdp_mline_index", 0), x.optString("candidate", ""));
                                addRemoteCandidate(ice);
                            }
                        }
                    }
                });
            } catch (Exception e) {
                if (!ended) runOnUiThread(() -> status("Menyambungkan kembali..."));
            }
        });
    }

    private void setRemote(SessionDescription sdp, Runnable after) {
        if (peerConnection == null || remoteDescriptionSet) return;
        peerConnection.setRemoteDescription(new SimpleSdpObserver() {
            @Override public void onSetSuccess() {
                remoteDescriptionSet = true;
                for (IceCandidate c : pendingRemoteCandidates) peerConnection.addIceCandidate(c);
                pendingRemoteCandidates.clear();
                if (after != null) after.run();
            }
            @Override public void onSetFailure(String error) { fail(new IllegalStateException("SDP remote gagal: " + error)); }
        }, sdp);
    }

    private void addRemoteCandidate(IceCandidate candidate) {
        if (candidate.sdp == null || candidate.sdp.isEmpty()) return;
        if (remoteDescriptionSet && peerConnection != null) peerConnection.addIceCandidate(candidate);
        else pendingRemoteCandidates.add(candidate);
    }

    private void handleStatus(String st) {
        if (ended) return;
        st = clean(st).toLowerCase();
        if ("accepted".equals(st)) {
            // Accepted is a call-wide state, not only a callee-side UI state.
            // Keeping this flag on the caller lets us retry WebRTC without
            // accidentally closing the call screen.
            accepted = true;
            if (!incoming) {
                status("Diterima, menyambungkan audio...");
                if (!peerStarted && !rtcStartRequested) {
                    rtcStartRequested = true;
                    ensureMicrophoneThenResume();
                }
            }
        }
        handleTerminalStatus(st, false);
    }

    private void handleTerminalStatus(String st, boolean fromPush) {
        if (ended) return;
        if ("rejected".equals(st)) {
            toast("Panggilan ditolak");
            finishCall("", false);
        } else if ("ended".equals(st) || "cancelled".equals(st) || "canceled".equals(st)) {
            toast("Panggilan berakhir");
            finishCall("", false);
        } else if ("missed".equals(st) || "timeout".equals(st)) {
            toast("Panggilan tidak terjawab");
            finishCall("", false);
        }
    }

    private void safeIo(Runnable task) {
        if (task == null || destroyed || ended || io.isShutdown()) return;
        try {
            io.execute(() -> {
                if (destroyed || ended) return;
                try {
                    task.run();
                } catch (Throwable t) {
                    // Do not let an executor callback kill the process.
                    fail(t);
                }
            });
        } catch (Throwable ignored) {
            // Activity may be shutting down between the check and execute().
        }
    }

    private void terminalIo(Runnable task) {
        if (task == null || destroyed || io.isShutdown()) {
            return;
        }
        try {
            io.execute(task);
        } catch (Throwable ignored) {
            // Shutdown race: the UI will still be closed safely.
        }
    }

    private void ensureRtcFactoryInitialized() {
        synchronized (RTC_INIT_LOCK) {
            if (rtcFactoryInitialized) return;
            PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions
                            .builder(getApplicationContext())
                            .createInitializationOptions()
            );
            rtcFactoryInitialized = true;
        }
    }

    private JSONObject basePayload(String action) throws Exception {
        JSONObject p = new JSONObject();
        p.put("action", action);
        p.put("role", role);
        if (!callId.isEmpty()) p.put("call_id", callId);
        if (role.equals("customer")) p.put("user_id", session.getUserId());
        return p;
    }

    private void finishCall(String action, boolean closeNow) {
        if (ended) return;
        ended = true;
        stopRingtone();
        main.removeCallbacks(pollTask);
        main.removeCallbacks(rtcRetryTask);

        final Runnable closeUi = () -> {
            releaseRtc();
            if (closeNow) finish();
            else main.postDelayed(this::finish, 180);
        };

        if (!action.isEmpty() && !callId.isEmpty()) {
            // IMPORTANT: do not finish the Activity before this terminal signal
            // reaches the backend. Otherwise onDestroy() may cancel the executor
            // and the peer can remain stuck on the call screen.
            terminalIo(() -> {
                try {
                    WebRtcSignalApi.post(session, basePayload(action));
                } catch (Exception ignored) {
                    // Polling/timeout on the peer remains the fallback.
                } finally {
                    runOnUiThread(closeUi);
                }
            });
        } else {
            closeUi.run();
        }
    }

    private void releaseRtc() {
        try { if (peerConnection != null) { peerConnection.close(); peerConnection.dispose(); } } catch (Throwable ignored) {}
        peerConnection = null;
        try { if (localAudioTrack != null) localAudioTrack.dispose(); } catch (Throwable ignored) {}
        try { if (audioSource != null) audioSource.dispose(); } catch (Throwable ignored) {}
        try { if (factory != null) factory.dispose(); } catch (Throwable ignored) {}
        try { if (audioDeviceModule != null) audioDeviceModule.release(); } catch (Throwable ignored) {}
        if (audioManager != null) {
            audioManager.setSpeakerphoneOn(false);
            audioManager.setMode(AudioManager.MODE_NORMAL);
        }
    }


    private void registerCallStateReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter(ACTION_CALL_STATE);
        ContextCompat.registerReceiver(
                this,
                callStateReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
        );
        receiverRegistered = true;
    }

    private void unregisterCallStateReceiver() {
        if (!receiverRegistered) return;
        try { unregisterReceiver(callStateReceiver); } catch (Throwable ignored) {}
        receiverRegistered = false;
    }

    private void toggleMute() {
        muted = !muted;
        if (localAudioTrack != null) localAudioTrack.setEnabled(!muted);
        muteButton.setText(muted ? "🔇 Muted" : "🎙 Mic");
    }

    private void toggleSpeaker() {
        speaker = !speaker;
        if (audioManager != null) audioManager.setSpeakerphoneOn(speaker);
        speakerButton.setText(speaker ? "🔊 Speaker" : "🔈 Earpiece");
    }

    private void configureAudioRoute() {
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(speaker);
        }
    }

    private void startRingtone() {
        try {
            // The Activity is the single owner of call audio. FCM notifications
            // are intentionally silent, preventing double ringtone.
            if (ringtone != null && ringtone.isPlaying()) return;
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            ringtone = RingtoneManager.getRingtone(getApplicationContext(), uri);
            if (ringtone != null && !ringtone.isPlaying()) ringtone.play();
        } catch (Throwable ignored) {}
    }

    private void stopRingtone() {
        try { if (ringtone != null && ringtone.isPlaying()) ringtone.stop(); } catch (Throwable ignored) {}
        ringtone = null;
    }

    private void cancelOwnCallNotification() {
        String id = clean(callId);
        if (id.isEmpty() && getIntent() != null) {
            id = clean(getIntent().getStringExtra("call_id"));
        }
        if (id.isEmpty()) return;
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(Math.abs(("webrtc_call|" + id).hashCode()));
        } catch (Throwable ignored) {}
    }

    private void connected() {
        main.removeCallbacks(rtcRetryTask);
        rtcRetryCount = 0;
        stopRingtone();
        status("Panggilan tersambung");
        timerView.setBase(SystemClock.elapsedRealtime());
        timerView.setVisibility(View.VISIBLE);
        timerView.start();
        acceptButton.setVisibility(View.GONE);
        endButton.setText("Akhiri");
    }

    private void status(String text) { if (statusView != null) statusView.setText(text); }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private void fail(Throwable e) {
        if (destroyed || ended) return;
        runOnUiThread(() -> {
            if (destroyed || ended) return;
            String message = (e == null || e.getMessage() == null || e.getMessage().trim().isEmpty())
                    ? "Audio belum tersambung"
                    : e.getMessage().trim();

            // A WebRTC/SDP/ICE failure is NOT the same as the other person
            // hanging up. Never close the call Activity automatically here.
            status("Audio belum tersambung. Mencoba kembali...");

            if (accepted && !callId.isEmpty() && rtcRetryCount < MAX_RTC_RETRIES) {
                rtcRetryCount++;
                main.removeCallbacks(rtcRetryTask);
                main.postDelayed(rtcRetryTask, 1800L);
            } else if (accepted && rtcRetryCount >= MAX_RTC_RETRIES) {
                status("Panggilan tetap aktif, tetapi audio belum tersambung. Tekan Akhiri untuk menutup.");
                toast("Koneksi audio belum berhasil: " + message);
            } else {
                status("Menyambungkan...");
            }
        });
    }

    private void resetRtcForRetry() {
        main.removeCallbacks(rtcRetryTask);
        releaseRtc();
        peerStarted = false;
        rtcStartRequested = false;
        offerCreated = false;
        answerCreated = false;
        remoteDescriptionSet = false;
        pendingRemoteCandidates.clear();
    }

    @Override
    public void onBackPressed() {
        finishCall(
                callId.isEmpty() ? "" : (incoming && !accepted ? "reject" : "end"),
                true
        );
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString(STATE_CALL_ID, callId);
        outState.putString(STATE_ORDER_ID, orderId);
        outState.putString(STATE_SOURCE, orderSource);
        outState.putString(STATE_PEER, peerName);
        outState.putBoolean(STATE_INCOMING, incoming);
        outState.putBoolean(STATE_ACCEPTED, accepted);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        unregisterCallStateReceiver();
        main.removeCallbacks(pollTask);
        main.removeCallbacks(rtcRetryTask);
        stopRingtone();

        // Android/OEM may destroy and recreate this Activity without the user
        // hanging up. Never send "end" from onDestroy().
        releaseRtc();
        io.shutdown();
        super.onDestroy();
    }

    private class PeerObserver implements PeerConnection.Observer {
        @Override public void onSignalingChange(PeerConnection.SignalingState newState) {}
        @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
            runOnUiThread(() -> {
                if (state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED) {
                    connected();
                } else if (state == PeerConnection.IceConnectionState.FAILED) {
                    // Do not treat a transport failure as a hang-up. Mobile
                    // networks often briefly report FAILED while switching
                    // Wi-Fi/data or while TURN/STUN negotiation is retried.
                    status("Koneksi audio gagal, mencoba kembali...");
                    if (accepted && rtcRetryCount < MAX_RTC_RETRIES) {
                        rtcRetryCount++;
                        main.removeCallbacks(rtcRetryTask);
                        main.postDelayed(rtcRetryTask, 1800L);
                    } else {
                        status("Panggilan tetap aktif, audio belum tersambung.");
                    }
                } else if (state == PeerConnection.IceConnectionState.DISCONNECTED) {
                    status("Koneksi terputus, mencoba kembali...");
                }
            });
        }
        @Override public void onIceConnectionReceivingChange(boolean receiving) {}
        @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) {}
        @Override public void onIceCandidate(IceCandidate candidate) { postCandidate(candidate); }
        @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
        @Override public void onAddStream(MediaStream stream) {}
        @Override public void onRemoveStream(MediaStream stream) {}
        @Override public void onDataChannel(DataChannel dataChannel) {}
        @Override public void onRenegotiationNeeded() {}
        @Override public void onAddTrack(RtpReceiver receiver, MediaStream[] mediaStreams) {}
    }

    private static class SimpleSdpObserver implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription sdp) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String error) {}
        @Override public void onSetFailure(String error) {}
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); return t;
    }
    private Button button(String value, String color) {
        Button b = new Button(this); b.setText(value); b.setTextColor(Color.WHITE); b.setTextSize(14); b.setAllCaps(false); b.setBackground(round(color, 28)); return b;
    }
    private LinearLayout.LayoutParams controlLp() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(54), 1f); p.setMargins(dp(6),0,dp(6),0); return p; }
    private LinearLayout.LayoutParams actionLp() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(58), 1f); p.setMargins(dp(8),0,dp(8),0); return p; }
    private GradientDrawable round(String color, int radius) { GradientDrawable g=new GradientDrawable();g.setColor(Color.parseColor(color));g.setCornerRadius(dp(radius));return g; }
    private GradientDrawable circle(String color) { GradientDrawable g=round(color,50);g.setShape(GradientDrawable.OVAL);return g; }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private static String clean(String v) { return v == null ? "" : v.trim(); }
    private static String first(String... values) { if (values != null) for (String v : values) if (v != null && !v.trim().isEmpty()) return v.trim(); return ""; }
}
