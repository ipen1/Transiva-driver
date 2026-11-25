package com.transiva.app;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.CompoundButton;
import android.widget.Switch;

import java.util.Map;

/** Opens the existing authenticated dashboard for a targeted, live offer. */
final class DashboardOfferForeground {
    private static final String PREFS = "driver_offer_presentation";
    private static final String ENABLED = "open_order_on_top";
    static final String SIMPEL_REQUEST = "simpel_request_contact";
    static final String ACTION_SIMPEL_REQUEST = "com.transiva.app.SIMPEL_REQUEST_RECEIVED";

    static String simpelContactId(Map<String, String> data) {
        if (data == null || !"transsend_contact".equals(data.get("type"))
                || !"transsend_requested".equals(data.get("event"))
                || !"Permintaan Trans Simpel".equals(data.get("title"))) return "";
        String id = clean(data.get("contact_id"));
        return id.matches("PUD[0-9A-F]{24}") ? id : "";
    }

    static Intent simpelIntent(Context context, String contactId) {
        return new Intent(context, DriverDashboardActivity.class)
                .putExtra("from_fcm", true).putExtra(SIMPEL_REQUEST, contactId);
    }

    static void maybeOpen(Context context, String orderId, Map<String, String> data) {
        if (data == null || orderId.isEmpty() || PushExpiryPolicy.expiredOrder(data, System.currentTimeMillis())) return;
        String token = clean(data.get("action_token"));
        String offeredDriver = clean(data.get("offered_driver"));
        if (token.isEmpty() && offeredDriver.isEmpty()) return;
        if (!mayOpen(context, offeredDriver)) return;
        Intent open = new Intent(context, DriverDashboardActivity.class)
                .putExtra("from_fcm", true).putExtra("order_id", orderId)
                .putExtra("order_db_id", clean(data.get("order_db_id")))
                .putExtra("dashboard_offer_token", token);
        openDashboard(context, open);
    }

    static void maybeOpenSimpel(Context context, String contactId) {
        if (mayOpen(context, "")) openDashboard(context, simpelIntent(context, contactId));
    }

    private static boolean mayOpen(Context context, String offeredDriver) {
        if (!preferences(context).getBoolean(ENABLED, true) || !DriverBubbleController.canOverlay(context)
                || TransivaDriverApplication.hasResumedActivity()) return false;
        SessionManager session = new SessionManager(context);
        String online = session.get("driver_server_online");
        if (!session.isLoggedIn() || !"driver".equals(session.normalizeRole(session.getRole()))
                || !"1".equals(online.isEmpty() ? session.get("driver_is_online") : online)) return false;
        if (!offeredDriver.isEmpty() && !offeredDriver.equalsIgnoreCase(session.getUsername())) return false;
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        PowerManager power = context.getSystemService(PowerManager.class);
        return (keyguard == null || !keyguard.isKeyguardLocked()) && (power == null || power.isInteractive());
    }

    private static void openDashboard(Context context, Intent open) {
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try { context.startActivity(open); }
        catch (RuntimeException error) { NavigationDiagnostics.error(context, "OFFER_FOREGROUND_BLOCKED", error); }
    }

    static Switch setting(Activity activity) {
        Switch toggle = new Switch(activity);
        toggle.setText("Tampilkan order di atas aplikasi lain");
        toggle.setTextColor(DriverThemeTokens.textPrimary(activity));
        toggle.setMinHeight(Math.round(48 * activity.getResources().getDisplayMetrics().density));
        Runnable refresh = () -> refreshSetting(activity, toggle);
        toggle.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(android.view.View view) { refresh.run(); }
            @Override public void onViewDetachedFromWindow(android.view.View view) {}
        });
        refresh.run();
        return toggle;
    }

    static void refreshSetting(Activity activity, Switch toggle) {
        toggle.setOnCheckedChangeListener(null);
        toggle.setChecked(DriverBubbleController.canOverlay(activity) && preferences(activity).getBoolean(ENABLED, true));
        toggle.setOnCheckedChangeListener((button, enabled) -> changeSetting(activity, button, enabled));
    }

    private static void changeSetting(Activity activity, CompoundButton button, boolean enabled) {
        preferences(activity).edit().putBoolean(ENABLED, enabled).apply();
        if (!enabled || DriverBubbleController.canOverlay(activity)) return;
        button.setOnCheckedChangeListener(null);
        button.setChecked(false);
        button.setOnCheckedChangeListener((view, value) -> changeSetting(activity, view, value));
        PremiumDialogs.builder(activity).setTitle("Tampilkan tawaran order")
                .setMessage("Izinkan Transiva tampil di atas aplikasi lain agar tawaran bisa langsung dibuka saat Anda online.")
                .setNegativeButton("Nanti", null)
                .setPositiveButton("Buka pengaturan", (d, w) -> {
                    try {
                        activity.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + activity.getPackageName())));
                    } catch (RuntimeException error) {
                        NavigationDiagnostics.error(activity, "OFFER_OVERLAY_SETTINGS_FAILED", error);
                    }
                }).show();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
