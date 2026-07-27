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

public class DriverProfileActivity extends Activity {

    private static final String PROFILE_ENDPOINT = "driver_profile_native.php?v=";

    private SessionManager session;
    private DriverApiClient api;
    private ProgressBar loading;
    private boolean loadingData;

    private ImageView avatarView;
    private ImageView ktpView;
    private ImageView vehicleView;

    private TextView nameView;
    private TextView usernameView;
    private TextView verificationBadge;
    private TextView driverTypeBadge;
    private TextView emailValue;
    private TextView phoneValue;
    private TextView plateValue;
    private TextView statusValue;
    private TextView verifiedAtValue;
    private TextView onlineValue;
    private TextView busyValue;
    private TextView onlineSinceValue;
    private TextView lastOrderValue;
    private TextView locationValue;
    private TextView accuracyValue;
    private TextView speedValue;
    private TextView balanceValue;
    private TextView noteValue;
    private TextView bpjsStatusValue;
    private TextView bpjsSummaryValue;
    private JSONObject latestProfile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#0B7CFF"));
        getWindow().setNavigationBarColor(Color.parseColor("#071426"));

        session = new SessionManager(this);
        if (!validDriverSession()) {
            redirectLogin();
            return;
        }

        api = new DriverApiClient(session);
        setContentView(buildScreen());
        DriverAppSettings.apply(this);
        loadProfile();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (api != null && !loadingData) loadProfile();
    }

    @Override
    protected void onDestroy() {
        if (api != null) api.shutdown();
        super.onDestroy();
    }

    private boolean validDriverSession() {
        return session != null
                && session.isLoggedIn()
                && "driver".equals(session.normalizeRole(session.getRole()))
                && !clean(session.getToken()).isEmpty();
    }

    private void redirectLogin() {
        Intent intent = new Intent(this, LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }

    private View buildScreen() {
        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(Color.parseColor("#F6F9FE"));

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        page.addView(shell, new FrameLayout.LayoutParams(-1, -1));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(14), dp(14), dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        content.addView(buildHeader());
        content.addView(buildIdentityCard(), sectionLp());
        content.addView(buildDriverInfoCard(), sectionLp());
        content.addView(buildStatusCard(), sectionLp());
        content.addView(buildBpjsCard(), sectionLp());
        content.addView(buildDocumentCard(), sectionLp());
        content.addView(buildSecurityCard());

        shell.addView(
                DriverBottomNavigation.build(this, DriverBottomNavigation.ActiveItem.PROFILE),
                new LinearLayout.LayoutParams(-1, dp(66))
        );

        loading = new ProgressBar(this);
        loading.setVisibility(View.GONE);
        FrameLayout.LayoutParams loadingLp = new FrameLayout.LayoutParams(dp(46), dp(46));
        loadingLp.gravity = Gravity.CENTER;
        page.addView(loading, loadingLp);
        return page;
    }

    private View buildHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.addView(text("Akun Driver", 24, "#0B3A78", true));
        titleBox.addView(text("Profil, kendaraan, dokumen, dan status kerja", 11, "#718096", false));
        row.addView(titleBox, new LinearLayout.LayoutParams(0, -2, 1));

        TextView refresh = text("↻", 25, "#0B7CFF", true);
        refresh.setGravity(Gravity.CENTER);
        refresh.setBackground(roundStroke("#FFFFFF", "#DCE8F6", 16, 1));
        refresh.setOnClickListener(view -> loadProfile());
        row.addView(refresh, new LinearLayout.LayoutParams(dp(44), dp(44)));
        return row;
    }

    private View buildIdentityCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(18), dp(22), dp(18), dp(20));
        card.setBackground(gradient("#075EF4", "#22A4FF", 22));
        card.setElevation(dp(3));

        FrameLayout avatarFrame = new FrameLayout(this);
        GradientDrawable border = new GradientDrawable();
        border.setShape(GradientDrawable.OVAL);
        border.setColor(Color.WHITE);
        border.setStroke(dp(3), Color.WHITE);
        avatarFrame.setBackground(border);
        avatarFrame.setElevation(dp(5));

        avatarView = new ImageView(this);
        avatarView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatarView.setImageResource(drawableOrFallback("ic_nav_profile"));
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.parseColor("#EAF4FF"));
        avatarView.setBackground(mask);
        avatarView.setClipToOutline(true);
        avatarView.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        FrameLayout.LayoutParams avatarLp = new FrameLayout.LayoutParams(dp(94), dp(94));
        avatarLp.gravity = Gravity.CENTER;
        avatarFrame.addView(avatarView, avatarLp);
        card.addView(avatarFrame, new LinearLayout.LayoutParams(dp(102), dp(102)));

        nameView = text(first(session.getName(), session.getUsername(), "Driver"), 21, "#FFFFFF", true);
        nameView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(-1, -2);
        nameLp.setMargins(0, dp(12), 0, 0);
        card.addView(nameView, nameLp);

        usernameView = text("@" + first(session.getUsername(), "driver"), 11, "#EAF5FF", false);
        usernameView.setGravity(Gravity.CENTER);
        card.addView(usernameView);

        LinearLayout badges = new LinearLayout(this);
        badges.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams badgesLp = new LinearLayout.LayoutParams(-1, -2);
        badgesLp.setMargins(0, dp(12), 0, 0);
        card.addView(badges, badgesLp);

        verificationBadge = badge("Memuat status", "#FFFFFF", "#0B7CFF");
        badges.addView(verificationBadge);

        driverTypeBadge = badge("Driver", "#FFE08A", "#5C3A00");
        LinearLayout.LayoutParams typeLp = new LinearLayout.LayoutParams(-2, -2);
        typeLp.setMargins(dp(7), 0, 0, 0);
        badges.addView(driverTypeBadge, typeLp);
        return card;
    }

    private View buildDriverInfoCard() {
        LinearLayout card = whiteCard();
        card.addView(sectionTitle("Informasi Driver", "Data akun dan kendaraan utama"));
        emailValue = addInfoRow(card, "Email", "-");
        phoneValue = addInfoRow(card, "Nomor HP", "-");
        plateValue = addInfoRow(card, "Nomor Polisi", "-");
        statusValue = addInfoRow(card, "Status Verifikasi", "-");
        verifiedAtValue = addInfoRow(card, "Terverifikasi Sejak", "-");
        balanceValue = addInfoRow(card, "Saldo Driver", "Rp0");
        noteValue = addInfoRow(card, "Catatan Verifikasi", "-");
        return card;
    }

    private View buildStatusCard() {
        LinearLayout card = whiteCard();
        card.addView(sectionTitle("Status Kerja", "Informasi operasional driver saat ini"));
        onlineValue = addInfoRow(card, "Status", "Offline");
        busyValue = addInfoRow(card, "Ketersediaan", "Tersedia");
        onlineSinceValue = addInfoRow(card, "Online Sejak", "-");
        lastOrderValue = addInfoRow(card, "Order Terakhir", "-");
        locationValue = addInfoRow(card, "Lokasi Terakhir", "-");
        accuracyValue = addInfoRow(card, "Akurasi Lokasi", "-");
        speedValue = addInfoRow(card, "Kecepatan", "-");
        return card;
    }

    private View buildBpjsCard() {
        LinearLayout card = whiteCard();
        card.setClickable(true);
        card.setFocusable(true);
        card.addView(sectionTitle("BPJS Ketenagakerjaan", "Perlindungan kepesertaan driver Transiva"));
        bpjsStatusValue = addInfoRow(card, "Status Kepesertaan", "Tidak Aktif");
        bpjsSummaryValue = addInfoRow(card, "Nomor BPJS", "Belum diisi");

        TextView open = text("Lihat kartu & detail BPJS  ›", 11, "#0B7CFF", true);
        open.setPadding(0, dp(13), 0, 0);
        card.addView(open);

        card.setOnClickListener(view -> {
            Intent intent = new Intent(this, DriverBpjsActivity.class);
            if (latestProfile != null) {
                intent.putExtra("bpjs_profile_json", latestProfile.toString());
            }
            startActivity(intent);
        });
        return card;
    }

    private View buildDocumentCard() {
        LinearLayout card = whiteCard();
        card.addView(sectionTitle("Kendaraan & Dokumen", "Preview dokumen yang tersimpan di server"));

        LinearLayout images = new LinearLayout(this);
        images.setOrientation(LinearLayout.HORIZONTAL);
        ktpView = documentImage("Foto KTP");
        images.addView(documentBox(ktpView, "KTP"), documentLp(false));
        vehicleView = documentImage("Foto Kendaraan");
        images.addView(documentBox(vehicleView, "Kendaraan"), documentLp(true));
        card.addView(images);

        TextView hint = text("Ketuk foto untuk melihat ukuran penuh.", 9, "#718096", false);
        hint.setPadding(0, dp(10), 0, 0);
        card.addView(hint);
        return card;
    }

    private View buildSecurityCard() {
        LinearLayout card = whiteCard();
        card.addView(sectionTitle("Keamanan", "Kelola sesi aplikasi driver"));
        Button logout = dangerButton("Keluar dari Akun");
        logout.setOnClickListener(view -> confirmLogout());
        card.addView(logout, new LinearLayout.LayoutParams(-1, dp(50)));
        return card;
    }

    private void loadProfile() {
        if (loadingData || api == null) return;
        setLoading(true);

        api.executor().execute(() -> {
            try {
                DriverApiClient.Result result = api.get(PROFILE_ENDPOINT + System.currentTimeMillis());
                JSONObject profile = result.body.optJSONObject("profile");
                if (profile == null) throw new IllegalStateException("Data profil kosong.");
                session.updateDriverRuntime(profile);
                runOnUiThread(() -> {
                    bindProfile(profile);
                    setLoading(false);
                });
            } catch (DriverApiClient.ApiException error) {
                runOnUiThread(() -> {
                    setLoading(false);
                    showInfo(error.status == 401 ? "Sesi Berakhir" : "Profil Gagal Dimuat",
                            error.status == 401 ? "Silakan login kembali." : first(error.getMessage(), "Tidak dapat mengambil profil driver."));
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setLoading(false);
                    showInfo("Profil Gagal Dimuat", first(error.getMessage(), "Data profil tidak valid."));
                });
            }
        });
    }

    private void bindProfile(JSONObject profile) {
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

    private void loadImage(ImageView target, String url, int fallback) {
        RemoteImageLoader.loadCenterCrop(target, absoluteUrl(url), fallback);
    }

    private void bindImageOpen(ImageView target, String rawUrl) {
        String url = absoluteUrl(rawUrl);
        target.setOnClickListener(url.isEmpty() ? null : view -> {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(android.net.Uri.parse(url));
            startActivity(intent);
        });
    }

    private String absoluteUrl(String value) {
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

    private boolean readFlag(JSONObject object, String... keys) {
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

    private String normalizeDriverType(String value) {
        return "car".equals(clean(value).toLowerCase(Locale.ROOT)) ? "Car" : "Bike";
    }

    private String normalizeVerification(String value) {
        String clean = clean(value).toLowerCase(Locale.ROOT);
        if ("verified".equals(clean) || "rejected".equals(clean) || "suspended".equals(clean)) return clean;
        return "pending";
    }

    private String verificationLabel(String status) {
        if ("verified".equals(status)) return "✓ Terverifikasi";
        if ("rejected".equals(status)) return "Ditolak";
        if ("suspended".equals(status)) return "Ditangguhkan";
        return "Menunggu Verifikasi";
    }

    private String verificationBackground(String status) {
        if ("verified".equals(status)) return "#E7FFF2";
        if ("rejected".equals(status)) return "#FFECEC";
        if ("suspended".equals(status)) return "#FFF0E5";
        return "#FFF7E6";
    }

    private String verificationTextColor(String status) {
        if ("verified".equals(status)) return "#0A8F4C";
        if ("rejected".equals(status)) return "#C62828";
        if ("suspended".equals(status)) return "#B45309";
        return "#C96A05";
    }

    private String formatDate(String value) {
        String clean = clean(value);
        return clean.isEmpty() ? "-" : clean.replace("T", " ");
    }

    private void confirmLogout() {
        new AlertDialog.Builder(this)
                .setTitle("Keluar Akun")
                .setMessage("Yakin ingin keluar dari akun driver Transiva?")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Keluar", (dialog, which) -> {
                    DriverServiceController.stop(this);
                    try { session.logout(); } catch (Exception ignored) {}
                    redirectLogin();
                })
                .show();
    }

    private LinearLayout whiteCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(15), dp(15), dp(15), dp(15));
        card.setBackground(roundStroke("#FFFFFF", "#E1EAF5", 18, 1));
        card.setElevation(dp(1));
        return card;
    }

    private View sectionTitle(String title, String subtitle) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(text(title, 16, "#0B3A78", true));
        box.addView(text(subtitle, 10, "#718096", false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(9));
        box.setLayoutParams(lp);
        return box;
    }

    private TextView addInfoRow(LinearLayout parent, String label, String value) {
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

    private ImageView documentImage(String description) {
        ImageView image = new ImageView(this);
        image.setContentDescription(description);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImageResource(android.R.drawable.ic_menu_report_image);
        image.setBackground(round("#EEF5FD", 14));
        image.setClipToOutline(true);
        image.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        return image;
    }

    private View documentBox(ImageView image, String label) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(image, new LinearLayout.LayoutParams(-1, dp(116)));
        TextView caption = text(label, 10, "#0B3A78", true);
        caption.setGravity(Gravity.CENTER);
        caption.setPadding(0, dp(6), 0, 0);
        box.addView(caption);
        return box;
    }

    private LinearLayout.LayoutParams documentLp(boolean margin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        if (margin) lp.setMargins(dp(9), 0, 0, 0);
        return lp;
    }

    private TextView badge(String value, String background, String color) {
        TextView badge = text(value, 9, color, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(9), dp(5), dp(9), dp(5));
        badge.setBackground(round(background, 14));
        return badge;
    }

    private Button dangerButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(Color.WHITE);
        button.setBackground(gradient("#EF4444", "#DC2626", 14));
        return button;
    }

    private LinearLayout.LayoutParams sectionLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(14), 0, 0);
        return lp;
    }

    private TextView text(String value, int size, String color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value == null ? "" : value);
        view.setTextSize(size);
        view.setTextColor(Color.parseColor(color));
        view.setIncludeFontPadding(false);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable round(String fill, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.parseColor(fill));
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private GradientDrawable roundStroke(String fill, String stroke, int radius, int width) {
        GradientDrawable drawable = round(fill, radius);
        drawable.setStroke(dp(width), Color.parseColor(stroke));
        return drawable;
    }

    private GradientDrawable gradient(String start, String end, int radius) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.parseColor(start), Color.parseColor(end)}
        );
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private int drawableOrFallback(String name) {
        int id = getResources().getIdentifier(name, "drawable", getPackageName());
        return id != 0 ? id : android.R.drawable.sym_def_app_icon;
    }

    private String rupiah(double amount) {
        NumberFormat format = NumberFormat.getCurrencyInstance(new Locale("id", "ID"));
        format.setMaximumFractionDigits(0);
        format.setMinimumFractionDigits(0);
        return format.format(amount);
    }

    private String first(String... values) {
        if (values == null) return "";
        for (String value : values) {
            String clean = clean(value);
            if (!clean.isEmpty()) return clean;
        }
        return "";
    }

    private String clean(String value) {
        if (value == null) return "";
        value = value.trim();
        if (value.isEmpty() || "null".equalsIgnoreCase(value) || "undefined".equalsIgnoreCase(value)) return "";
        return value;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void setLoading(boolean value) {
        loadingData = value;
        if (loading != null) loading.setVisibility(value ? View.VISIBLE : View.GONE);
    }

    private void showInfo(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }
}
