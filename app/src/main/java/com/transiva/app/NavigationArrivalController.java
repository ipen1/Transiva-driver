package com.transiva.app;

import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;

/** Navigation renders and executes the shared trip action for the current order. */
final class NavigationArrivalController {
    private final DriverNavigationActivity host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinearLayout dock;
    private final TextView hint;
    private TripSwipeControl swipe;
    private TripActionSession session;
    private boolean active;
    private String actionKey = "";
    private final Runnable changed = this::render;

    NavigationArrivalController(DriverNavigationActivity host, LinearLayout dock) {
        this.host = host; this.dock = dock;
        session = TripActionSession.get(host, host.order);
        adopt();
        hint = new TextView(host);
        hint.setGravity(Gravity.CENTER);
        hint.setTextColor(DriverThemeTokens.textSecondary(host));
        hint.setTextSize(11);
        hint.setPadding(host.dp(3), host.dp(2), host.dp(3), 0);
        dock.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        render();
    }

    void active(boolean value) {
        active = value;
        main.removeCallbacks(tick);
        session.listen(changed, value);
        if (value) { adopt(); main.post(tick); }
        else if (swipe != null) swipe.setEnabled(false);
    }

    void resetOrder() {
        session.listen(changed, false);
        session = TripActionSession.get(host, host.order);
        session.listen(changed, active);
        adopt(); render();
    }

    void fix(Location raw) { session.fix(raw); }
    void synced(JSONObject order) { session.synced(order); adopt(); render(); }
    void close() { active(false); }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!active) return;
            render(); main.postDelayed(this, 750);
        }
    };

    private void adopt() {
        JSONObject order = session.order();
        if (NavigationTargetPolicy.same(host.order, order)) host.order = TripActionSession.merge(host.order, order);
    }

    private void render() {
        if (host.isFinishing()) return;
        TripActionPolicy.Action action = TripActionPolicy.resolve(session);
        String key = action.next + "|" + action.stop;
        if (swipe == null || !key.equals(actionKey)) {
            if (swipe != null) dock.removeView(swipe);
            actionKey = key;
            swipe = new TripSwipeControl(host, action.label, this::submit);
            int height = Math.max(48, (int) Math.ceil(40 * host.getResources().getConfiguration().fontScale));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, host.dp(height));
            lp.topMargin = host.dp(5);
            dock.addView(swipe, dock.indexOfChild(hint), lp);
        }
        boolean enabled = active && action.enabled;
        if (swipe.isEnabled() != enabled) swipe.setEnabled(enabled);
        if (!action.hint.contentEquals(hint.getText())) hint.setText(action.hint);
        int hintVisibility = action.hint.isEmpty() ? TextView.GONE : TextView.VISIBLE;
        if (hint.getVisibility() != hintVisibility) hint.setVisibility(hintVisibility);
    }

    private void submit() {
        TripActionPolicy.Action action = TripActionPolicy.resolve(session);
        if (!active || !action.enabled) { render(); return; }
        TripNavigationAction.execute(host, session, action);
    }
}
