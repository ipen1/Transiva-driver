package com.transiva.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.widget.Button;
import android.widget.TextView;

import com.transiva.app.driver.domain.DriverOrder;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Owns offer deadlines, countdown rendering and countdown haptics for dashboard. */
public final class DashboardOfferCountdownController {
    public interface Listener { void onOfferExpired(); }

    private static final long SERVER_DRIFT_TOLERANCE_MS = 1800L;
    private final Context context;
    private final Listener listener;
    private final Vibrator vibrator;
    private final float density;
    private final Map<String, Long> deadlines = new HashMap<>();
    private final Map<String, TextView> views = new HashMap<>();
    private final Map<String, Button> buttons = new HashMap<>();
    private final Map<String, Integer> lastVibratedSecond = new HashMap<>();
    private final Set<String> expiredRefreshRequested = new HashSet<>();

    public DashboardOfferCountdownController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        this.density = context.getResources().getDisplayMetrics().density;
    }

    public String key(DriverOrder order) {
        String cycle = "";
        if (order != null && order.raw != null) {
            cycle = first(order.raw.optString("offer_cycle_key", ""),
                    order.raw.optString("offer_action_token", ""),
                    order.raw.optString("offer_expired_at", ""));
        }
        return clean(order == null ? "" : order.source) + ":" + clean(order == null ? "" : order.id) + ":" + clean(cycle);
    }

    public void beginSnapshot() { views.clear(); buttons.clear(); }

    public void syncDeadline(DriverOrder order) {
        if (order == null || order.remainingSeconds < 0) return;
        String key = key(order);
        long now = SystemClock.elapsedRealtime();
        long candidate = now + order.remainingSeconds * 1000L;
        Long current = deadlines.get(key);
        if (current == null) {
            deadlines.put(key, candidate); expiredRefreshRequested.remove(key); return;
        }
        long currentRemaining = current - now;
        long candidateRemaining = candidate - now;
        boolean revivedByServer = currentRemaining <= 0L && candidateRemaining > 0L;
        boolean newerOfferWindow = candidate > current + Math.max(SERVER_DRIFT_TOLERANCE_MS, 3000L);
        if (revivedByServer || newerOfferWindow) {
            deadlines.put(key, candidate); expiredRefreshRequested.remove(key); return;
        }
        if (candidate < current - SERVER_DRIFT_TOLERANCE_MS) deadlines.put(key, candidate);
    }

    public void retain(Set<String> activeKeys) {
        deadlines.keySet().retainAll(activeKeys);
        lastVibratedSecond.keySet().retainAll(activeKeys);
        expiredRefreshRequested.retainAll(activeKeys);
    }

    public void bindCountdown(String key, TextView view) { if (key != null && view != null) views.put(key, view); }
    public void bindButton(String key, Button button) { if (key != null && button != null) buttons.put(key, button); }

    public long remainingMillis(String key) {
        Long deadline = deadlines.get(key);
        return deadline == null ? 0L : Math.max(0L, deadline - SystemClock.elapsedRealtime());
    }

    public void tick() {
        if (views.isEmpty()) return;
        Set<String> keys = new HashSet<>(views.keySet());
        for (String key : keys) {
            TextView view = views.get(key);
            if (view == null) continue;
            long remainingMs = remainingMillis(key);
            int seconds = remainingMs <= 0L ? 0 : (int)Math.ceil(remainingMs / 1000.0);
            render(view, seconds);
            Button button = buttons.get(key);
            if (seconds <= 0) {
                if (button != null) { button.setEnabled(false); button.setText("Tawaran berakhir"); }
                if (expiredRefreshRequested.add(key) && listener != null) listener.onOfferExpired();
            } else {
                if (button != null) { button.setEnabled(true); button.setText("Ambil Order"); }
                maybeVibrate(key, seconds);
            }
        }
    }

    private void render(TextView view, int seconds) {
        int border = countdownColor(seconds);
        int fill = mixWithWhite(border, .90f);
        view.setText(seconds > 0 ? "⏱ " + seconds + " detik" : "Waktu habis");
        view.setTextColor(border);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill); bg.setCornerRadius(dp(999)); bg.setStroke(dp(seconds <= 6 ? 2 : 1), border);
        view.setBackground(bg);
        if (seconds > 0 && seconds <= 6 && !DevicePerformanceProfile.get(context).reduceMapMotion) {
            view.animate().cancel(); view.setScaleX(1.08f); view.setScaleY(1.08f);
            view.animate().scaleX(1f).scaleY(1f).setDuration(180L).start();
        } else { view.animate().cancel(); view.setScaleX(1f); view.setScaleY(1f); }
    }

    private void maybeVibrate(String key, int seconds) {
        if (seconds < 1 || seconds > 9 || !DriverAppSettings.isVibrationEnabled(context)) return;
        Integer last = lastVibratedSecond.get(key);
        if (last != null && last == seconds) return;
        lastVibratedSecond.put(key, seconds);
        try {
            if (vibrator == null || !vibrator.hasVibrator()) return;
            long duration = seconds <= 2 ? 120L : 70L;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE));
            else vibrator.vibrate(duration);
        } catch (Throwable ignored) {}
    }

    private int countdownColor(int seconds) {
        if (seconds <= 0) return Color.parseColor("#991B1B");
        float normalized = Math.max(0f, Math.min(1f, (seconds - 1f) / 14f));
        return Color.HSVToColor(new float[]{120f * normalized, .88f, .82f});
    }

    private static int mixWithWhite(int color, float whiteRatio) {
        float r = Math.max(0f, Math.min(1f, whiteRatio));
        return Color.rgb(Math.round(Color.red(color)*(1f-r)+255f*r), Math.round(Color.green(color)*(1f-r)+255f*r), Math.round(Color.blue(color)*(1f-r)+255f*r));
    }

    private int dp(int value) { return (int)(value * density + .5f); }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static String first(String... values) {
        if (values != null) for (String v : values) if (v != null && !v.trim().isEmpty() && !"null".equalsIgnoreCase(v.trim())) return v.trim();
        return "";
    }
}
