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
abstract class WebRtcCallActivityLayer1 extends Activity {
    protected static final int REQ_MIC = 7101;
    protected static final long POLL_MS = 900L;
    public static final String ACTION_CALL_STATE = "com.transiva.app.WEBRTC_CALL_STATE";
    public static final String EXTRA_CALL_ID = "call_id";
    public static final String EXTRA_CALL_STATUS = "call_status";
    protected static final String STATE_CALL_ID = "wr_call_id";
    protected static final String STATE_ORDER_ID = "wr_order_id";
    protected static final String STATE_SOURCE = "wr_source";
    protected static final String STATE_PEER = "wr_peer";
    protected static final String STATE_INCOMING = "wr_incoming";
    protected static final String STATE_ACCEPTED = "wr_accepted";
    protected static final Object RTC_INIT_LOCK = new Object();
    protected static boolean rtcFactoryInitialized;


    protected final Handler main = new Handler(Looper.getMainLooper());
    protected final ExecutorService io = Executors.newSingleThreadExecutor();
    protected final List<IceCandidate> pendingRemoteCandidates = new ArrayList<>();

    protected SessionManager session;
    protected String role;
    protected String orderId = "";
    protected String orderSource = "orders";
    protected String callId = "";
    protected String peerName = "";
    protected boolean incoming;
    protected boolean accepted;
    protected boolean autoAccept;
    protected boolean ended;
    protected boolean peerStarted;
    protected boolean offerCreated;
    protected boolean answerCreated;
    protected boolean remoteDescriptionSet;
    protected boolean rtcStartRequested;
    protected boolean muted;
    protected boolean speaker = true;
    protected int lastCandidateId;
    protected String lastServerStatus = "";

    protected PeerConnectionFactory factory;
    protected PeerConnection peerConnection;
    protected JavaAudioDeviceModule audioDeviceModule;
    protected AudioSource audioSource;
    protected AudioTrack localAudioTrack;
    protected AudioManager audioManager;
    protected Ringtone ringtone;

    protected TextView titleView;
    protected TextView statusView;
    protected Chronometer timerView;
    protected Button acceptButton;
    protected Button endButton;
    protected Button muteButton;
    protected Button speakerButton;
    protected boolean userRequestedClose;
    protected boolean everConnected;

    protected boolean receiverRegistered;
    protected volatile boolean destroyed;
    protected int rtcRetryCount;
    protected static final int MAX_RTC_RETRIES = 5;

    protected final Runnable rtcRetryTask = new Runnable() {
        @Override public void run() {
            if (destroyed || ended || !accepted || callId.isEmpty()) return;
            status("Mencoba menyambungkan audio kembali...");
            resetRtcForRetry();
            loadIceAndStartPeer();
        }
    };

    protected final BroadcastReceiver callStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || ended) return;
            String eventCallId = clean(intent.getStringExtra(EXTRA_CALL_ID));
            String eventStatus = clean(intent.getStringExtra(EXTRA_CALL_STATUS)).toLowerCase();
            if (eventCallId.isEmpty() || !eventCallId.equals(callId)) return;
            if ("accepted".equals(eventStatus)) {
                handleStatus("accepted");
                return;
            }
            handleTerminalStatus(eventStatus, true);
        }
    };

    protected final Runnable pollTask = new Runnable() {
        @Override public void run() {
            if (ended || destroyed || callId.isEmpty()) return;
            pollSignal();
            if (!ended && !destroyed) main.postDelayed(this, POLL_MS);
        }
    };

    protected class PeerObserver implements PeerConnection.Observer {
        @Override public void onSignalingChange(PeerConnection.SignalingState newState) { debug("RTC signaling=" + newState); }
        @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
            debug("ICE connection=" + state);
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
        @Override public void onIceConnectionReceivingChange(boolean receiving) { debug("ICE receiving=" + receiving); }
        @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) { debug("ICE gathering=" + state); }
        @Override public void onIceCandidate(IceCandidate candidate) { postCandidate(candidate); }
        @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
        @Override public void onAddStream(MediaStream stream) {}
        @Override public void onRemoveStream(MediaStream stream) {}
        @Override public void onDataChannel(DataChannel dataChannel) {}
        @Override public void onRenegotiationNeeded() { debug("RTC renegotiation needed"); }
        @Override public void onAddTrack(RtpReceiver receiver, MediaStream[] mediaStreams) { debug("AUDIO remote track added"); }
    }

    protected static class SimpleSdpObserver implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription sdp) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String error) {}
        @Override public void onSetFailure(String error) {}
    }
    protected static String clean(String v) { return v == null ? "" : v.trim(); }
    protected static String first(String... values) { if (values != null) for (String v : values) if (v != null && !v.trim().isEmpty()) return v.trim(); return ""; }

    protected void setRemote(SessionDescription sdp, Runnable after) {
        debug("SDP setRemote type=" + (sdp == null ? "?" : sdp.type));
        if (peerConnection == null || remoteDescriptionSet) return;
        peerConnection.setRemoteDescription(new SimpleSdpObserver() {
            @Override public void onSetSuccess() {
                remoteDescriptionSet = true;
                debug("SDP remote SET OK");
                for (IceCandidate c : pendingRemoteCandidates) peerConnection.addIceCandidate(c);
                pendingRemoteCandidates.clear();
                if (after != null) after.run();
            }
            @Override public void onSetFailure(String error) { fail(new IllegalStateException("SDP remote gagal: " + error)); }
        }, sdp);
    }

    protected void addRemoteCandidate(IceCandidate candidate) {
        if (candidate.sdp == null || candidate.sdp.isEmpty()) return;
        if (remoteDescriptionSet && peerConnection != null) peerConnection.addIceCandidate(candidate);
        else pendingRemoteCandidates.add(candidate);
    }

    protected void handleStatus(String st) {
        if (ended) return;
        st = clean(st).toLowerCase();
        if ("accepted".equals(st)) {
            debug("CALL accepted by server");
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

    protected void handleTerminalStatus(String st, boolean fromPush) {
        if (ended) return;
        if ("rejected".equals(st)) {
            debug("CALL terminal server=rejected");
            toast("Panggilan ditolak");
            finishCall("", false);
        } else if ("ended".equals(st) || "cancelled".equals(st) || "canceled".equals(st)) {
            debug("CALL terminal server=" + st);
            toast("Panggilan berakhir");
            finishCall("", false);
        } else if ("missed".equals(st) || "timeout".equals(st)) {
            debug("CALL terminal server=" + st);
            toast("Panggilan tidak terjawab");
            finishCall("", false);
        }
    }

    protected void safeIo(Runnable task) {
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

    protected void terminalIo(Runnable task) {
        if (task == null || destroyed || io.isShutdown()) {
            return;
        }
        try {
            io.execute(task);
        } catch (Throwable ignored) {
            // Shutdown race: the UI will still be closed safely.
        }
    }

    protected void ensureRtcFactoryInitialized() {
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

    protected JSONObject basePayload(String action) throws Exception {
        JSONObject p = new JSONObject();
        p.put("action", action);
        p.put("role", role);
        if (!callId.isEmpty()) p.put("call_id", callId);
        if (role.equals("customer")) p.put("user_id", session.getUserId());
        return p;
    }

    protected void finishCall(String action, boolean closeNow) {
        debug("CALL finishCall action=" + action + " closeNow=" + closeNow);
        if (closeNow) userRequestedClose = true;
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

    protected void releaseRtc() {
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


    protected void registerCallStateReceiver() {
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

    protected void unregisterCallStateReceiver() {
        if (!receiverRegistered) return;
        try { unregisterReceiver(callStateReceiver); } catch (Throwable ignored) {}
        receiverRegistered = false;
    }

    protected void toggleMute() {
        muted = !muted;
        if (localAudioTrack != null) localAudioTrack.setEnabled(!muted);
        muteButton.setText(muted ? "🔇 Muted" : "🎙 Mic");
    }

    protected void toggleSpeaker() {
        speaker = !speaker;
        if (audioManager != null) audioManager.setSpeakerphoneOn(speaker);
        speakerButton.setText(speaker ? "🔊 Speaker" : "🔈 Earpiece");
    }

    protected void configureAudioRoute() {
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(speaker);
        }
    }

    protected void startRingtone() {
        try {
            // The Activity is the single owner of call audio. FCM notifications
            // are intentionally silent, preventing double ringtone.
            if (ringtone != null && ringtone.isPlaying()) return;
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            ringtone = RingtoneManager.getRingtone(getApplicationContext(), uri);
            if (ringtone != null && !ringtone.isPlaying()) ringtone.play();
        } catch (Throwable ignored) {}
    }

    protected void stopRingtone() {
        IncomingCallAlertManager.stop(callId);
        try { if (ringtone != null && ringtone.isPlaying()) ringtone.stop(); } catch (Throwable ignored) {}
        ringtone = null;
    }

    protected void cancelOwnCallNotification() {
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

    protected void connected() {
        everConnected = true;
        debug("RTC CONNECTED audio call active");
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

    protected void status(String text) { if (statusView != null) statusView.setText(text); }
    protected void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    protected void fail(Throwable e) {
        debug("ERROR " + (e == null ? "unknown" : e.getClass().getSimpleName() + ": " + clean(e.getMessage())));
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

    protected void resetRtcForRetry() {
        debug("RTC reset retry #" + rtcRetryCount);
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
    @SuppressLint("MissingSuperCall")
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

    @Override protected void onPause() { super.onPause(); debug("LIFECYCLE onPause finishing=" + isFinishing()); }
    @Override protected void onStop() { super.onStop(); debug("LIFECYCLE onStop finishing=" + isFinishing()); }

    @Override
    protected void onDestroy() {
        debug("LIFECYCLE onDestroy finishing=" + isFinishing() + " ended=" + ended + " userClose=" + userRequestedClose + " connected=" + everConnected);
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

    protected void debug(String event) {
        // Production: diagnostic UI/remote logging disabled.
    }

    protected TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); return t;
    }
    protected Button button(String value, String color) {
        Button b = new Button(this); b.setText(value); b.setTextColor(Color.WHITE); b.setTextSize(14); b.setAllCaps(false); b.setBackground(round(color, 28)); return b;
    }
    protected LinearLayout.LayoutParams controlLp() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(54), 1f); p.setMargins(dp(6),0,dp(6),0); return p; }
    protected LinearLayout.LayoutParams actionLp() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(58), 1f); p.setMargins(dp(8),0,dp(8),0); return p; }
    protected GradientDrawable round(String color, int radius) { GradientDrawable g=new GradientDrawable();g.setColor(Color.parseColor(color));g.setCornerRadius(dp(radius));return g; }
    protected GradientDrawable circle(String color) { GradientDrawable g=round(color,50);g.setShape(GradientDrawable.OVAL);return g; }
    protected int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    // Cross-layer contracts keep the split type-safe without duplicating state.
    protected abstract void readIntent();
    protected abstract View buildUi();
    protected abstract void ensureMicrophoneThenResume();
    protected abstract void ensureMicrophoneThenStart();
    protected abstract void startOutgoingCall();
    protected abstract void acceptIncoming();
    protected abstract void loadIceAndStartPeer();
    protected abstract void initializePeer(List<PeerConnection.IceServer> iceServers);
    protected abstract void createOffer();
    protected abstract void createAnswer();
    protected abstract void postSdp(String action, String sdp);
    protected abstract void postCandidate(IceCandidate c);
    protected abstract void pollSignal();

}
