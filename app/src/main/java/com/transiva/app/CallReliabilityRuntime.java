package com.transiva.app;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground-only recovery. No polling service, background launch, or wake lock. */
public final class CallReliabilityRuntime implements Application.ActivityLifecycleCallbacks {
    private static CallReliabilityRuntime instance;
    private final Context app;
    private final String role;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private WeakReference<Activity> resumed = new WeakReference<>(null);
    private String owner = "";
    private boolean polling;
    private long nextPoll;
    private int pollFailures;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            main.removeCallbacks(this);
            Activity a = resumed.get();
            if (a == null || a.isFinishing() || a.isDestroyed()) return;
            SessionManager session = new SessionManager(app);
            String token = session.getToken();
            if (!session.isLoggedIn() || token == null || token.trim().isEmpty()
                    || !role.equalsIgnoreCase(session.getRole())) {
                owner = ""; nextPoll = 0;
                main.postDelayed(this, 4000); return;
            }
            if (!token.equals(owner)) {
                owner = token; pollFailures = 0; nextPoll = 0;
            }
            long now = SystemClock.elapsedRealtime();
            FcmTokenCoordinator.syncIfNeeded(app);
            if (!polling && now >= nextPoll) checkIncoming(token);
            main.postDelayed(this, 4000);
        }
    };

    private CallReliabilityRuntime(Application application) {
        app = application.getApplicationContext();
        role = app.getPackageName().endsWith(".driver") ? "driver" : "customer";
        FcmTokenCoordinator.install(application);
        application.registerActivityLifecycleCallbacks(this);
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                    new ConnectivityManager.NetworkCallback() {
                        @Override public void onAvailable(Network network) {
                            main.post(() -> {
                                FcmTokenCoordinator.forceSync(app); nextPoll = 0; pollFailures = 0;
                                // Resume only when an Activity is active.
                                main.removeCallbacks(tick); main.post(tick);
                            });
                        }
                    });
        } catch (RuntimeException ignored) { /* Resume hook still retries. */ }
    }
    public static synchronized void install(Application app) {
        if (instance == null) instance = new CallReliabilityRuntime(app);
    }
    /** Also used by customer onNewToken/login; retries continue while foreground. */
    public static void requestTokenSync() {
        CallReliabilityRuntime r=instance;if(r!=null)FcmTokenCoordinator.forceSync(r.app);
    }
    private boolean current(String token) {
        SessionManager s = new SessionManager(app);
        return s.isLoggedIn() && token.equals(s.getToken()) && role.equalsIgnoreCase(s.getRole());
    }
    private void checkIncoming(String token) {
        polling = true;
        io.execute(() -> {
            JSONObject result = null;
            try {
                if (current(token)) result = request(token, "webrtc_call.php",
                        new JSONObject().put("action", "incoming").put("role", role));
            } catch (Exception ignored) { }
            final JSONObject answer = result;
            main.post(() -> {
                polling = false;
                if (!current(token)) return;
                if (answer == null) {
                    pollFailures = Math.min(pollFailures + 1, 4);
                    nextPoll = SystemClock.elapsedRealtime() + Math.min(30000L, 2000L << pollFailures);
                    return;
                }
                pollFailures = 0; nextPoll = SystemClock.elapsedRealtime() + 4000;
                Activity a = resumed.get();
                if (a == null || a.isFinishing() || a.isDestroyed()) return;
                WebRtcSessionEngine engine = WebRtcCallForegroundService.current();
                if (engine != null && engine.active()) return;
                JSONArray calls = answer.optJSONArray("calls");
                JSONObject call = calls == null ? null : calls.optJSONObject(0);
                if (a instanceof TransivaCallUiActivity) {
                    // Observe cancellation/timeout even when its terminal FCM was lost.
                    Intent shown = a.getIntent();
                    String id = shown.getStringExtra("call_id");
                    if (shown.getBooleanExtra("incoming", false) && id != null
                            && (call == null || !id.equals(call.optString("call_id")))) {
                        IncomingCallAlertManager.resolve(id);
                        Intent event = new Intent(TransivaCallUiActivity.ACTION_CALL_STATE)
                                .setPackage(app.getPackageName()).putExtra("call_id", id).putExtra("call_status", "ended");
                        app.sendBroadcast(event);
                    }
                    return;
                }
                if (call == null || call.optInt("remaining_seconds", 0) <= 1) return;
                String id = call.optString("call_id", "");
                // Same deduplication owner as FCM: no second ringtone or call screen.
                if (!IncomingCallAlertManager.claimNotification(id)) return;
                Intent incoming = new Intent(a, WebRtcCallActivity.class)
                        .putExtra("incoming", true).putExtra("call_id", id)
                        .putExtra("order_id", call.optString("order_id"))
                        .putExtra("source", call.optString("source", "orders"))
                        .putExtra("call_type", call.optString("call_type", "audio"))
                        .putExtra("caller_name", call.optString("caller_name", "Transiva"))
                        .putExtra("remaining_seconds", call.optInt("remaining_seconds", 45));
                a.startActivity(incoming);
            });
        });
    }
    private JSONObject request(String token, String endpoint, JSONObject payload) throws Exception {
        if (!current(token)) throw new IllegalStateException("Sesi berubah");
        HttpURLConnection c = com.transiva.app.driver.data.DriverConnectionRepository.open(DriverApiConfig.endpoint(endpoint));
        try {
            c.setRequestMethod("POST"); c.setDoOutput(true); c.setUseCaches(false);
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(6000); c.setReadTimeout(6000);
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("X-Device-UUID", DeviceIdentityManager.getInstallationUuid(app));
            c.setRequestProperty("X-App-Scope", role);
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            c.setRequestProperty("Accept", "application/json");
            try (OutputStream out = c.getOutputStream()) { out.write(payload.toString().getBytes(StandardCharsets.UTF_8)); }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[2048]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (bytes.size() + n > 65536) throw new IllegalStateException("Respons terlalu besar");
                    bytes.write(buffer, 0, n);
                }
                JSONObject json = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                if (!json.optBoolean("success", false) || !current(token)) throw new IllegalStateException("Sesi/permintaan tidak valid");
                return json;
            }
        } finally { c.disconnect(); }
    }
    @Override public void onActivityResumed(Activity a) {
        resumed = new WeakReference<>(a); main.removeCallbacks(tick); main.post(tick);
    }
    @Override public void onActivityPaused(Activity a) {
        if (resumed.get() == a) { resumed.clear(); main.removeCallbacks(tick); }
    }
    @Override public void onActivityCreated(Activity a, Bundle b) { }
    @Override public void onActivityStarted(Activity a) { }
    @Override public void onActivityStopped(Activity a) { }
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
    @Override public void onActivityDestroyed(Activity a) { }
}
