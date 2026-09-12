package com.transiva.app;

import android.annotation.SuppressLint;

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
public class WebRtcCallActivity extends WebRtcCallActivityLayer1 {

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

        debug("Activity onCreate role=" + role + " incoming=" + incoming + " accepted=" + accepted + " call=" + callId);
        TransivaDiagnostics.event(this, "call", incoming ? "INCOMING_UI_CREATE" : "OUTGOING_UI_CREATE");

        // Incoming-call intents originate from FCM/PendingIntent and can be delivered
        // by aggressive OEM task managers with partially restored state. Never let a
        // non-essential setup step crash the Activity before the user can answer.
        try { IncomingCallAlertManager.stop(callId); }
        catch (Throwable t) { TransivaDiagnostics.error(this, "call", "ALERT_STOP_FAILED", t); }
        try { cancelOwnCallNotification(); }
        catch (Throwable t) { TransivaDiagnostics.error(this, "call", "NOTIFICATION_CANCEL_FAILED", t); }
        try { registerCallStateReceiver(); }
        catch (Throwable t) { TransivaDiagnostics.error(this, "call", "STATE_RECEIVER_REGISTER_FAILED", t); }

        try {
            setContentView(buildUi());
        } catch (Throwable t) {
            TransivaDiagnostics.error(this, "call", "CALL_UI_BUILD_FAILED", t);
            Toast.makeText(this, "Panggilan masuk gagal dibuka. Silakan coba lagi.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Do not switch to MODE_IN_COMMUNICATION while an incoming call is merely
        // ringing. Several OEM audio stacks become unstable when ringtone playback
        // and communication mode are activated simultaneously. Configure audio only
        // once a call is accepted or when starting an outgoing call.
        if (!incoming || accepted) {
            try { configureAudioRoute(); }
            catch (Throwable t) { TransivaDiagnostics.error(this, "call", "AUDIO_ROUTE_INIT_FAILED", t); }
        }

        if (incoming) {
            if (accepted) {
                acceptButton.setVisibility(View.GONE);
                endButton.setText("Akhiri");
                status("Menyambungkan kembali...");
                main.post(pollTask);
                ensureMicrophoneThenResume();
            } else {
                status("Panggilan masuk");
                main.post(pollTask);
                if (autoAccept) {
                    main.post(this::acceptIncoming);
                } else {
                    startRingtone();
                }
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

    protected void readIntent() {
        Intent i = getIntent();
        callId = clean(i.getStringExtra("call_id"));
        orderId = clean(i.getStringExtra("order_id"));
        orderSource = first(i.getStringExtra("source"), i.getStringExtra("order_source"), "orders");
        peerName = first(i.getStringExtra("peer_name"), i.getStringExtra("caller_name"), role.equals("driver") ? "Customer" : "Driver");
        incoming = i.getBooleanExtra("incoming", false);
        autoAccept = i.getBooleanExtra("auto_accept", false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null) return;
        setIntent(intent);

        cancelOwnCallNotification();

        String nextCallId = clean(intent.getStringExtra("call_id"));
        if (!nextCallId.isEmpty() && !callId.isEmpty() && !nextCallId.equals(callId)) {
            // A notification for another call must NEVER terminate or mutate the
            // call that is already visible. Ignore it and let FCM create its own
            // incoming-call notification instead.
            debug("onNewIntent ignored different call=" + nextCallId + " active=" + callId);
            return;
        }

        final boolean hadActiveCall = !callId.isEmpty();
        if (!nextCallId.isEmpty()) callId = nextCallId;
        orderId = first(intent.getStringExtra("order_id"), orderId);
        orderSource = first(intent.getStringExtra("source"), intent.getStringExtra("order_source"), orderSource, "orders");
        peerName = first(intent.getStringExtra("peer_name"), intent.getStringExtra("caller_name"), peerName);

        // Caller/callee role is immutable for the lifetime of one call. In the
        // old code, any WebRTC PendingIntent could turn an outgoing caller into
        // incoming=true after the peer accepted, preventing createOffer().
        if (!hadActiveCall) {
            incoming = intent.getBooleanExtra("incoming", false);
        }
        if (intent.getBooleanExtra("auto_accept", false) && incoming && !accepted) {
            autoAccept = true;
            IncomingCallAlertManager.stop(callId);
            main.post(this::acceptIncoming);
        }

        if (titleView != null && !peerName.isEmpty()) titleView.setText(peerName);
        if (incoming && !accepted && !ended) {
            status("Panggilan masuk");
            startRingtone();
            main.removeCallbacks(pollTask);
            main.post(pollTask);
        }
    }

    protected View buildUi() {
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

    protected void ensureMicrophoneThenResume() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }

        // callId already exists, therefore never create another server-side call.
        loadIceAndStartPeer();
    }

    protected void ensureMicrophoneThenStart() {
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

    protected void startOutgoingCall() {
        debug("SIGNAL start outgoing order=" + orderId + " source=" + orderSource);
        if (orderId.isEmpty()) { toast("Order tidak valid"); finish(); return; }
        safeIo(() -> {
            try {
                JSONObject p = basePayload("start");
                p.put("order_id", orderId);
                p.put("source", orderSource);
                JSONObject r = WebRtcSignalApi.post(session, p);
                callId = r.optString("call_id", "");
                debug("SIGNAL start OK call_id=" + callId);
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

    protected void acceptIncoming() {
        debug("UI acceptIncoming() call=" + callId);
        if (accepted || callId.isEmpty()) return;
        accepted = true;
        stopRingtone();
        try { configureAudioRoute(); }
        catch (Throwable t) { TransivaDiagnostics.error(this, "call", "AUDIO_ROUTE_ACCEPT_FAILED", t); }
        acceptButton.setVisibility(View.GONE);
        endButton.setText("Akhiri");
        status("Menghubungkan audio...");
        safeIo(() -> {
            try {
                WebRtcSignalApi.post(session, basePayload("accept"));
                debug("SIGNAL accept OK");
                runOnUiThread(this::ensureMicrophoneThenStart);
            } catch (Throwable e) { fail(e); }
        });
    }

    protected void loadIceAndStartPeer() {
        debug("RTC loadIceAndStartPeer peerStarted=" + peerStarted + " accepted=" + accepted);
        if (peerStarted || ended) return;
        peerStarted = true;
        safeIo(() -> {
            List<PeerConnection.IceServer> servers = new ArrayList<>();
            try {
                debug("SIGNAL ice_config request");
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
            debug("ICE servers=" + servers.size());
            List<PeerConnection.IceServer> finalServers = servers;
            runOnUiThread(() -> initializePeer(finalServers));
        });
    }

    protected void initializePeer(List<PeerConnection.IceServer> iceServers) {
        debug("RTC initializePeer servers=" + (iceServers == null ? 0 : iceServers.size()));
        if (ended || peerConnection != null || factory != null) return;
        try {
            debug("RTC PeerConnectionFactory.initialize");
            ensureRtcFactoryInitialized();
            debug("AUDIO create JavaAudioDeviceModule");
            audioDeviceModule = JavaAudioDeviceModule.builder(getApplicationContext())
                    .setUseHardwareAcousticEchoCanceler(false)
                    .setUseHardwareNoiseSuppressor(false)
                    .createAudioDeviceModule();
            factory = PeerConnectionFactory.builder().setAudioDeviceModule(audioDeviceModule).createPeerConnectionFactory();
            debug("RTC factory created");
            audioSource = factory.createAudioSource(new MediaConstraints());
            localAudioTrack = factory.createAudioTrack("TRANSIVA_AUDIO", audioSource);
            debug("AUDIO local track created");
            localAudioTrack.setEnabled(true);

            PeerConnection.RTCConfiguration cfg = new PeerConnection.RTCConfiguration(iceServers);
            cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
            peerConnection = factory.createPeerConnection(cfg, new PeerObserver());
            debug("RTC peerConnection=" + (peerConnection != null));
            if (peerConnection == null) throw new IllegalStateException("PeerConnection gagal dibuat");
            peerConnection.addTrack(localAudioTrack, Collections.singletonList("transiva_audio"));
            debug("AUDIO addTrack OK");
            configureAudioRoute();
            if (!incoming) createOffer();
        } catch (Throwable e) { fail(e); }
    }

    protected void createOffer() {
        debug("SDP createOffer requested");
        if (offerCreated || peerConnection == null) return;
        offerCreated = true;
        MediaConstraints c = new MediaConstraints();
        c.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        c.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));
        peerConnection.createOffer(new SimpleSdpObserver() {
            @Override public void onCreateSuccess(SessionDescription sdp) {
                debug("SDP offer created bytes=" + (sdp == null || sdp.description == null ? 0 : sdp.description.length()));
                peerConnection.setLocalDescription(new SimpleSdpObserver() {
                    @Override public void onSetSuccess() { debug("SDP local offer SET"); postSdp("offer", sdp.description); }
                    @Override public void onSetFailure(String error) { fail(new IllegalStateException("SDP local offer SET gagal: " + error)); }
                }, sdp);
            }
            @Override public void onCreateFailure(String error) { fail(new IllegalStateException("SDP offer create gagal: " + error)); }
        }, c);
    }

    protected void createAnswer() {
        debug("SDP createAnswer requested");
        if (answerCreated || peerConnection == null) return;
        answerCreated = true;
        MediaConstraints c = new MediaConstraints();
        c.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        peerConnection.createAnswer(new SimpleSdpObserver() {
            @Override public void onCreateSuccess(SessionDescription sdp) {
                debug("SDP answer created bytes=" + (sdp == null || sdp.description == null ? 0 : sdp.description.length()));
                peerConnection.setLocalDescription(new SimpleSdpObserver() {
                    @Override public void onSetSuccess() { debug("SDP local answer SET"); postSdp("answer", sdp.description); }
                    @Override public void onSetFailure(String error) { fail(new IllegalStateException("SDP local answer SET gagal: " + error)); }
                }, sdp);
            }
            @Override public void onCreateFailure(String error) { fail(new IllegalStateException("SDP answer create gagal: " + error)); }
        }, c);
    }

    protected void postSdp(String action, String sdp) {
        debug("SIGNAL post SDP " + action + " bytes=" + (sdp == null ? 0 : sdp.length()));
        safeIo(() -> {
            try { JSONObject p = basePayload(action); p.put("sdp", sdp); WebRtcSignalApi.post(session, p); }
            catch (Throwable e) { fail(e); }
        });
    }

    protected void postCandidate(IceCandidate c) {
        debug("ICE local candidate mid=" + (c == null ? "?" : c.sdpMid) + " line=" + (c == null ? -1 : c.sdpMLineIndex));
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

    protected void pollSignal() {
        safeIo(() -> {
            try {
                JSONObject p = basePayload("poll");
                p.put("candidate_after", lastCandidateId);
                JSONObject r = WebRtcSignalApi.post(session, p);
                String st = r.optString("status", "");
                if (!st.equalsIgnoreCase(lastServerStatus)) { lastServerStatus = st; debug("SIGNAL server status=" + st); }
                String offer = r.optString("offer_sdp", "");
                String answer = r.optString("answer_sdp", "");
                JSONArray candidates = r.optJSONArray("candidates");
                lastCandidateId = r.optInt("candidate_last", lastCandidateId);
                String serverPeer = r.optString("peer_name", "");
                runOnUiThread(() -> {
                    if (candidates != null && candidates.length() > 0) debug("ICE remote candidates batch=" + candidates.length() + " last=" + lastCandidateId);
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
                debug("SIGNAL poll ERROR " + e.getClass().getSimpleName() + ": " + clean(e.getMessage()));
                if (!ended) runOnUiThread(() -> status("Menyambungkan kembali..."));
            }
        });
    }
}
