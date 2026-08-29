package com.transiva.app;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

/**
 * Lightweight Messenger-style overlay companion.
 * Does not own GPS/polling and deliberately is NOT a second foreground service.
 */
public final class DriverBubbleOverlayService extends Service {
    public static final String ACTION_START = "com.transiva.app.BUBBLE_START";
    public static final String ACTION_STOP = "com.transiva.app.BUBBLE_STOP";
    private static final long DEFAULT_PREVIEW_MS = 4200L;
    private static volatile DriverBubbleOverlayService instance;

    private WindowManager wm;
    private FrameLayout bubbleRoot;
    private TextView badge;
    private TextView preview;
    private TextView closeTarget;
    private WindowManager.LayoutParams bubbleLp;
    private WindowManager.LayoutParams previewLp;
    private WindowManager.LayoutParams closeLp;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Runnable hidePreview;
    private String lastType = "";
    private String lastOrderId = "";
    private String lastRoomId = "";
    private long lastMentionId = 0L;

    public static void publish(Context context, String type, String text,
                               String orderId, String roomId, long mentionId,
                               boolean newOrder) {
        DriverBubbleOverlayService s = instance;
        if (s == null) return;
        s.main.post(() -> s.showEvent(type, text, orderId, roomId, mentionId, newOrder));
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (!canOverlay()) { stopSelf(); return; }
        createViews();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            getSharedPreferences("transiva_bubble", MODE_PRIVATE).edit().putBoolean("enabled", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!canOverlay()) { stopSelf(); return START_NOT_STICKY; }
        if (bubbleRoot == null) createViews();
        return START_STICKY;
    }

    private boolean canOverlay() {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
    }

    private void createViews() {
        if (wm == null || bubbleRoot != null) return;
        final int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        bubbleRoot = new FrameLayout(this);
        bubbleRoot.setClipChildren(false);
        ImageView icon = new ImageView(this);
        android.graphics.Bitmap bubbleBitmap = ResourceUpdateManager.loadBitmapOverride(this, "images/bubble_icon.png");
        if (bubbleBitmap == null) bubbleBitmap = ResourceUpdateManager.loadBitmapOverride(this, "images/bubble_icon.webp");
        if (bubbleBitmap != null) icon.setImageBitmap(bubbleBitmap);
        else icon.setImageResource(R.mipmap.ic_launcher);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.setBackground(circle("#FFFFFF", "#D7E8FF", 2));
        icon.setPadding(dp(3), dp(3), dp(3), dp(3));
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(58), dp(58));
        iconLp.gravity = Gravity.CENTER;
        bubbleRoot.addView(icon, iconLp);

        badge = new TextView(this);
        badge.setText("1");
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(11);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(circle("#E53935", "#FFFFFF", 2));
        badge.setVisibility(View.GONE);
        FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP | Gravity.END);
        badgeLp.topMargin = dp(1);
        bubbleRoot.addView(badge, badgeLp);

        bubbleLp = new WindowManager.LayoutParams(dp(68), dp(68), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        bubbleLp.gravity = Gravity.TOP | Gravity.START;
        bubbleLp.x = dp(10);
        bubbleLp.y = dp(180);
        wm.addView(bubbleRoot, bubbleLp);

        preview = new TextView(this);
        preview.setTextColor(Color.WHITE);
        preview.setTextSize(13);
        preview.setGravity(Gravity.CENTER_VERTICAL);
        preview.setMaxLines(2);
        preview.setPadding(dp(14), dp(9), dp(14), dp(9));
        preview.setBackground(round("#E91B2533", 18));
        previewLp = new WindowManager.LayoutParams(dp(245), WindowManager.LayoutParams.WRAP_CONTENT,
                type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        previewLp.gravity = Gravity.TOP | Gravity.START;
        previewLp.x = dp(80);
        previewLp.y = bubbleLp.y + dp(8);
        preview.setVisibility(View.GONE);
        wm.addView(preview, previewLp);

        closeTarget = new TextView(this);
        closeTarget.setText("×");
        closeTarget.setTextColor(Color.WHITE);
        closeTarget.setTextSize(34);
        closeTarget.setGravity(Gravity.CENTER);
        closeTarget.setBackground(circle("#D9D32F2F", "#55FFFFFF", 1));
        closeTarget.setVisibility(View.GONE);
        closeLp = new WindowManager.LayoutParams(dp(74), dp(74), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        closeLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        closeLp.y = dp(28);
        wm.addView(closeTarget, closeLp);

        installDrag();
        bubbleRoot.setOnClickListener(v -> openLastTarget());
    }

    private void installDrag() {
        bubbleRoot.setOnTouchListener(new View.OnTouchListener() {
            float downRawX, downRawY;
            int startX, startY;
            boolean dragging;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = e.getRawX(); downRawY = e.getRawY();
                        startX = bubbleLp.x; startY = bubbleLp.y; dragging = false;
                        closeTarget.setVisibility(View.VISIBLE);
                        hidePreviewNow();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downRawX;
                        float dy = e.getRawY() - downRawY;
                        if (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4)) dragging = true;
                        bubbleLp.x = startX + Math.round(dx);
                        bubbleLp.y = Math.max(0, startY + Math.round(dy));
                        try { wm.updateViewLayout(bubbleRoot, bubbleLp); } catch (Throwable ignored) {}
                        boolean hit = isOverClose(e.getRawX(), e.getRawY());
                        closeTarget.setAlpha(hit ? 1f : .72f);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        boolean close = isOverClose(e.getRawX(), e.getRawY());
                        closeTarget.setVisibility(View.GONE);
                        if (close) {
                            getSharedPreferences("transiva_bubble", MODE_PRIVATE).edit().putBoolean("enabled", false).apply();
                            stopSelf();
                            return true;
                        }
                        if (!dragging) v.performClick();
                        snapToEdge();
                        return true;
                }
                return false;
            }
        });
    }

    private boolean isOverClose(float rawX, float rawY) {
        android.graphics.Point p = new android.graphics.Point();
        wm.getDefaultDisplay().getSize(p);
        float cx = p.x / 2f;
        float cy = p.y - dp(65);
        return Math.hypot(rawX - cx, rawY - cy) < dp(82);
    }

    private void snapToEdge() {
        android.graphics.Point p = new android.graphics.Point();
        wm.getDefaultDisplay().getSize(p);
        int mid = p.x / 2;
        bubbleLp.x = bubbleLp.x + dp(34) < mid ? dp(8) : Math.max(dp(8), p.x - dp(76));
        try { wm.updateViewLayout(bubbleRoot, bubbleLp); } catch (Throwable ignored) {}
    }

    private void showEvent(String type, String text, String orderId, String roomId,
                           long mentionId, boolean newOrder) {
        if (bubbleRoot == null) return;
        lastType = safe(type);
        lastOrderId = safe(orderId);
        lastRoomId = safe(roomId);
        lastMentionId = mentionId;
        if (newOrder) {
            badge.setText("1");
            badge.setVisibility(View.VISIBLE);
        }
        String message = safe(text);
        if (message.isEmpty()) return;
        preview.setText(message);
        previewLp.x = bubbleLp.x < dp(100) ? bubbleLp.x + dp(70) : Math.max(dp(8), bubbleLp.x - dp(252));
        previewLp.y = bubbleLp.y + dp(8);
        try { wm.updateViewLayout(preview, previewLp); } catch (Throwable ignored) {}
        preview.setAlpha(0f);
        preview.setVisibility(View.VISIBLE);
        preview.animate().alpha(1f).setDuration(160L).start();
        if (hidePreview != null) main.removeCallbacks(hidePreview);
        hidePreview = () -> {
            if (preview == null) return;
            preview.animate().alpha(0f).setDuration(180L).withEndAction(() -> {
                if (preview != null) preview.setVisibility(View.GONE);
            }).start();
        };
        long previewMs = DEFAULT_PREVIEW_MS;
        try {
            org.json.JSONObject cfg = ResourceUpdateManager.loadJsonOverride(this, "config/bubble.json");
            if (cfg != null) previewMs = Math.max(1800L, Math.min(8000L, cfg.optLong("preview_ms", DEFAULT_PREVIEW_MS)));
        } catch (Throwable ignored) {}
        main.postDelayed(hidePreview, previewMs);
    }

    private void openLastTarget() {
        Intent i;
        if (lastType.contains("mention") || "driver_global_mention".equals(lastType)) {
            i = new Intent(this, DriverGlobalChatActivity.class);
            if (lastMentionId > 0) i.putExtra("jump_message_id", lastMentionId);
        } else if (lastType.contains("chat") || lastType.contains("message")) {
            i = new Intent(this, DriverChatRoomActivity.class);
            if (!lastOrderId.isEmpty()) i.putExtra("order_id", lastOrderId);
            if (!lastRoomId.isEmpty()) i.putExtra("room_id", lastRoomId);
        } else {
            i = new Intent(this, DriverDashboardActivity.class);
        }
        badge.setVisibility(View.GONE);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try { startActivity(i); } catch (Throwable ignored) {}
    }

    private void hidePreviewNow() {
        if (hidePreview != null) main.removeCallbacks(hidePreview);
        if (preview != null) preview.setVisibility(View.GONE);
    }

    @Override public void onDestroy() {
        instance = null;
        hidePreviewNow();
        remove(bubbleRoot); remove(preview); remove(closeTarget);
        bubbleRoot = null; preview = null; closeTarget = null;
        super.onDestroy();
    }

    private void remove(View v) { if (v == null || wm == null) return; try { wm.removeView(v); } catch (Throwable ignored) {} }
    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private String safe(String s) { return s == null ? "" : s.trim(); }
    private GradientDrawable circle(String fill, String stroke, int sw) { GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL); g.setColor(Color.parseColor(fill)); g.setStroke(dp(sw), Color.parseColor(stroke)); return g; }
    private GradientDrawable round(String fill, int r) { GradientDrawable g = new GradientDrawable(); g.setColor(Color.parseColor(fill)); g.setCornerRadius(dp(r)); return g; }
}
