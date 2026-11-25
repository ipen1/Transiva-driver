package com.transiva.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DashboardSimpelRequests {
    private static final String KEY_PREFIX = "simpel-request:";
    private final DriverDashboardActivity host;
    private final DriverUiScope scope;
    private final SessionManager session;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable onChanged;
    private final Runnable refresh = this::refresh;
    private final Map<String, JSONObject> pending = new LinkedHashMap<>();
    private final LinearLayout card;
    private final TextView summary, pickup, countdown;
    private final Button accept, reject;
    private String preferredId = "", selectedKey = "";
    private boolean listening, online, busy, refreshAgain;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { focus(intent); }
    };

    DashboardSimpelRequests(DriverDashboardActivity host, DriverUiScope scope, Runnable onChanged) {
        this.host = host;
        this.scope = scope;
        this.onChanged = onChanged;
        session = new SessionManager(host);
        card = new LinearLayout(host);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(host.dp(7), host.dp(7), host.dp(7), 0);
        card.setVisibility(View.GONE);
        LinearLayout header = new LinearLayout(host);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(host.text("Permintaan Trans Simpel", 17, "#0B3A78", true),
                new LinearLayout.LayoutParams(0, -2, 1));
        countdown = host.text("", 12, "#B45309", true);
        header.addView(countdown);
        card.addView(header);
        summary = host.text("", 13, "#405975", true);
        summary.setMaxLines(1);
        summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(summary);
        pickup = host.text("", 13, "#405975", false);
        pickup.setMaxLines(2);
        pickup.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(pickup);
        LinearLayout actions = new LinearLayout(host);
        LinearLayout.LayoutParams actionRow = new LinearLayout.LayoutParams(-1, host.dp(48));
        actionRow.topMargin = host.dp(7);
        card.addView(actions, actionRow);
        reject = host.dangerOutlineButton("Tolak");
        accept = host.primaryButton("Terima & Buka Chat");
        LinearLayout.LayoutParams rejectParams = new LinearLayout.LayoutParams(0, -1, 1);
        rejectParams.setMarginEnd(host.dp(8));
        actions.addView(reject, rejectParams);
        actions.addView(accept, new LinearLayout.LayoutParams(0, -1, 2));
        reject.setOnClickListener(v -> decide("reject"));
        accept.setOnClickListener(v -> decide("accept"));
    }

    View view() { return card; }
    List<String> keys() { return new ArrayList<>(pending.keySet()); }
    boolean contains(String key) { return pending.containsKey(key); }

    String preferredKey() {
        String key = KEY_PREFIX + preferredId;
        if (!pending.containsKey(key)) return "";
        preferredId = "";
        return key;
    }

    JSONObject point(String key) {
        JSONObject brief = brief(pending.get(key));
        return brief == null ? null : brief.optJSONObject("pickup");
    }

    private static JSONObject brief(JSONObject entry) {
        return entry == null ? null : entry.optJSONObject("tiva_brief");
    }

    void show(String key) {
        selectedKey = key;
        JSONObject entry = pending.get(key);
        card.setVisibility(entry == null ? View.GONE : View.VISIBLE);
        if (entry == null) return;
        JSONObject brief = brief(entry);
        JSONObject point = point(key);
        String title = brief == null ? "" : brief.optString("title");
        summary.setText(CallPeerIdentity.first(entry.optString("peer_name"), "Customer")
                + (title.isEmpty() ? "" : " · " + title));
        pickup.setVisibility(point == null ? View.GONE : View.VISIBLE);
        if (point != null) pickup.setText(point.optString("address"));
        renderCountdown();
    }

    void tick() {
        if (pending.entrySet().removeIf(entry -> seconds(entry.getValue()) <= 0)) onChanged.run();
        renderCountdown();
    }

    private void renderCountdown() {
        JSONObject entry = pending.get(selectedKey);
        if (entry == null) return;
        long remaining = Math.max(0L, seconds(entry));
        countdown.setText(remaining + " dtk");
        accept.setEnabled(remaining > 0 && !busy);
        reject.setEnabled(remaining > 0 && !busy);
    }

    private static long seconds(JSONObject request) {
        return request.optLong("expires_epoch") - System.currentTimeMillis() / 1000L;
    }

    void focus(Intent intent) {
        if (intent == null || !intent.hasExtra(DashboardOfferForeground.SIMPEL_REQUEST)) return;
        preferredId = CallPeerIdentity.first(intent.getStringExtra(DashboardOfferForeground.SIMPEL_REQUEST), "");
        refresh();
    }

    void setOnline(boolean value) {
        if (online == value) return;
        online = value;
        if (online) refresh();
        else {
            main.removeCallbacks(refresh);
            pending.clear();
        }
    }

    void resume() {
        if (listening) return;
        IntentFilter filter = new IntentFilter(DashboardOfferForeground.ACTION_SIMPEL_REQUEST);
        ContextCompat.registerReceiver(host, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        listening = true;
        refresh();
    }

    void pause() {
        main.removeCallbacks(refresh);
        if (listening) host.unregisterReceiver(receiver);
        listening = false;
    }

    private void refresh() {
        if (!listening || !online || !scope.alive()) return;
        if (busy) {
            refreshAgain = true;
            return;
        }
        busy = true;
        if (!scope.execute(() -> {
            JSONArray contacts = null;
            try {
                JSONObject reply = request("list", "");
                if (reply.optBoolean("success")) contacts = reply.optJSONArray("contacts");
            } catch (Exception ignored) {
                // Keep the latest verified invites until a successful refresh or their deadline.
            }
            JSONArray snapshot = contacts;
            scope.post(() -> finishRefresh(snapshot));
        })) finishRefresh(null);
    }

    private void finishRefresh(JSONArray contacts) {
        busy = false;
        if (online && contacts != null) {
            pending.clear();
            for (int i = 0; i < contacts.length(); i++) {
                JSONObject entry = contacts.optJSONObject(i);
                if (entry == null || !"trans_simpel".equals(entry.optString("service"))
                        || !"pending".equals(entry.optString("state")) || seconds(entry) <= 0) continue;
                String id = entry.optString("id");
                if (!id.isEmpty()) pending.put(KEY_PREFIX + id, entry);
            }
            if (!contains(KEY_PREFIX + preferredId) && !refreshAgain) preferredId = "";
            onChanged.run();
        }
        scheduleRefresh();
    }

    private void scheduleRefresh() {
        main.removeCallbacks(refresh);
        boolean again = refreshAgain;
        refreshAgain = false;
        if (listening && online) main.postDelayed(refresh,
                again ? 0L : WaveLoadGuard.jitter(pending.isEmpty() ? 20_000L : 6_000L));
    }

    private void decide(String action) {
        String key = selectedKey;
        JSONObject entry = pending.get(key);
        if (busy || !listening || !online || entry == null || seconds(entry) <= 0) return;
        busy = true;
        renderCountdown();
        if (!scope.execute(() -> {
            JSONObject reply;
            try {
                reply = request(action, entry.optString("id"));
            } catch (Exception ignored) {
                reply = null;
            }
            JSONObject response = reply;
            scope.post(() -> finishDecision(action, key, response));
        })) finishDecision(action, key, null);
    }

    private void finishDecision(String action, String key, JSONObject reply) {
        busy = false;
        if (reply != null && reply.optBoolean("success")) {
            pending.remove(key);
            onChanged.run();
            JSONObject contact = reply.optJSONObject("contact");
            if (listening && "accept".equals(action) && contact != null
                    && "accepted".equals(contact.optString("state")) && !contact.optString("room_id").isEmpty()) {
                host.startActivity(DriverTransSendContactsActivity.chatIntent(host, contact));
            }
        } else if (listening) {
            host.showMessage(reply == null ? "Koneksi terputus. Coba lagi."
                    : reply.optString("message", "Permintaan belum bisa diproses."));
        }
        refresh();
    }

    private JSONObject request(String action, String id) throws Exception {
        JSONObject data = new JSONObject().put("action", action).put("contact_id", id);
        return DriverMessageApi.post(session, DriverApiConfig.BASE_URL + "transsend_contact.php", data, scope.owner);
    }
}
