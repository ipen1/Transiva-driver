package com.transiva.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.transiva.app.driver.ui.DriverBottomNavigation;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DriverChatActivity extends Activity {

    private static final String BASE_URL = "https://transiva.my.id/";
    private static final String CONVERSATIONS_URL =
            BASE_URL + "server/get_driver_conversations.php";
    private static final String IMAGE_PREFIX = "[[IMAGE]]";
    private static final String VOICE_PREFIX = "[[VOICE]]";
    private static final String IMAGE_V2_PREFIX = "[[IMAGE2]]";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<JSONObject> conversations = new ArrayList<>();

    private LinearLayout listBox;
    private LinearLayout tabRow;
    private ProgressBar progress;
    private SessionManager session;

    private String selectedTab = "active";
    private boolean loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.parseColor("#0B7CFF"));
        getWindow().setNavigationBarColor(Color.parseColor("#071426"));

        session = new SessionManager(this);

        if (!validDriverSession()) {
            finish();
            return;
        }

        setContentView(buildScreen());
        DriverAppSettings.apply(this);
        DriverChatNotificationPoller.requestPermission(this);
        DriverChatNotificationPoller.start(this);
        loadConversations();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!loading && listBox != null) {
            loadConversations();
        }
    }

    private boolean validDriverSession() {
        return session != null
                && session.isLoggedIn()
                && "driver".equals(session.normalizeRole(session.getRole()))
                && !clean(session.getToken()).isEmpty();
    }

    private View buildScreen() {
        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(Color.parseColor("#F6F9FE"));

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        page.addView(shell, new FrameLayout.LayoutParams(-1, -1));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(14), dp(14), dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout title = new LinearLayout(this);
        title.setOrientation(LinearLayout.VERTICAL);
        title.addView(text("Pesan", 24, "#0B3A78", true));
        title.addView(text(
                "Percakapan driver dengan customer terkait order",
                11,
                "#718096",
                false
        ));
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));

        TextView refresh = text("↻", 25, "#0B7CFF", true);
        refresh.setGravity(Gravity.CENTER);
        refresh.setBackground(roundStroke(
                "#FFFFFF", "#DCE8F6", 16, 1));
        refresh.setOnClickListener(v -> loadConversations());
        header.addView(refresh, new LinearLayout.LayoutParams(dp(44), dp(44)));
        content.addView(header);

        LinearLayout info = new LinearLayout(this);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(dp(13), dp(12), dp(13), dp(12));
        info.setBackground(gradient("#0868F5", "#23A7FF", 18));

        ImageView icon = new ImageView(this);
        int iconRes = drawable("ic_nav_chat");
        if (iconRes != 0) icon.setImageResource(iconRes);
        info.addView(icon, new LinearLayout.LayoutParams(dp(38), dp(38)));

        LinearLayout infoText = new LinearLayout(this);
        infoText.setOrientation(LinearLayout.VERTICAL);
        infoText.setPadding(dp(11), 0, 0, 0);
        infoText.addView(text(
                "Chat aman dan terkait order",
                13,
                "#FFFFFF",
                true
        ));
        infoText.addView(text(
                "Pesan dan foto tetap tersimpan sebagai riwayat.",
                10,
                "#EAF5FF",
                false
        ));
        info.addView(infoText, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout.LayoutParams infoLp =
                new LinearLayout.LayoutParams(-1, -2);
        infoLp.setMargins(0, dp(14), 0, dp(14));
        content.addView(info, infoLp);

        tabRow = new LinearLayout(this);
        tabRow.setPadding(dp(4), dp(4), dp(4), dp(4));
        tabRow.setBackground(round("#EAF1FA", 15));
        content.addView(tabRow, new LinearLayout.LayoutParams(-1, dp(48)));
        rebuildTabs();

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listLp =
                new LinearLayout.LayoutParams(-1, -2);
        listLp.setMargins(0, dp(12), 0, 0);
        content.addView(listBox, listLp);

        shell.addView(
                DriverBottomNavigation.build(
                        this,
                        DriverBottomNavigation.ActiveItem.CHAT
                ),
                new LinearLayout.LayoutParams(-1, dp(66))
        );

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        FrameLayout.LayoutParams p =
                new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER);
        page.addView(progress, p);

        render();
        return page;
    }

    private void rebuildTabs() {
        tabRow.removeAllViews();

        Button active = tabButton(
                "Aktif",
                selectedTab.equals("active")
        );
        Button history = tabButton(
                "Riwayat",
                selectedTab.equals("history")
        );

        active.setOnClickListener(v -> {
            selectedTab = "active";
            rebuildTabs();
            render();
        });

        history.setOnClickListener(v -> {
            selectedTab = "history";
            rebuildTabs();
            render();
        });

        tabRow.addView(active, new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout.LayoutParams hp =
                new LinearLayout.LayoutParams(0, -1, 1);
        hp.setMargins(dp(4), 0, 0, 0);
        tabRow.addView(history, hp);
    }

    private Button tabButton(String label, boolean selected) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(11);
        button.setTextColor(Color.parseColor(
                selected ? "#0B7CFF" : "#64748B"));
        button.setTypeface(
                Typeface.DEFAULT,
                selected ? Typeface.BOLD : Typeface.NORMAL
        );
        button.setBackground(round(
                selected ? "#FFFFFF" : "#EAF1FA",
                12
        ));
        return button;
    }

    private void loadConversations() {
        if (loading) return;

        loading = true;
        progress.setVisibility(View.VISIBLE);
        render();

        new Thread(() -> {
            try {
                // Endpoint ini melakukan autentikasi driver dari Bearer token.
                // Jangan memakai endpoint customer dengan driver_id karena endpoint
                // tersebut mengharuskan user_id customer dan memunculkan
                // "User ID tidak valid" pada menu Pesan driver.
                String endpoint = CONVERSATIONS_URL
                        + "?_=" + System.currentTimeMillis();
                JSONObject response = getAuthorized(endpoint);
                JSONArray array = response.optJSONArray("conversations");
                List<JSONObject> fresh = new ArrayList<>();

                if (!response.optBoolean("success", false)) {
                    throw new IllegalStateException(first(
                            response.optString("message"),
                            "Server gagal memuat percakapan"
                    ));
                }

                if (array != null) {
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject item = array.optJSONObject(i);
                        if (item != null) fresh.add(item);
                    }
                }

                main.post(() -> {
                    conversations.clear();
                    conversations.addAll(fresh);
                    loading = false;
                    progress.setVisibility(View.GONE);
                    render();
                });

            } catch (Exception error) {
                main.post(() -> {
                    loading = false;
                    progress.setVisibility(View.GONE);
                    render();
                    toast(first(error.getMessage(),
                            "Gagal memuat daftar pesan"));
                });
            }
        }).start();
    }

    private JSONObject getAuthorized(String endpoint) throws Exception {
        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection)
                    new URL(endpoint).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(20000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty(
                    "Authorization",
                    "Bearer " + session.getToken()
            );
            connection.setRequestProperty("X-Device-UUID", DeviceIdentityManager.getInstallationUuid(this));
            connection.setRequestProperty("X-App-Scope", "driver");

            int status = connection.getResponseCode();
            InputStream input = status >= 200 && status < 400
                    ? connection.getInputStream()
                    : connection.getErrorStream();

            if (input == null) {
                throw new IllegalStateException("Respons server kosong");
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8));
            StringBuilder raw = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                raw.append(line);
            }

            String body = raw.toString().trim();

            if (body.isEmpty()) {
                throw new IllegalStateException("Respons server kosong. HTTP " + status);
            }

            String lower = body.toLowerCase(Locale.US);
            if (lower.startsWith("<!doctype") || lower.startsWith("<html")) {
                throw new IllegalStateException(
                        "Endpoint chat tidak tersedia. HTTP " + status
                );
            }

            int firstBrace = body.indexOf('{');
            int lastBrace = body.lastIndexOf('}');
            if (firstBrace < 0 || lastBrace <= firstBrace) {
                throw new IllegalStateException("Respons server bukan JSON. HTTP " + status);
            }

            JSONObject response = new JSONObject(
                    body.substring(firstBrace, lastBrace + 1)
            );

            if (status < 200 || status >= 400) {
                throw new IllegalStateException(
                        response.optString("message", "HTTP " + status));
            }

            return response;

        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void render() {
        if (listBox == null) return;

        listBox.removeAllViews();

        if (loading) {
            addState(
                    "Memuat percakapan...",
                    "Daftar pesan driver sedang diperbarui."
            );
            return;
        }

        List<JSONObject> filtered = new ArrayList<>();

        for (JSONObject item : conversations) {
            boolean history = item.optBoolean("is_history", false)
                    || DriverMessageStatus.isEnded(
                    item.optString("status", ""));

            if (selectedTab.equals("history") == history) {
                filtered.add(item);
            }
        }

        if (filtered.isEmpty()) {
            addState(
                    selectedTab.equals("active")
                            ? "Belum ada pesan aktif"
                            : "Riwayat pesan masih kosong",
                    selectedTab.equals("active")
                            ? "Percakapan tersedia setelah driver menerima order."
                            : "Chat dari order selesai akan tersimpan di sini."
            );
            return;
        }

        listBox.addView(text(
                selectedTab.equals("active")
                        ? "Percakapan aktif"
                        : "Riwayat percakapan",
                15,
                "#0B3A78",
                true
        ));

        for (JSONObject item : filtered) {
            listBox.addView(conversationCard(item));
        }
    }

    private View conversationCard(JSONObject item) {
        boolean history = item.optBoolean("is_history", false)
                || DriverMessageStatus.isEnded(
                item.optString("status", ""));

        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setBackground(roundStroke(
                "#FFFFFF", "#E1EAF5", 17, 1));

        FrameLayout iconFrame = new FrameLayout(this);
        iconFrame.setBackground(round(
                serviceSoftColor(item.optString("order_type", "")),
                14
        ));

        ImageView icon = new ImageView(this);
        int iconRes = serviceDrawable(
                item.optString("order_type", ""));
        if (iconRes != 0) icon.setImageResource(iconRes);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);

        FrameLayout.LayoutParams iconLp =
                new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER);
        iconFrame.addView(icon, iconLp);
        card.addView(iconFrame, new LinearLayout.LayoutParams(dp(50), dp(50)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(10), 0, dp(7), 0);

        String customer = first(
                item.optString("participant_name"),
                item.optString("customer_name"),
                item.optString("customer"),
                "Customer"
        );

        TextView name = text(customer, 14, "#0B3A78", true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(name);

        String last = item.optString("last_message", "").trim();
        boolean image = isImageMessage(last);
        boolean voice = last.startsWith(VOICE_PREFIX);
        String preview = voice
                ? (isLastMessageMine(item) ? "Anda mengirim voice note" : customer + " mengirim voice note")
                : (image
                ? (isLastMessageMine(item) ? "Anda mengirim foto" : customer + " mengirim foto")
                : first(last, "Belum ada pesan"));

        TextView previewView = text(
                preview,
                11,
                (image || voice) ? "#0B5FAF" : "#64748B",
                image || voice
        );
        previewView.setSingleLine(true);
        previewView.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(previewView);

        info.addView(text(
                serviceName(item.optString("order_type", ""))
                        + " • "
                        + DriverMessageStatus.orderLabel(
                        item.optString("status", ""),
                        item.optString("order_type", "")),
                9,
                history ? "#8495A8" : "#0B7CFF",
                true
        ));

        card.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout end = new LinearLayout(this);
        end.setOrientation(LinearLayout.VERTICAL);
        end.setGravity(Gravity.END);

        end.addView(text(
                formatDate(first(
                        item.optString("last_message_at"),
                        item.optString("created_at"))),
                9,
                "#94A3B8",
                false
        ));
        end.addView(text("›", 25, "#0B7CFF", true));
        card.addView(end, new LinearLayout.LayoutParams(dp(70), -2));

        card.setOnClickListener(v -> openRoom(item, history));

        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(9), 0, 0);
        card.setLayoutParams(lp);
        return card;
    }

    private void openRoom(JSONObject item, boolean history) {
        Intent intent = new Intent(this, DriverChatRoomActivity.class);
        intent.putExtra("order_id", first(
                item.optString("order_id"),
                item.optString("id")));
        intent.putExtra("room_id", item.optString("room_id", ""));
        intent.putExtra("order_db_id", first(
                item.optString("order_db_id"),
                item.optString("id")));
        intent.putExtra("participant_name", first(
                item.optString("participant_name"),
                item.optString("customer_name"),
                item.optString("customer"),
                "Customer"));
        intent.putExtra("order_type", item.optString("order_type", ""));
        intent.putExtra("order_status", item.optString("status", ""));
        intent.putExtra(
                "order_source",
                item.optString("source", "orders")
        );
        intent.putExtra("read_only", history);
        startActivity(intent);
    }

    private boolean isImageMessage(String value) {
        String clean = clean(value);
        return clean.startsWith(IMAGE_PREFIX)
                || clean.startsWith(IMAGE_V2_PREFIX);
    }

    private boolean isLastMessageMine(JSONObject item) {
        if (item.optBoolean("last_message_is_mine", false)) return true;

        String sender = first(
                item.optString("last_sender_type"),
                item.optString("sender_type"),
                item.optString("last_message_sender")
        ).toLowerCase(Locale.US);

        return sender.equals("driver");
    }

    private void addState(String title, String subtitle) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(20), dp(16), dp(20));
        card.setBackground(roundStroke(
                "#FFFFFF", "#DCE8F6", 18, 1));
        card.addView(text(title, 15, "#0B3A78", true));
        card.addView(text(subtitle, 11, "#718096", false));
        listBox.addView(card);
    }

    private String serviceName(String type) {
        String value = clean(type).toLowerCase(Locale.US);
        if (value.contains("food")) return "TransFood";
        if (value.contains("car") || value.contains("mobil")) return "TransCar";
        if (value.contains("pickup")) return "TransPickup";
        return "TransRide";
    }

    private String serviceSoftColor(String type) {
        String value = clean(type).toLowerCase(Locale.US);
        if (value.contains("food")) return "#FFF3E8";
        if (value.contains("car") || value.contains("mobil")) return "#EAF1FF";
        if (value.contains("pickup")) return "#E9FBF4";
        return "#EAF4FF";
    }

    private int serviceDrawable(String type) {
        String value = clean(type).toLowerCase(Locale.US);
        if (value.contains("food")) return drawable("ic_transfood");
        if (value.contains("car") || value.contains("mobil")) {
            return drawable("ic_transcar");
        }
        if (value.contains("pickup")) return drawable("ic_transpickup");
        return drawable("ic_transride");
    }

    private int drawable(String name) {
        return getResources().getIdentifier(
                name, "drawable", getPackageName());
    }

    private String formatDate(String raw) {
        if (clean(raw).isEmpty()) return "";

        String[] formats = {
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd'T'HH:mm:ss"
        };

        for (String format : formats) {
            try {
                Date date = new SimpleDateFormat(
                        format, Locale.US).parse(raw);
                if (date != null) {
                    return new SimpleDateFormat(
                            "dd/MM HH:mm",
                            new Locale("id", "ID")
                    ).format(date);
                }
            } catch (Exception ignored) {}
        }

        return raw;
    }

    private TextView text(
            String value,
            int sp,
            String color,
            boolean bold
    ) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(Color.parseColor(color));
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable round(String fill, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.parseColor(fill));
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private GradientDrawable roundStroke(
            String fill,
            String stroke,
            int radius,
            int width
    ) {
        GradientDrawable shape = round(fill, radius);
        shape.setStroke(dp(width), Color.parseColor(stroke));
        return shape;
    }

    private GradientDrawable gradient(
            String start,
            String end,
            int radius
    ) {
        GradientDrawable shape = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{
                        Color.parseColor(start),
                        Color.parseColor(end)
                });
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private int dp(int value) {
        return Math.round(value
                * getResources().getDisplayMetrics().density);
    }

    private String first(String... values) {
        if (values == null) return "";

        for (String value : values) {
            String clean = clean(value);
            if (!clean.isEmpty()
                    && !"null".equalsIgnoreCase(clean)
                    && !"undefined".equalsIgnoreCase(clean)) {
                return clean;
            }
        }

        return "";
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_LONG).show();
    }
}
