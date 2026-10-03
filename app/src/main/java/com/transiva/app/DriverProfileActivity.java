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
public class DriverProfileActivity extends DriverProfileActivityLayer1 {
    private LinearLayout performanceOptions, accountDetails, locationDetails, documentDetails;
    private ScrollView profileScroll;
    private boolean hasProfile;
    private boolean expandedPerformance, expandedAccount, expandedLocation, expandedDocuments;

    private void toggle(LinearLayout target, boolean show) { if(target!=null) target.setVisibility(show?View.VISIBLE:View.GONE); }
    private TextView expandLink(String label, LinearLayout target, int which) {
        TextView link=text(label+"  ▾",12,"#0B7CFF",true);
        link.setPadding(0,dp(9),0,dp(3));
        link.setOnClickListener(v->{
            boolean open=target.getVisibility()!=View.VISIBLE;
            toggle(target,open);link.setText(label+(open?"  ▴":"  ▾"));
            if(which==0)expandedPerformance=open;
            if(which==1)expandedAccount=open;
            if(which==2)expandedLocation=open;
            if(which==3)expandedDocuments=open;
            getPreferences(MODE_PRIVATE).edit().putBoolean("expanded_"+which,open).apply();
        });
        boolean initial=getPreferences(MODE_PRIVATE).getBoolean("expanded_"+which,false);
        toggle(target,initial);link.setText(label+(initial?"  ▴":"  ▾"));
        return link;
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(DriverThemeTokens.color(this, "#0B7CFF"));
        getWindow().setNavigationBarColor(DriverThemeTokens.color(this, "#071426"));

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
        // The Profile/Akun page can remain in the back stack while the user changes
        // theme from DriverSettingsActivity. Re-apply here so DriverAppSettings can
        // detect the preference change and recreate this page immediately.
        DriverAppSettings.apply(this);
        if (api != null && !loadingData) loadProfile();
    }

    @Override
    protected void onDestroy() {
        if (api != null) api.shutdown();
        super.onDestroy();
    }

    protected boolean validDriverSession() {
        return session != null
                && session.isLoggedIn()
                && "driver".equals(session.normalizeRole(session.getRole()))
                && !clean(session.getToken()).isEmpty();
    }

    protected void redirectLogin() {
        Intent intent = new Intent(this, LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }

    protected View buildScreen() {
        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(DriverThemeTokens.color(this, "#F6F9FE"));

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        page.addView(shell, new FrameLayout.LayoutParams(-1, -1));

        ScrollView scroll = new ScrollView(this); profileScroll=scroll;
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(10), dp(14), dp(18));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        content.addView(buildHeader());
        content.addView(buildIdentityCard(), sectionLp());
        content.addView(buildPerformanceCard(), sectionLp());
        content.addView(buildRatingCard(), sectionLp());
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

    protected View buildHeader() {
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

    protected View buildIdentityCard() {
        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14),dp(13),dp(14),dp(13));
        card.setBackground(gradient("#075EF4","#22A4FF",20));
        FrameLayout frame=new FrameLayout(this);
        frame.setBackground(roundStroke("#FFFFFF","#FFFFFF",40,2));
        avatarView=new ImageView(this);avatarView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatarView.setImageResource(drawableOrFallback("ic_nav_profile"));
        avatarView.setBackground(round("#EAF4FF",40));
        avatarView.setClipToOutline(true);
        frame.addView(avatarView,new FrameLayout.LayoutParams(-1,-1));
        card.addView(frame,new LinearLayout.LayoutParams(dp(70),dp(70)));
        LinearLayout info=new LinearLayout(this);info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ilp=new LinearLayout.LayoutParams(0,-2,1);ilp.leftMargin=dp(12);
        card.addView(info,ilp);
        nameView=text(first(session.getName(),session.getUsername(),"Driver"),19,"#FFFFFF",true);
        nameView.setSingleLine(false);info.addView(nameView);
        usernameView=text("@"+first(session.getUsername(),"driver"),11,"#EAF5FF",false);
        info.addView(usernameView);
        LinearLayout badges=new LinearLayout(this);badges.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp=new LinearLayout.LayoutParams(-1,-2);blp.topMargin=dp(6);
        info.addView(badges,blp);
        verificationBadge=badge("Memuat","#FFFFFF","#0B7CFF");badges.addView(verificationBadge);
        driverTypeBadge=badge("Driver","#FFE08A","#5C3A00");
        LinearLayout.LayoutParams tlp=new LinearLayout.LayoutParams(-2,-2);tlp.leftMargin=dp(5);
        badges.addView(driverTypeBadge,tlp);
        return card;
    }

    protected View buildPerformanceCard() {
        LinearLayout card = whiteCard();
        card.addView(sectionTitle("Performa Aplikasi", "Mode aktif dan rekomendasi perangkat"));

        performanceModeValue = text("Mendeteksi perangkat…", 14, "#0B3A78", true);
        card.addView(performanceModeValue);
        performanceRecommendedValue = text("", 10, "#64748B", false);
        LinearLayout.LayoutParams recLp = new LinearLayout.LayoutParams(-1, -2);
        recLp.setMargins(0, dp(4), 0, dp(11));
        card.addView(performanceRecommendedValue, recLp);

        performanceOptions=new LinearLayout(this);
        performanceOptions.setOrientation(LinearLayout.VERTICAL);
        card.addView(expandLink("Ubah pengaturan",performanceOptions,0));
        card.addView(performanceOptions);
        LinearLayout row1 = new LinearLayout(this);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        performanceAutoButton = performanceButton("Auto");
        performanceLowButton = performanceButton("Low · Hemat baterai");
        row1.addView(performanceAutoButton, new LinearLayout.LayoutParams(0, dp(46), 1));
        LinearLayout.LayoutParams lowLp = new LinearLayout.LayoutParams(0, dp(46), 1);
        lowLp.setMargins(dp(8), 0, 0, 0);
        row1.addView(performanceLowButton, lowLp);
        performanceOptions.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        performanceNormalButton = performanceButton("Normal");
        performanceHighButton = performanceButton("High");
        row2.addView(performanceNormalButton, new LinearLayout.LayoutParams(0, dp(46), 1));
        LinearLayout.LayoutParams highLp = new LinearLayout.LayoutParams(0, dp(46), 1);
        highLp.setMargins(dp(8), 0, 0, 0);
        row2.addView(performanceHighButton, highLp);
        LinearLayout.LayoutParams row2Lp = new LinearLayout.LayoutParams(-1, -2);
        row2Lp.setMargins(0, dp(8), 0, 0);
        performanceOptions.addView(row2, row2Lp);

        performanceAutoButton.setOnClickListener(v -> selectPerformanceMode(DevicePerformanceProfile.UserMode.AUTO));
        performanceLowButton.setOnClickListener(v -> selectPerformanceMode(DevicePerformanceProfile.UserMode.LOW));
        performanceNormalButton.setOnClickListener(v -> selectPerformanceMode(DevicePerformanceProfile.UserMode.NORMAL));
        performanceHighButton.setOnClickListener(v -> selectPerformanceMode(DevicePerformanceProfile.UserMode.HIGH));
        updatePerformanceCard();
        return card;
    }

    protected Button performanceButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(10);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setPadding(dp(5), 0, dp(5), 0);
        return b;
    }

    protected void selectPerformanceMode(DevicePerformanceProfile.UserMode mode) {
        if (mode != DevicePerformanceProfile.UserMode.AUTO
                && DevicePerformanceProfile.isAboveRecommended(this, mode)) {
            DevicePerformanceProfile.UserMode recommended = DevicePerformanceProfile.getRecommendedMode(this);
            PremiumDialogs.builder(this)
                    .setTitle("Mode di atas rekomendasi perangkat")
                    .setMessage("Perangkat ini direkomendasikan menggunakan "
                            + DevicePerformanceProfile.title(recommended)
                            + ". Memakai " + DevicePerformanceProfile.title(mode)
                            + " dapat membuat perangkat lebih panas, baterai lebih boros, dan aplikasi kurang stabil saat navigasi lama. Tetap gunakan mode ini?")
                    .setNegativeButton("Batal", null)
                    .setPositiveButton("Tetap gunakan", (d, w) -> applyPerformanceMode(mode))
                    .show();
            return;
        }
        applyPerformanceMode(mode);
    }

    protected void applyPerformanceMode(DevicePerformanceProfile.UserMode mode) {
        DevicePerformanceProfile.setSelectedMode(this, mode);
        RemoteImageLoader.onPerformanceModeChanged();
        updatePerformanceCard();
    }

    protected void updatePerformanceCard() {
        if (performanceModeValue == null) return;
        DevicePerformanceProfile.UserMode selected = DevicePerformanceProfile.getSelectedMode(this);
        DevicePerformanceProfile.UserMode recommended = DevicePerformanceProfile.getRecommendedMode(this);
        DevicePerformanceProfile effective = DevicePerformanceProfile.get(this);
        String activeLabel = selected == DevicePerformanceProfile.UserMode.AUTO
                ? "Auto → " + DevicePerformanceProfile.title(recommended)
                : DevicePerformanceProfile.title(selected);
        performanceModeValue.setText("Aktif: " + activeLabel);
        performanceRecommendedValue.setText("Rekomendasi perangkat: " + DevicePerformanceProfile.title(recommended)
                + " · target " + effective.targetFps + " FPS · GPS " + effective.navigationGpsMs + " ms");
        stylePerformanceButton(performanceAutoButton, selected == DevicePerformanceProfile.UserMode.AUTO);
        stylePerformanceButton(performanceLowButton, selected == DevicePerformanceProfile.UserMode.LOW);
        stylePerformanceButton(performanceNormalButton, selected == DevicePerformanceProfile.UserMode.NORMAL);
        stylePerformanceButton(performanceHighButton, selected == DevicePerformanceProfile.UserMode.HIGH);
    }

    protected void stylePerformanceButton(Button button, boolean active) {
        if (button == null) return;
        button.setTextColor(active ? DriverThemeTokens.onAccent(this) : DriverThemeTokens.textPrimary(this));
        button.setBackground(active
                ? gradient("#0B7CFF", "#2EA2FF", 13)
                : roundStroke("#F8FBFF", "#D7E6F8", 13, 1));
    }

    protected View buildRatingCard() {
        LinearLayout card = whiteCard();
        card.addView(sectionTitle("Rating Driver", "Ulasan customer"));

        LinearLayout summary = new LinearLayout(this);
        summary.setGravity(Gravity.CENTER_VERTICAL);
        summary.setPadding(dp(4), dp(8), dp(4), dp(8));

        LinearLayout scoreBox = new LinearLayout(this);
        scoreBox.setOrientation(LinearLayout.VERTICAL);
        ratingValue = text("0.0", 25, "#0B3A78", true);
        ratingValue.setGravity(Gravity.CENTER);
        scoreBox.addView(ratingValue);
        ratingCountValue = text("Belum ada penilaian", 10, "#718096", false);
        ratingCountValue.setGravity(Gravity.CENTER);
        scoreBox.addView(ratingCountValue);
        summary.addView(scoreBox, new LinearLayout.LayoutParams(0, -2, 1));

        ratingStarsValue = text("☆☆☆☆☆", 21, "#FFB300", true);
        ratingStarsValue.setGravity(Gravity.CENTER);
        summary.addView(ratingStarsValue, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(summary);

        TextView info = text("Rating diperbarui dari review TransRide, TransCar, TransFood, dan TransSend.", 9, "#718096", false);
        info.setGravity(Gravity.CENTER);
        info.setPadding(0, dp(8), 0, 0);
        card.addView(info);
        return card;
    }

    protected View buildDriverInfoCard() {
        LinearLayout card=whiteCard();
        card.addView(sectionTitle("Informasi Driver","Identitas dan kendaraan"));
        phoneValue=addInfoRow(card,"Nomor HP","-");
        plateValue=addInfoRow(card,"Nomor Polisi","-");
        statusValue=addInfoRow(card,"Verifikasi","-");
        accountDetails=new LinearLayout(this);accountDetails.setOrientation(LinearLayout.VERTICAL);
        emailValue=addInfoRow(accountDetails,"Email","-");
        verifiedAtValue=addInfoRow(accountDetails,"Terverifikasi Sejak","-");
        balanceValue=addInfoRow(accountDetails,"Saldo Driver","Rp0");
        noteValue=addInfoRow(accountDetails,"Catatan Verifikasi","-");
        card.addView(expandLink("Detail akun",accountDetails,1));
        card.addView(accountDetails);
        return card;
    }
    protected View buildStatusCard() {
        LinearLayout card=whiteCard();
        card.addView(sectionTitle("Status & Lokasi","Informasi operasional saat ini"));
        onlineValue=addInfoRow(card,"Status","Offline");
        busyValue=addInfoRow(card,"Ketersediaan","Tersedia");
        accuracyValue=addInfoRow(card,"Akurasi GPS","-");
        locationDetails=new LinearLayout(this);locationDetails.setOrientation(LinearLayout.VERTICAL);
        onlineSinceValue=addInfoRow(locationDetails,"Online Sejak","-");
        lastOrderValue=addInfoRow(locationDetails,"Order Terakhir","-");
        locationValue=addInfoRow(locationDetails,"Koordinat","-");
        speedValue=addInfoRow(locationDetails,"Kecepatan","-");
        card.addView(expandLink("Detail lokasi",locationDetails,2));
        card.addView(locationDetails);
        return card;
    }

    protected View buildBpjsCard() {
        LinearLayout card = whiteCard();
        card.setClickable(true);
        card.setFocusable(true);
        card.addView(sectionTitle("BPJS Ketenagakerjaan", "Status kepesertaan"));
        bpjsStatusValue = addInfoRow(card, "Status Kepesertaan", "Tidak Aktif");
        bpjsSummaryValue = text("Belum diisi",10,"#718096",false);
        bpjsSummaryValue.setVisibility(View.GONE);card.addView(bpjsSummaryValue);

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

    protected View buildDocumentCard() {
        LinearLayout card=whiteCard();
        card.addView(sectionTitle("Kendaraan & Dokumen","Dokumen tersimpan di server"));
        documentDetails=new LinearLayout(this);documentDetails.setOrientation(LinearLayout.VERTICAL);
        LinearLayout images=new LinearLayout(this);images.setOrientation(LinearLayout.HORIZONTAL);
        ktpView=documentImage("Foto KTP");
        images.addView(documentBox(ktpView,"KTP"),documentLp(false));
        vehicleView=documentImage("Foto Kendaraan");
        images.addView(documentBox(vehicleView,"Kendaraan"),documentLp(true));
        documentDetails.addView(images);
        TextView hint=text("Ketuk foto untuk melihat ukuran penuh.",9,"#718096",false);
        documentDetails.addView(hint);
        card.addView(expandLink("Lihat dokumen",documentDetails,3));
        card.addView(documentDetails);
        return card;
    }

    protected View buildSecurityCard() {
        LinearLayout card = whiteCard();
        card.addView(sectionTitle("Keamanan", "Kelola sesi aplikasi driver"));
        Button logout = dangerButton("Keluar dari Akun");
        logout.setOnClickListener(view -> confirmLogout());
        card.addView(logout, new LinearLayout.LayoutParams(-1, dp(50)));
        return card;
    }

    protected void loadProfile() {
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
}
