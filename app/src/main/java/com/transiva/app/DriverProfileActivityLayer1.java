package com.transiva.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.transiva.app.driver.data.DriverApiClient;
import com.transiva.app.driver.ui.DriverBottomNavigation;

import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.Locale;
abstract class DriverProfileActivityLayer1 extends Activity {

    protected static final String PROFILE_ENDPOINT = "driver_profile_native.php?v=";

    protected SessionManager session;
    protected DriverApiClient api;
    protected ProgressBar loading;
    protected boolean loadingData;
    protected boolean loggingOut;

    protected ImageView avatarView;
    protected ImageView ktpView;
    protected ImageView vehicleView;

    protected TextView nameView;
    protected TextView usernameView;
    protected TextView verificationBadge;
    protected TextView driverTypeBadge;
    protected TextView emailValue;
    protected TextView phoneValue;
    protected TextView plateValue;
    protected TextView statusValue;
    protected TextView verifiedAtValue;
    protected TextView onlineValue;
    protected TextView busyValue;
    protected TextView onlineSinceValue;
    protected TextView lastOrderValue;
    protected TextView locationValue;
    protected TextView accuracyValue;
    protected TextView speedValue;
    protected TextView balanceValue;
    protected TextView noteValue;
    protected TextView ratingValue;
    protected TextView ratingCountValue;
    protected TextView ratingStarsValue;
    protected TextView bpjsStatusValue;
    protected TextView bpjsSummaryValue;
    protected TextView performanceModeValue;
    protected TextView performanceRecommendedValue;
    protected Button performanceAutoButton;
    protected Button performanceLowButton;
    protected Button performanceNormalButton;
    protected Button performanceHighButton;
    protected JSONObject latestProfile;

    protected void bindProfile(JSONObject profile) {
        latestProfile = profile;
        String name = first(profile.optString("name"), profile.optString("username"), "Driver");
        String username = first(profile.optString("username"), "driver");
        String driverType = normalizeDriverType(profile.optString("driver_type"));
        String verification = normalizeVerification(profile.optString("verification_status"));

        nameView.setText(name);
        usernameView.setText("@" + username);
        verificationBadge.setText(verificationLabel(verification));
        verificationBadge.setTextColor(Color.parseColor(verificationTextColor(verification)));
        verificationBadge.setBackground(round(verificationBackground(verification), 14));
        driverTypeBadge.setText("Driver " + driverType);

        emailValue.setText(first(profile.optString("email"), "-"));
        phoneValue.setText(first(profile.optString("phone"), "-"));
        plateValue.setText(first(profile.optString("plate"), "-"));
        statusValue.setText(verificationLabel(verification));
        verifiedAtValue.setText(formatDate(profile.optString("verified_at")));
        balanceValue.setText(rupiah(profile.optDouble("balance", 0)));
        noteValue.setText(first(profile.optString("verification_note"), "-"));

        double driverRating = profile.optDouble("rating", 0);
        int ratingCount = profile.optInt("rating_count", profile.optInt("review_count", 0));
        if (ratingValue != null) ratingValue.setText(String.format(Locale.US, "%.1f", driverRating));
        if (ratingCountValue != null) {
            ratingCountValue.setText(ratingCount > 0
                    ? ratingCount + " penilaian customer"
                    : "Belum ada penilaian");
        }
        if (ratingStarsValue != null) ratingStarsValue.setText(stars(driverRating));

        boolean bpjsActive = readFlag(profile, "bpjs_active", "bpjs_is_active");
        if (bpjsStatusValue != null) {
            bpjsStatusValue.setText(bpjsActive ? "Aktif" : "Tidak Aktif");
            bpjsStatusValue.setTextColor(Color.parseColor(bpjsActive ? "#0E9F4B" : "#C62828"));
        }
        if (bpjsSummaryValue != null) {
            bpjsSummaryValue.setText(first(profile.optString("bpjs_number"), profile.optString("bpjs_no"), "Belum diisi"));
        }

        boolean online = profile.optInt("is_online", 0) == 1;
        boolean busy = profile.optInt("is_busy", 0) == 1;
        onlineValue.setText(online ? "Online" : "Offline");
        onlineValue.setTextColor(Color.parseColor(online ? "#0E9F4B" : "#64748B"));
        busyValue.setText(busy ? "Sedang Menangani Order" : "Tersedia");
        onlineSinceValue.setText(formatDate(profile.optString("online_since")));
        lastOrderValue.setText(formatDate(profile.optString("last_order_at")));

        String latitude = clean(profile.optString("latitude"));
        String longitude = clean(profile.optString("longitude"));
        locationValue.setText(latitude.isEmpty() || longitude.isEmpty() ? "-" : latitude + ", " + longitude);

        String accuracy = clean(profile.optString("location_accuracy"));
        accuracyValue.setText(accuracy.isEmpty() ? "-" : accuracy + " meter");
        String speed = clean(profile.optString("location_speed"));
        speedValue.setText(speed.isEmpty() ? "-" : speed + " m/s");

        String driverPhoto = first(profile.optString("driver_photo"), profile.optString("profile_photo"));
        String ktpPhoto = profile.optString("ktp_photo");
        String vehiclePhoto = profile.optString("vehicle_photo");

        loadImage(avatarView, driverPhoto, drawableOrFallback("ic_nav_profile"));
        loadImage(ktpView, ktpPhoto, android.R.drawable.ic_menu_report_image);
        loadImage(vehicleView, vehiclePhoto, android.R.drawable.ic_menu_report_image);
        bindImageOpen(ktpView, ktpPhoto);
        bindImageOpen(vehicleView, vehiclePhoto);
    }

    protected void loadImage(ImageView target, String url, int fallback) {
        RemoteImageLoader.loadCenterCrop(target, absoluteUrl(url), fallback);
    }

    protected void bindImageOpen(ImageView target, String rawUrl) {
        String url = absoluteUrl(rawUrl);
        target.setOnClickListener(url.isEmpty() ? null : view -> {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(android.net.Uri.parse(url));
            startActivity(intent);
        });
    }

    protected String absoluteUrl(String value) {
        String clean = clean(value);
        if (clean.isEmpty()) return "";
        if (clean.startsWith("http://") || clean.startsWith("https://")) return clean;
        while (clean.startsWith("/")) clean = clean.substring(1);

        // Path foto driver dari API disimpan relatif sebagai uploads/drivers/....
        // Folder fisiknya berada di public_html/server/uploads/drivers, jadi
        // URL publik harus melewati /server/. Tetap dukung nilai lama yang
        // sudah mengandung server/ agar tidak terjadi double prefix.
        if (clean.startsWith("uploads/")) {
            return "https://transiva.my.id/server/" + clean;
        }
        if (clean.startsWith("server/")) {
            return "https://transiva.my.id/" + clean;
        }
        return "https://transiva.my.id/" + clean;
    }

    protected boolean readFlag(JSONObject object, String... keys) {
        if (object == null || keys == null) return false;
        for (String key : keys) {
            if (!object.has(key) || object.isNull(key)) continue;
            Object value = object.opt(key);
            if (value instanceof Boolean) return (Boolean) value;
            if (value instanceof Number) return ((Number) value).intValue() == 1;
            String text = clean(String.valueOf(value)).toLowerCase(Locale.ROOT);
            if ("1".equals(text) || "true".equals(text) || "active".equals(text) || "aktif".equals(text) || "yes".equals(text)) return true;
        }
        return false;
    }

    protected String normalizeDriverType(String value) {
        return "car".equals(clean(value).toLowerCase(Locale.ROOT)) ? "Car" : "Bike";
    }

    protected String normalizeVerification(String value) {
        String clean = clean(value).toLowerCase(Locale.ROOT);
        if ("verified".equals(clean) || "rejected".equals(clean) || "suspended".equals(clean)) return clean;
        return "pending";
    }

    protected String verificationLabel(String status) {
        if ("verified".equals(status)) return "✓ Terverifikasi";
        if ("rejected".equals(status)) return "Ditolak";
        if ("suspended".equals(status)) return "Ditangguhkan";
        return "Menunggu Verifikasi";
    }

    protected String verificationBackground(String status) {
        if ("verified".equals(status)) return "#E7FFF2";
        if ("rejected".equals(status)) return "#FFECEC";
        if ("suspended".equals(status)) return "#FFF0E5";
        return "#FFF7E6";
    }

    protected String verificationTextColor(String status) {
        if ("verified".equals(status)) return "#0A8F4C";
        if ("rejected".equals(status)) return "#C62828";
        if ("suspended".equals(status)) return "#B45309";
        return "#C96A05";
    }

    protected String stars(double rating) {
        int filled = (int) Math.round(Math.max(0, Math.min(5, rating)));
        StringBuilder value = new StringBuilder(5);
        for (int i = 1; i <= 5; i++) value.append(i <= filled ? '★' : '☆');
        return value.toString();
    }

    protected String formatDate(String value) {
        String clean = clean(value);
        return clean.isEmpty() ? "-" : clean.replace("T", " ");
    }

    protected void confirmLogout() {
        if (loggingOut) return;
        PremiumDialogs.builder(this)
                .setTitle("Keluar Akun")
                .setMessage("Keluar akan melepas akun dari perangkat ini seperti Reset Perangkat. Setelah berhasil, akun dapat langsung login di HP lain.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Keluar", (dialog, which) -> logoutAndReleaseDevice())
                .show();
    }

    protected void logoutAndReleaseDevice() {
        if (loggingOut) return;
        loggingOut = true;
        DriverDeviceDisconnectClient.disconnect(this, new DriverDeviceDisconnectClient.Callback() {
            @Override public void onSuccess() {
                DriverServiceController.stop(DriverProfileActivity.this);
                try { new SessionManager(DriverProfileActivity.this).forceLogout("driver_profile_logout_device_reset"); }
                catch (Exception ignored) {}
                loggingOut = false;
                redirectLogin();
            }

            @Override public void onError(String message) {
                loggingOut = false;
                if (isFinishing()) return;
                PremiumDialogs.builder(DriverProfileActivity.this)
                        .setTitle("Gagal keluar akun")
                        .setMessage(message + "\n\nAkun belum dilepas dari perangkat. Coba lagi saat koneksi stabil agar akun tetap dapat dipindahkan dengan aman.")
                        .setPositiveButton("OK", null)
                        .show();
            }
        });
    }

    protected LinearLayout whiteCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(15), dp(15), dp(15), dp(15));
        card.setBackground(roundStroke("#FFFFFF", "#E1EAF5", 18, 1));
        card.setElevation(dp(1));
        return card;
    }

    protected View sectionTitle(String title, String subtitle) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(text(title, 16, "#0B3A78", true));
        box.addView(text(subtitle, 10, "#718096", false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(9));
        box.setLayoutParams(lp);
        return box;
    }

    protected TextView addInfoRow(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(11), 0, dp(10));
        TextView left = text(label, 11, "#64748B", false);
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        TextView right = text(value, 11, "#0B3A78", true);
        right.setGravity(Gravity.END);
        right.setMaxWidth(dp(190));
        row.addView(right);
        parent.addView(row);
        return right;
    }

    protected ImageView documentImage(String description) {
        ImageView image = new ImageView(this);
        image.setContentDescription(description);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImageResource(android.R.drawable.ic_menu_report_image);
        image.setBackground(round("#EEF5FD", 14));
        image.setClipToOutline(true);
        image.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        return image;
    }

    protected View documentBox(ImageView image, String label) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(image, new LinearLayout.LayoutParams(-1, dp(116)));
        TextView caption = text(label, 10, "#0B3A78", true);
        caption.setGravity(Gravity.CENTER);
        caption.setPadding(0, dp(6), 0, 0);
        box.addView(caption);
        return box;
    }

    protected LinearLayout.LayoutParams documentLp(boolean margin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        if (margin) lp.setMargins(dp(9), 0, 0, 0);
        return lp;
    }

    protected TextView badge(String value, String background, String color) {
        TextView badge = text(value, 9, color, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(9), dp(5), dp(9), dp(5));
        badge.setBackground(round(background, 14));
        return badge;
    }

    protected Button dangerButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(Color.WHITE);
        button.setBackground(gradient("#EF4444", "#DC2626", 14));
        return button;
    }

    protected LinearLayout.LayoutParams sectionLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(14), 0, 0);
        return lp;
    }

    protected TextView text(String value, int size, String color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value == null ? "" : value);
        view.setTextSize(size);
        view.setTextColor(Color.parseColor(color));
        view.setIncludeFontPadding(false);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    protected GradientDrawable round(String fill, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.parseColor(fill));
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    protected GradientDrawable roundStroke(String fill, String stroke, int radius, int width) {
        GradientDrawable drawable = round(fill, radius);
        drawable.setStroke(dp(width), Color.parseColor(stroke));
        return drawable;
    }

    protected GradientDrawable gradient(String start, String end, int radius) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.parseColor(start), Color.parseColor(end)}
        );
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    protected int drawableOrFallback(String name) {
        int id = getResources().getIdentifier(name, "drawable", getPackageName());
        return id != 0 ? id : android.R.drawable.sym_def_app_icon;
    }

    protected String rupiah(double amount) {
        NumberFormat format = NumberFormat.getCurrencyInstance(new Locale("id", "ID"));
        format.setMaximumFractionDigits(0);
        format.setMinimumFractionDigits(0);
        return format.format(amount);
    }

    protected String first(String... values) {
        if (values == null) return "";
        for (String value : values) {
            String clean = clean(value);
            if (!clean.isEmpty()) return clean;
        }
        return "";
    }

    protected String clean(String value) {
        if (value == null) return "";
        value = value.trim();
        if (value.isEmpty() || "null".equalsIgnoreCase(value) || "undefined".equalsIgnoreCase(value)) return "";
        return value;
    }

    protected int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    protected void setLoading(boolean value) {
        loadingData = value;
        if (loading != null) loading.setVisibility(value ? View.VISIBLE : View.GONE);
    }

    protected void showInfo(String title, String message) {
        PremiumDialogs.builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }
    // Cross-layer contracts keep the split type-safe without duplicating state.
    protected abstract void onCreate(Bundle savedInstanceState);
    protected abstract void onResume();
    protected abstract void onDestroy();
    protected abstract boolean validDriverSession();
    protected abstract void redirectLogin();
    protected abstract View buildScreen();
    protected abstract View buildHeader();
    protected abstract View buildIdentityCard();
    protected abstract View buildPerformanceCard();
    protected abstract Button performanceButton(String label);
    protected abstract void selectPerformanceMode(DevicePerformanceProfile.UserMode mode);
    protected abstract void applyPerformanceMode(DevicePerformanceProfile.UserMode mode);
    protected abstract void updatePerformanceCard();
    protected abstract void stylePerformanceButton(Button button, boolean active);
    protected abstract View buildRatingCard();
    protected abstract View buildDriverInfoCard();
    protected abstract View buildStatusCard();
    protected abstract View buildBpjsCard();
    protected abstract View buildDocumentCard();
    protected abstract View buildSecurityCard();
    protected abstract void loadProfile();

}
