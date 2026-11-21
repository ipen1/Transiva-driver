package com.transiva.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

public final class DriverTransSendContactsActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::refresh;
    private DriverUiScope scope;
    private SessionManager session;
    private LinearLayout rows;
    private TextView state;
    private boolean visible;
    private boolean busy;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        scope = new DriverUiScope(this);
        session = new SessionManager(this);
        if (!scope.alive()) { finish(); return; }
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(16));
        content.setBackgroundColor(Color.parseColor("#F3F8FF"));
        scroll.addView(content);
        content.addView(label("Permintaan TransSend", 22));
        state = label("Memuat permintaan…", 14);
        content.addView(state);
        LinearLayout actions = new LinearLayout(this);
        actions.addView(button("Kembali", this::finish));
        actions.addView(button("Perbarui", this::refresh));
        content.addView(actions);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        content.addView(rows);
        setContentView(scroll);
        DriverAppSettings.apply(this);
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        refresh();
    }

    @Override protected void onPause() {
        visible = false;
        main.removeCallbacks(poll);
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (scope != null) scope.close();
        super.onDestroy();
    }

    private void refresh() { request("list", ""); }

    private void request(String action, String id) {
        if (!visible || busy || !scope.alive()) return;
        busy = true;
        if (!scope.execute(() -> {
            try {
                JSONObject data = new JSONObject().put("action", action).put("contact_id", id);
                JSONObject reply = DriverMessageApi.post(session,
                        DriverApiConfig.BASE_URL + "transsend_contact.php", data, scope.owner);
                scope.post(() -> {
                    busy = false;
                    if (!visible) return;
                    if (!reply.optBoolean("success")) {
                        failure(reply.optString("message", "Permintaan belum dapat dimuat."));
                    } else if ("list".equals(action)) {
                        render(reply.optJSONArray("contacts"));
                    } else if ("accept".equals(action)) {
                        JSONObject contact = reply.optJSONObject("contact");
                        if (contact != null && "accepted".equals(contact.optString("state"))) openChat(contact);
                        else failure("Permintaan sudah berubah. Perbarui daftar.");
                    } else {
                        refresh();
                    }
                    schedule();
                });
            } catch (Exception error) {
                scope.post(() -> failure("Koneksi terputus. Coba lagi."));
            }
        })) failure("Sistem sedang sibuk. Coba lagi.");
    }

    private void render(JSONArray entries) {
        rows.removeAllViews();
        int count = entries == null ? 0 : entries.length();
        state.setText(count == 0 ? "Belum ada permintaan aktif." : count + " permintaan aktif");
        for (int i = 0; i < count; i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) continue;
            boolean accepted = "accepted".equals(entry.optString("state"));
            String name = entry.optString("peer_name", "Customer");
            TextView title = label(name + (accepted ? " · Chat aktif" : " · Menunggu jawaban"), 16);
            title.setPadding(0, dp(12), 0, 0);
            rows.addView(title);
            LinearLayout actions = new LinearLayout(this);
            rows.addView(actions);
            if (accepted) {
                actions.addView(button("Buka Chat", () -> openChat(entry)));
            } else {
                String id = entry.optString("id");
                actions.addView(button("Terima", () -> new AlertDialog.Builder(this)
                        .setTitle("Terima permintaan TransSend?")
                        .setMessage("Chat aktif selama 15 menit. Jika customer memesan saat Anda masih tersedia, pesanan langsung masuk ke pesanan aktif Anda.")
                        .setNegativeButton("Kembali", null)
                        .setPositiveButton("Terima & Buka Chat", (d,w) -> request("accept", id)).show()));
                actions.addView(button("Tolak", () -> request("reject", id)));
            }
        }
        DriverAppSettings.apply(this);
    }

    private void openChat(JSONObject entry) {
        startActivity(new Intent(this, DriverChatRoomActivity.class)
                .putExtra("order_source", "pickup_orders")
                .putExtra("order_type", "TransSend")
                .putExtra("order_status", "contact_accepted")
                .putExtra("order_id", entry.optString("order_id"))
                .putExtra("room_id", entry.optString("room_id"))
                .putExtra("participant_name", entry.optString("peer_name"))
                .putExtra("participant_photo", entry.optString("peer_photo")));
    }

    private void failure(String message) {
        busy = false;
        if (!visible) return;
        state.setText(message);
        schedule();
    }

    private void schedule() {
        main.removeCallbacks(poll);
        if (visible) main.postDelayed(poll, 5000);
    }

    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }

    private TextView label(String value, int size) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(Color.parseColor("#103769"));
        return label;
    }

    private Button button(String label, Runnable click) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setOnClickListener(v -> click.run());
        return button;
    }
}
