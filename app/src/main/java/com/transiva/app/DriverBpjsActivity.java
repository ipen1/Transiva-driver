package com.transiva.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.transiva.app.driver.data.DriverApiClient;

import org.json.JSONObject;

import java.util.Locale;

public class DriverBpjsActivity extends Activity {

    private static final String PROFILE_ENDPOINT = "driver_profile_native.php?v=";

    private SessionManager session;
    private DriverApiClient api;
    private ProgressBar loading;
    private boolean loadingData;

    private TextView statusBadge;
    private TextView nikValue;
    private TextView numberValue;
    private TextView nameValue;
    private TextView registeredValue;

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

        String cached = getIntent().getStringExtra("bpjs_profile_json");
        if (cached != null && !cached.trim().isEmpty()) {
            try {
                bindProfile(new JSONObject(cached));
            } catch (Exception ignored) {}
        }
        loadProfile();
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

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        page.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(14), dp(14), dp(28));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        content.addView(buildHeader());
        content.addView(buildBpjsCard(), bpjsCardLp());
        content.addView(buildBenefitsCard(), sectionLp());

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

        TextView back = text("‹", 34, "#0B7CFF", true);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(view -> finish());
        row.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("BPJS Ketenagakerjaan", 22, "#0B3A78", true));
        titles.addView(text("Informasi perlindungan kepesertaan driver", 10, "#718096", false));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1);
        titleLp.setMargins(dp(6), 0, 0, 0);
        row.addView(titles, titleLp);
        return row;
    }

    private View buildBpjsCard() {
        FrameLayout card = new FrameLayout(this);
        card.setClipToOutline(true);
        card.setElevation(dp(4));
        card.setBackground(round("#FFFFFF", 22));

        ImageView background = new ImageView(this);
        background.setImageResource(R.drawable.bg_bpjs_card);
        // Background sudah memiliki rasio kartu. FIT_XY aman karena tinggi kartu
        // dihitung dengan rasio gambar yang sama, sehingga gambar tidak terpotong.
        background.setScaleType(ImageView.ScaleType.FIT_XY);
        card.addView(background, new FrameLayout.LayoutParams(-1, -1));

        // Overlay tipis agar data tetap terbaca di area hijau kartu.
        View shade = new View(this);
        GradientDrawable shadeDrawable = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x26000000, 0x09000000, 0x00000000}
        );
        shade.setBackground(shadeDrawable);
        card.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout data = new LinearLayout(this);
        data.setOrientation(LinearLayout.VERTICAL);
        // Sisakan area judul KARTU PESERTA di kiri atas background.
        data.setPadding(dp(22), dp(38), dp(18), dp(10));

        nikValue = addCardValueOnly(data, "Belum diisi", 15);
        numberValue = addCardField(data, "NO. BPJS", "Belum diisi", 17);
        nameValue = addCardValueOnly(data, "Belum diisi", 16);
        registeredValue = addCardField(data, "BPJS AKTIF SEJAK", "Belum diisi", 14);

        statusBadge = text("TIDAK AKTIF", 10, "#C62828", true);
        statusBadge.setPadding(dp(10), dp(5), dp(10), dp(5));
        statusBadge.setBackground(round("#FDECEC", 14));
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-2, -2);
        statusLp.setMargins(0, dp(3), 0, 0);
        data.addView(statusBadge, statusLp);

        FrameLayout.LayoutParams dataLp = new FrameLayout.LayoutParams(-1, -2);
        dataLp.gravity = Gravity.TOP;
        card.addView(data, dataLp);

        // Kunci rasio kartu agar FrameLayout wrap_content tidak mengikuti intrinsic
        // height PNG dan berubah menjadi kartu vertikal yang sangat panjang.
        // Background 1440 x 879 -> rasio tinggi/lebar = 879/1440.
        return card;
    }

    private TextView addCardField(LinearLayout parent, String label, String value, int valueSize) {
        TextView labelView = text(label, 9, "#123C32", true);
        parent.addView(labelView);

        TextView valueView = text(value, valueSize, "#071C17", true);
        valueView.setMaxLines(1);
        valueView.setPadding(0, dp(1), 0, dp(6));
        parent.addView(valueView, new LinearLayout.LayoutParams(-1, -2));
        return valueView;
    }

    private TextView addCardValueOnly(LinearLayout parent, String value, int valueSize) {
        TextView valueView = text(value, valueSize, "#071C17", true);
        valueView.setMaxLines(1);
        valueView.setPadding(0, 0, 0, dp(6));
        parent.addView(valueView, new LinearLayout.LayoutParams(-1, -2));
        return valueView;
    }

    private View buildBenefitsCard() {
        LinearLayout card = whiteCard();
        card.setPadding(dp(16), dp(16), dp(16), dp(16));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView shield = text("🛡", 26, "#0A9C52", false);
        shield.setGravity(Gravity.CENTER);
        shield.setBackground(round("#EAF8F0", 16));
        titleRow.addView(shield, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setPadding(dp(10), 0, 0, 0);
        titleBox.addView(text("MANFAAT BPJS KETENAGAKERJAAN AKTIF", 15, "#12395F", true));
        titleBox.addView(text("JAMINAN KECELAKAAN KERJA (JKK)", 12, "#169C51", true));
        titleBox.addView(text(
                "Memberikan perlindungan bagi Anda saat bekerja sebagai driver Transiva.",
                10, "#53677D", false
        ));
        titleRow.addView(titleBox, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(titleRow);

        LinearLayout benefits = new LinearLayout(this);
        benefits.setOrientation(LinearLayout.HORIZONTAL);
        benefits.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams benefitsLp = new LinearLayout.LayoutParams(-1, -2);
        benefitsLp.setMargins(0, dp(16), 0, 0);
        card.addView(benefits, benefitsLp);

        benefits.addView(benefitItem("✚", "Perawatan", "Sesuai manfaat JKK"), benefitLp());
        benefits.addView(benefitItem("♿", "Tidak mampu bekerja", "Santunan sementara"), benefitLp());
        benefits.addView(benefitItem("♟", "Risiko cacat", "Santunan sesuai ketentuan"), benefitLp());
        benefits.addView(benefitItem("Rp", "Santunan kematian", "Untuk ahli waris"), benefitLp());

        TextView note = text(
                "Manfaat mengikuti status kepesertaan dan ketentuan resmi BPJS Ketenagakerjaan yang berlaku.",
                9, "#6B7C8F", false
        );
        note.setPadding(0, dp(14), 0, 0);
        card.addView(note);

        TextView hashtag = text(
                "#TransivaAman  •  #DriverTerlindungi  •  #TransivaPeduli",
                8, "#0B3A78", true
        );
        hashtag.setGravity(Gravity.CENTER);
        hashtag.setSingleLine(true);
        hashtag.setMaxLines(1);
        hashtag.setTextScaleX(0.92f);
        hashtag.setPadding(dp(6), dp(9), dp(6), dp(9));
        hashtag.setBackground(gradient("#EFFAF3", "#F4F9FF", 16));
        LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(-1, -2);
        tagLp.setMargins(0, dp(14), 0, 0);
        card.addView(hashtag, tagLp);

        return card;
    }

    private View benefitItem(String iconText, String title, String subtitle) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(4), 0, dp(4), 0);

        TextView icon = text(iconText, 18, "#139A50", true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(round("#EFF9F3", 18));
        box.addView(icon, new LinearLayout.LayoutParams(dp(38), dp(38)));

        TextView titleView = text(title, 9, "#15415C", true);
        titleView.setGravity(Gravity.CENTER);
        titleView.setMaxLines(2);
        titleView.setPadding(0, dp(6), 0, 0);
        box.addView(titleView, new LinearLayout.LayoutParams(-1, -2));

        TextView subView = text(subtitle, 8, "#66788A", false);
        subView.setGravity(Gravity.CENTER);
        subView.setMaxLines(2);
        subView.setPadding(0, dp(3), 0, 0);
        box.addView(subView, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    private LinearLayout.LayoutParams benefitLp() {
        return new LinearLayout.LayoutParams(0, -2, 1);
    }

    private LinearLayout.LayoutParams bpjsCardLp() {
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int availableWidth = Math.max(dp(280), screenWidth - dp(28));
        int cardHeight = Math.round(availableWidth * (879f / 1440f));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, cardHeight);
        lp.setMargins(0, dp(14), 0, 0);
        return lp;
    }

    private void loadProfile() {
        if (loadingData || api == null) return;
        setLoading(true);

        api.executor().execute(() -> {
            try {
                DriverApiClient.Result result = api.get(PROFILE_ENDPOINT + System.currentTimeMillis());
                JSONObject profile = result.body.optJSONObject("profile");
                if (profile == null) throw new IllegalStateException("Data profil kosong.");
                runOnUiThread(() -> {
                    bindProfile(profile);
                    setLoading(false);
                });
            } catch (Exception error) {
                runOnUiThread(() -> setLoading(false));
            }
        });
    }

    private void bindProfile(JSONObject profile) {
        boolean active = readFlag(profile, "bpjs_active", "bpjs_is_active");
        statusBadge.setText(active ? "AKTIF" : "TIDAK AKTIF");
        statusBadge.setTextColor(Color.parseColor(active ? "#0A8F4C" : "#C62828"));
        statusBadge.setBackground(round(active ? "#E7FFF2" : "#FFECEC", 14));

        nikValue.setText(first(
                profile.optString("nik_ktp"),
                profile.optString("nik"),
                profile.optString("ktp_number"),
                "Belum diisi"
        ));
        numberValue.setText(first(profile.optString("bpjs_number"), profile.optString("bpjs_no"), "Belum diisi"));
        nameValue.setText(first(profile.optString("bpjs_name"), profile.optString("name"), "Belum diisi"));
        registeredValue.setText(first(profile.optString("bpjs_registered_since"), "Belum diisi"));
    }

    private void setLoading(boolean value) {
        loadingData = value;
        if (loading != null) loading.setVisibility(value ? View.VISIBLE : View.GONE);
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
        box.addView(text(title, 15, "#0B3A78", true));
        box.addView(text(subtitle, 10, "#718096", false));
        box.setPadding(0, 0, 0, dp(8));
        return box;
    }

    private TextView addInfoRow(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        row.addView(text(label, 10, "#718096", false));
        TextView result = text(value, 13, "#183B66", true);
        result.setPadding(0, dp(3), 0, 0);
        row.addView(result);
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return result;
    }

    private LinearLayout.LayoutParams sectionLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(14), 0, 0);
        return lp;
    }

    private TextView text(String value, int size, String color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
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

    private boolean readFlag(JSONObject object, String... keys) {
        if (object == null || keys == null) return false;
        for (String key : keys) {
            if (!object.has(key) || object.isNull(key)) continue;
            Object value = object.opt(key);
            if (value instanceof Boolean) return (Boolean) value;
            if (value instanceof Number) return ((Number) value).intValue() == 1;
            String text = clean(String.valueOf(value)).toLowerCase(Locale.ROOT);
            if ("1".equals(text) || "true".equals(text) || "active".equals(text)
                    || "aktif".equals(text) || "yes".equals(text)) return true;
        }
        return false;
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
        return value == null ? "" : value.trim();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
