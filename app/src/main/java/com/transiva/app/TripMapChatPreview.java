package com.transiva.app;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Foreground, order-scoped previews using the same authenticated room as the chat screen. */
final class TripMapChatPreview {
    private static final int MAX_QUEUED = 32;
    private final Activity activity;
    private final FrameLayout mapLayer;
    private final Supplier<JSONObject> order;
    private final Supplier<PointF> pickup;
    private final IntSupplier visibleTop;
    private final IntSupplier visibleBottom;
    private final Runnable openRoom;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> messages = new ArrayDeque<>();
    private final LinearLayout bubble;
    private final TextView message;
    private final TextView pageBadge;
    private final ArrayList<String> pages = new ArrayList<>();
    private DriverUiScope scope;
    private boolean registered;
    private boolean busy;
    private boolean moreWaiting;
    private String identity = "";
    private long lastId;
    private int page;
    private int failures;

    TripMapChatPreview(Activity activity, FrameLayout mapLayer, Supplier<JSONObject> order,
                       Supplier<PointF> pickup, IntSupplier visibleTop, IntSupplier visibleBottom,
                       Runnable openRoom) {
        this.activity = activity;
        this.mapLayer = mapLayer;
        this.order = order;
        this.pickup = pickup;
        this.visibleTop = visibleTop;
        this.visibleBottom = visibleBottom;
        this.openRoom = openRoom;

        bubble = new LinearLayout(activity);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(12), dp(8), dp(12), dp(9));
        bubble.setMinimumHeight(dp(48));
        int surface = DriverThemeTokens.surface(activity);
        android.graphics.drawable.GradientDrawable glass = DriverNavigationUi.roundRect(
                Color.argb(248, Color.red(surface), Color.green(surface), Color.blue(surface)), dp(17));
        glass.setStroke(dp(1), DriverThemeTokens.border(activity));
        bubble.setBackground(glass);
        bubble.setElevation(dp(6));
        LinearLayout heading = new LinearLayout(activity);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(activity);
        title.setText("●  Pesan pelanggan");
        title.setTextSize(11);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(DriverThemeTokens.accent(activity));
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        pageBadge = new TextView(activity);
        pageBadge.setTextSize(10);
        pageBadge.setTextColor(DriverThemeTokens.textSecondary(activity));
        pageBadge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        heading.addView(pageBadge);
        bubble.addView(heading);
        message = new TextView(activity);
        message.setTextColor(DriverThemeTokens.textPrimary(activity));
        message.setTextSize(14);
        message.setPadding(0, dp(3), 0, 0);
        message.setMaxLines(3);
        bubble.addView(message, new LinearLayout.LayoutParams(-1, -2));
        bubble.setVisibility(View.INVISIBLE);
        bubble.setAlpha(0f);
        mapLayer.addView(bubble, new FrameLayout.LayoutParams(dp(216), -2, Gravity.TOP | Gravity.LEFT));
        bubble.setOnClickListener(view -> {
            if (scope == null || !scope.alive()) return;
            clear();
            openRoom.run();
        });
    }

    private final BroadcastReceiver updates = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            JSONObject current = order.get();
            if (current == null) return;
            String type = String.valueOf(intent.getStringExtra("type")).toLowerCase(java.util.Locale.US);
            if ((!type.contains("chat") && !type.contains("message"))
                    || type.contains("merchant") || type.contains("global") || type.contains("mention")) return;
            String incomingOrder = intent.getStringExtra("order_id");
            String incomingRoom = intent.getStringExtra("room_id");
            if (NavigationTargetPolicy.id(current).equalsIgnoreCase(incomingOrder)
                    || room(current).equalsIgnoreCase(incomingRoom)) {
                main.removeCallbacks(poll);
                if (!busy) main.postDelayed(poll, 220);
            }
        }
    };

    void start() {
        if (scope != null) return;
        scope = new DriverUiScope(activity);
        try {
            ContextCompat.registerReceiver(activity, updates,
                    new IntentFilter(TransivaFirebaseService.ACTION_DRIVER_DATA_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            registered = true;
        } catch (RuntimeException e) {
            NavigationDiagnostics.error(activity, "MAP_CHAT_RECEIVER_FAILED", e);
        }
        failures = 0;
        if (identity.equals(NavigationTargetPolicy.identity(order.get()))) {
            if (!pages.isEmpty()) {
                pageStarted = android.os.SystemClock.elapsedRealtime();
                main.post(position);
            } else if (!messages.isEmpty()) showNext();
        }
        main.post(poll);
    }

    void stop() {
        if (scope != null) scope.close();
        scope = null;
        if (registered) {
            try { activity.unregisterReceiver(updates); }
            catch (IllegalArgumentException ignored) {}
        }
        registered = false;
        busy = false;
        main.removeCallbacksAndMessages(null);
        hideBubble();
    }

    private final Runnable poll = this::fetch;

    private void fetch() {
        JSONObject current = order.get();
        DriverUiScope request = scope;
        if (request == null || !request.alive() || busy || NavigationTargetPolicy.id(current).isEmpty()
                || NavigationTargetPolicy.terminal(current)) return;
        if (messages.size() >= MAX_QUEUED) { moreWaiting = true; return; }
        String key = NavigationTargetPolicy.identity(current);
        if (!key.equals(identity)) {
            identity = key;
            lastId = 0;
            clear();
        }
        long after = lastId;
        String endpoint = Uri.parse("https://transiva.my.id/server/getChat.php").buildUpon()
                .appendQueryParameter("order_id", NavigationTargetPolicy.id(current))
                .appendQueryParameter("order_db_id", current.optString("id"))
                .appendQueryParameter("room_id", room(current))
                .appendQueryParameter("source", NavigationTargetPolicy.source(current))
                .appendQueryParameter("page_mode", after == 0 ? "latest" : "newer")
                .appendQueryParameter("limit", "24")
                .appendQueryParameter("last_id", String.valueOf(after)).build().toString();
        busy = true;
        if (!request.execute(() -> {
            try {
                JSONObject result = DriverMessageApi.get(new SessionManager(activity), endpoint, request.owner);
                request.post(() -> delivered(request, key, after, result));
            } catch (Exception e) {
                request.post(() -> delivered(request, key, after, null));
            }
        })) delivered(request, key, after, null);
    }

    private void delivered(DriverUiScope request, String key, long after, JSONObject result) {
        if (scope != request) return;
        busy = false;
        boolean success = result != null && result.optBoolean("success");
        if (success && result.optBoolean("ended")) { clear(); return; }
        if (!key.equals(NavigationTargetPolicy.identity(order.get()))) {
            main.post(poll);
            return;
        }
        boolean truncated = false;
        if (success && "driver".equalsIgnoreCase(result.optString("viewer_type", "driver"))) {
            JSONArray rows = result.optJSONArray("messages");
            if (rows != null) for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                long id = row.optLong("id");
                if (id <= lastId) continue;
                boolean unreadCustomer = "customer".equalsIgnoreCase(row.optString("sender_type"))
                        && row.optInt("is_read") == 0 && !row.optBoolean("is_read");
                String value = unreadCustomer ? preview(row.optString("message").trim()) : "";
                if (!value.isEmpty()) {
                    if (messages.size() >= MAX_QUEUED) { truncated = true; break; }
                    messages.addLast(value);
                }
                lastId = id;
            }
        }
        failures = success ? 0 : Math.min(failures + 1, 3);
        moreWaiting = truncated || (success && after > 0 && lastId > after && result.optBoolean("has_more"));
        if (pages.isEmpty()) showNext();
        updatePageBadge();
        main.removeCallbacks(poll);
        if (!truncated) main.postDelayed(poll, moreWaiting ? 350 : 7000L << failures);
    }

    private static String preview(String raw) {
        if (raw.startsWith("[[IMAGE")) return "Pelanggan mengirim foto";
        if (raw.startsWith("[[STICKER]]")) return "Pelanggan mengirim stiker";
        if (raw.startsWith("[[VOICE")) return "Pelanggan mengirim pesan suara";
        return raw;
    }

    private static String room(JSONObject order) {
        String value = order.optString("room_id", "").trim();
        if (value.isEmpty() || "null".equalsIgnoreCase(value)) value = order.optString("chat_room_id", "").trim();
        return value.isEmpty() || "null".equalsIgnoreCase(value) ? "ROOM-" + NavigationTargetPolicy.id(order) : value;
    }

    private void showNext() {
        String next = messages.pollFirst();
        if (next == null) { clear(); return; }
        if (moreWaiting && messages.size() <= MAX_QUEUED / 2 && !busy) {
            main.removeCallbacks(poll);
            main.postDelayed(poll, 180L);
        }
        int width = Math.max(dp(112), Math.min(dp(222), mapLayer.getWidth() - dp(20)));
        if (bubble.getLayoutParams().width != width) {
            bubble.getLayoutParams().width = width;
            bubble.requestLayout();
        }
        StaticLayout layout = StaticLayout.Builder.obtain(next, 0, next.length(), message.getPaint(), width - dp(24))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
        pages.clear();
        for (int line = 0; line < layout.getLineCount(); line += 3) {
            int end = Math.min(line + 2, layout.getLineCount() - 1);
            pages.add(next.substring(layout.getLineStart(line), layout.getLineEnd(end)).trim());
        }
        page = 0;
        showPage();
        main.removeCallbacks(position);
        main.post(position);
    }

    private void showPage() {
        String value = pages.get(page);
        message.setText(value);
        updatePageBadge();
        bubble.setContentDescription("Pesan pelanggan. " + value + ". Buka chat.");
        pageVisibleMs = 0;
        pageLimitMs = Math.max(3500, Math.min(8500, 1900 + value.length() * 46));
        pageStarted = android.os.SystemClock.elapsedRealtime();
    }

    private void updatePageBadge() {
        if (pages.size() > 1) pageBadge.setText((page + 1) + "/" + pages.size());
        else if (!messages.isEmpty()) pageBadge.setText("+" + messages.size());
        else pageBadge.setText("");
    }

    private long pageStarted;
    private int pageVisibleMs, pageLimitMs;
    private final Runnable position = new Runnable() {
        @Override public void run() {
            if (pages.isEmpty() || scope == null || !scope.alive()) return;
            PointF point = pickup.get();
            int width = bubble.getWidth(), height = bubble.getHeight();
            int top = Math.max(dp(4), visibleTop.getAsInt()) + dp(7);
            int bottom = Math.min(mapLayer.getHeight(), visibleBottom.getAsInt()) - dp(6);
            boolean onMap = point != null && point.x >= 0 && point.x <= mapLayer.getWidth()
                    && point.y >= top && point.y <= bottom;
            boolean visible = width > 0 && height > 0 && top + height <= bottom
                    && mapLayer.getWidth() >= width + dp(12);
            if (visible) {
                float x = onMap ? point.x - width / 2f : mapLayer.getWidth() - width - dp(10);
                float y = top;
                if (onMap) {
                    float above = point.y - height - dp(32);
                    if (above >= top) y = above;
                    else if (point.y + height + dp(12) <= bottom) y = point.y + dp(12);
                    else x = point.x < mapLayer.getWidth() / 2f
                            ? mapLayer.getWidth() - width - dp(10) : dp(8);
                }
                bubble.setTranslationX(Math.max(dp(8), Math.min(x, mapLayer.getWidth() - width - dp(8))));
                bubble.setTranslationY(y);
                if (bubble.getVisibility() != View.VISIBLE) {
                    bubble.setVisibility(View.VISIBLE);
                    bubble.setAlpha(0f);
                    bubble.setScaleX(.97f);
                    bubble.setScaleY(.97f);
                    bubble.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200L).start();
                }
                pageVisibleMs += 100;
            } else if (bubble.getVisibility() == View.VISIBLE) {
                bubble.animate().cancel();
                bubble.setVisibility(View.INVISIBLE);
            }
            if (pageVisibleMs >= pageLimitMs) {
                if (++page < pages.size()) {
                    showPage();
                    message.setAlpha(.58f);
                    message.animate().alpha(1f).setDuration(180L).start();
                }
                else { showNext(); return; }
            } else if (!visible && android.os.SystemClock.elapsedRealtime() - pageStarted > 18_000L) {
                showNext();
                return;
            }
            main.postDelayed(this, 100L);
        }
    };

    private void clear() {
        messages.clear();
        pages.clear();
        main.removeCallbacks(position);
        hideBubble();
    }

    private void hideBubble() {
        bubble.animate().cancel();
        message.animate().cancel();
        message.setAlpha(1f);
        bubble.setVisibility(View.INVISIBLE);
        bubble.setAlpha(0f);
    }

    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
