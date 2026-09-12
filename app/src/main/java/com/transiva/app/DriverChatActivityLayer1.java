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
abstract class DriverChatActivityLayer1 extends Activity {

    protected static final String BASE_URL = "https://transiva.my.id/";
    protected static final String CONVERSATIONS_URL =
            BASE_URL + "server/get_driver_conversations.php";
    protected static final String IMAGE_PREFIX = "[[IMAGE]]";
    protected static final String VOICE_PREFIX = "[[VOICE]]";
    protected static final String IMAGE_V2_PREFIX = "[[IMAGE2]]";

    protected final Handler main = new Handler(Looper.getMainLooper());
    protected final List<JSONObject> conversations = new ArrayList<>();

    protected LinearLayout listBox;
    protected LinearLayout tabRow;
    protected ProgressBar progress;
    protected SessionManager session;

    protected String selectedTab = "active";
    protected boolean loading;

    protected View conversationCard(JSONObject item) {
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

    protected void openRoom(JSONObject item, boolean history) {
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

    protected boolean isImageMessage(String value) {
        String clean = clean(value);
        return clean.startsWith(IMAGE_PREFIX)
                || clean.startsWith(IMAGE_V2_PREFIX);
    }

    protected boolean isLastMessageMine(JSONObject item) {
        if (item.optBoolean("last_message_is_mine", false)) return true;

        String sender = first(
                item.optString("last_sender_type"),
                item.optString("sender_type"),
                item.optString("last_message_sender")
        ).toLowerCase(Locale.US);

        return sender.equals("driver");
    }

    protected void addState(String title, String subtitle) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(20), dp(16), dp(20));
        card.setBackground(roundStroke(
                "#FFFFFF", "#DCE8F6", 18, 1));
        card.addView(text(title, 15, "#0B3A78", true));
        card.addView(text(subtitle, 11, "#718096", false));
        listBox.addView(card);
    }

    protected String serviceName(String type) {
        String value = clean(type).toLowerCase(Locale.US);
        if (value.contains("food")) return "TransFood";
        if (value.contains("car") || value.contains("mobil")) return "TransCar";
        if (value.contains("pickup")) return "TransPickup";
        return "TransRide";
    }

    protected String serviceSoftColor(String type) {
        String value = clean(type).toLowerCase(Locale.US);
        if (value.contains("food")) return "#FFF3E8";
        if (value.contains("car") || value.contains("mobil")) return "#EAF1FF";
        if (value.contains("pickup")) return "#E9FBF4";
        return "#EAF4FF";
    }

    protected int serviceDrawable(String type) {
        String value = clean(type).toLowerCase(Locale.US);
        if (value.contains("food")) return drawable("ic_transfood");
        if (value.contains("car") || value.contains("mobil")) {
            return drawable("ic_transcar");
        }
        if (value.contains("pickup")) return drawable("ic_transpickup");
        return drawable("ic_transride");
    }

    protected int drawable(String name) {
        return getResources().getIdentifier(
                name, "drawable", getPackageName());
    }

    protected String formatDate(String raw) {
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

    protected TextView text(
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

    protected GradientDrawable round(String fill, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.parseColor(fill));
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    protected GradientDrawable roundStroke(
            String fill,
            String stroke,
            int radius,
            int width
    ) {
        GradientDrawable shape = round(fill, radius);
        shape.setStroke(dp(width), Color.parseColor(stroke));
        return shape;
    }

    protected GradientDrawable gradient(
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

    protected int dp(int value) {
        return Math.round(value
                * getResources().getDisplayMetrics().density);
    }

    protected String first(String... values) {
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

    protected String clean(String value) {
        return value == null ? "" : value.trim();
    }

    protected void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_LONG).show();
    }
    // Cross-layer contracts keep the split type-safe without duplicating state.
    protected abstract void onCreate(Bundle savedInstanceState);
    protected abstract void onResume();
    protected abstract boolean validDriverSession();
    protected abstract View buildScreen();
    protected abstract void rebuildTabs();
    protected abstract Button tabButton(String label, boolean selected);
    protected abstract void loadConversations();
    protected abstract JSONObject getAuthorized(String endpoint) throws Exception;
    protected abstract void render();

}
