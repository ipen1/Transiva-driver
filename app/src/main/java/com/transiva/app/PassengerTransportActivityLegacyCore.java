package com.transiva.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.Address;
import android.location.Geocoder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.ScrollView;
import android.text.Editable;
import android.text.TextWatcher;
import android.content.ClipboardManager;
import android.content.ClipData;

import com.google.android.gms.common.api.Status;
import com.google.android.gms.common.api.ApiException;
import android.util.Log;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.libraries.places.api.Places;
import com.google.android.libraries.places.api.model.Place;
import com.google.android.libraries.places.api.model.AutocompletePrediction;
import com.google.android.libraries.places.api.net.PlacesClient;
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest;
import com.google.android.libraries.places.api.net.FetchPlaceRequest;
import com.google.android.libraries.places.api.model.AutocompleteSessionToken;
import com.google.android.libraries.places.api.model.RectangularBounds;
import com.google.android.libraries.places.api.model.CircularBounds;
import com.google.android.libraries.places.api.net.SearchNearbyRequest;
import com.google.android.libraries.places.widget.Autocomplete;
import com.google.android.libraries.places.widget.AutocompleteActivity;
import com.google.android.libraries.places.widget.model.AutocompleteActivityMode;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
class PassengerTransportActivityLegacyCore extends CustomerAdaptiveActivity {



    protected boolean isCarService() { return false; }
    protected String serviceName() { return isCarService() ? "Transcar" : "TransRide"; }
    protected String offerService() { return isCarService() ? "TransCar" : "TransRide"; }
    protected String driverType() { return isCarService() ? "car" : "bike"; }
    protected String serviceTitle() { return isCarService() ? "Transcar" : "TransRide"; }
    protected String orderButtonText() { return isCarService() ? "🚘 Order Mobil" : "🏍️ Order Motor"; }
    protected String orderNoun() { return isCarService() ? "mobil" : "motor"; }

    protected final CustomerFeatureRuntimeController featureRuntime =
            new CustomerFeatureRuntimeController(CustomerRealtimeCoordinator.Role.SEARCH);
    protected CustomerGeocodingRepository geocodingRepository;

    protected static final String BASE_URL = "https://transiva.my.id/";
    protected static final String CREATE_ORDER_URL = BASE_URL + "server/createOrder.php";
    protected static final String PAYMENT_QUOTE_URL = BASE_URL + "server/ride_payment_quote.php";
    protected static final String HEMAT_STATUS_URL = BASE_URL + "server/customer_coin_status.php";
    protected static final String RESOLVE_MAPS_URL = BASE_URL + "server/resolve_google_maps.php";
    protected static final String GET_BUSINESSES_URL = BASE_URL + "server/getBusinesses.php";
    protected static final String GET_LAUNDRIES_URL = BASE_URL + "server/admin_get_laundries.php";
    protected static final String GET_ONLINE_DRIVERS_URL = BASE_URL + "server/get_map_drivers.php";
    protected static final int REQ_LOCATION = 44;
    protected static final int REQ_PLACE_AUTOCOMPLETE = 45;
    protected static final int TIMEOUT_MS = 25000;

    protected final Handler mainHandler = new Handler(Looper.getMainLooper());

    protected TransivaGoogleMapView mapView;
    protected TextView pickupText, deliveryText, modeText, fareText, paymentSummaryText, driverAvailabilityText;
    protected TextView distanceInfoText, durationInfoText, originalPriceText, finalPriceText, discountInfoText, wizardStepText, tripRouteSummaryText;
    protected Button voucherChoiceBtn, noteChoiceBtn, paymentChoiceBtn;
    protected LinearLayout bookingDetailsCard;
    protected EditText googleMapInput, noteInput, voucherInput;
    private String driverNoteDraft = "";
    private boolean orderAfterNote;
    protected Button pickupBtn, deliveryBtn, gpsBtn, orderBtn, backBtn, useLinkBtn;
    protected ProgressBar progressBar;

    protected boolean mapReady = false;
    // 2.2: preview lokasi peta terpisah dari tujuan yang sudah dikonfirmasi.
    protected volatile boolean deliverySelectionLocked = false;
    protected volatile String pendingDeliveryTitle = "";
    protected volatile String pendingDeliverySubtitle = "";
    // 2.4: guards reverse-geocode/Places callbacks so an older map position can never overwrite the latest candidate.
    protected volatile long destinationResolveGeneration = 0L;

    // 2.5 API Saver: keep Google Places traffic intentionally low.
    protected static final int PLACES_MIN_QUERY_CHARS = 3;
    protected static final long PLACES_DEBOUNCE_MS = 700L;
    protected static final long PLACES_ERROR_COOLDOWN_MS = 30L * 60L * 1000L;
    protected volatile long placesCooldownUntilMs = 0L;
    protected final java.util.LinkedHashMap<String, java.util.List<AutocompletePrediction>> placesPredictionCache = new java.util.LinkedHashMap<String, java.util.List<AutocompletePrediction>>() {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, java.util.List<AutocompletePrediction>> e) { return size() > 24; }
    };
    protected final java.util.LinkedHashMap<String, String> nearbyLandmarkCache = new java.util.LinkedHashMap<String, String>() {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, String> e) { return size() > 48; }
    };
    protected boolean ordering = false;
    protected String mode = "pickup";

    /*
     * SATU-SATUNYA PENGATURAN UKURAN MARKER JEMPUT/TUJUAN.
     *
     * Marker center Android dan marker tersimpan Leaflet memakai ukuran yang sama.
     * Cukup ubah angka MARKER_WIDTH_DP untuk memperbesar atau memperkecil semuanya.
     *
     * Rekomendasi:
     * 36 = kecil
     * 40 = sedang
     * 44 = besar
     */
    protected static final int MARKER_WIDTH_DP = 40;

    // Rasio mengikuti file ikon asli (46 x 58).
    protected static final int MARKER_HEIGHT_DP =
            Math.round(MARKER_WIDTH_DP * 58f / 46f);

    protected static final int MARKER_BOX_WIDTH_DP = MARKER_WIDTH_DP;
    protected static final int MARKER_BOX_HEIGHT_DP = MARKER_HEIGHT_DP;
    protected static final int MARKER_IMAGE_WIDTH_DP = MARKER_WIDTH_DP;
    protected static final int MARKER_IMAGE_HEIGHT_DP = MARKER_HEIGHT_DP;

    protected static final int MARKER_ANCHOR_X_PX =
            MARKER_BOX_WIDTH_DP / 2;

    protected static final int MARKER_ANCHOR_Y_PX =
            Math.max(1, MARKER_IMAGE_HEIGHT_DP - 2);
    protected String username = "";
    protected String authToken = "";
    protected String paymentMethod = "cash";
    protected String scheduledAt = "";
    protected String priceMode = "standard";
    protected int familyMemberId = 0;
    protected final RideEcosystemFeatures ecosystemFeatures = new RideEcosystemFeatures();
    protected Button waypointQuickButton, waypointBtn, groupRideBtn, safetyRideBtn, familyBtn;
    protected LinearLayout waypointHeaderRows;
    protected LinearLayout ecosystemRow;
    protected SplitBillManager splitBillManager;
    protected int lastQuotedFare = 0;
    protected boolean selectingWaypoint = false;
    protected String familyMemberName = "";
    protected boolean smartFavoriteIntent = false;
    protected int userId = 0;

    protected double pickupLat = 0, pickupLng = 0;
    protected double deliveryLat = 0, deliveryLng = 0;
    protected double centerLat = -0.018137, centerLng = 120.087380;
    protected double pickLat = 0, pickLng = 0;

    protected String pickupAddress = "Lokasi Jemput";
    protected String deliveryAddress = "Lokasi Pengantaran";
    protected boolean destroyed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        geocodingRepository = new CustomerGeocodingRepository(this);
        initializePlacesAutocomplete();
        splitBillManager = new SplitBillManager(this, (key,size,ready) -> { ecosystemFeatures.groupSize=size; ecosystemFeatures.splitFareMode=size>1?"custom":"none"; if(groupRideBtn!=null) groupRideBtn.setText(size>1 ? (ready?"✅ Split "+size:"⏳ Split "+size) : "💳 Split Pay"); });
        try {
            CustomerWindowPolicy.status(getWindow(), Color.parseColor("#071426"));
            CustomerWindowPolicy.navigation(getWindow(), Color.parseColor("#071426"));
        } catch (Exception ignored) {}

        readUser();
        familyMemberId = getIntent() == null ? 0 : getIntent().getIntExtra("family_member_id", 0);
        familyMemberName = getIntent() == null ? "" : firstNonEmpty(getIntent().getStringExtra("family_member_name"), "");
        if (getIntent() != null && getIntent().getBooleanExtra("split_pay_entry", false)) paymentMethod = "balance";
        if(savedInstanceState!=null) driverNoteDraft=savedInstanceState.getString("driver_note_draft","");
        buildLayout();
        applySmartFavoriteIntent();
        CustomerBestOffer.load(this, offerService(), offer -> featureRuntime.post(mainHandler, () -> {
            if (offer != null && voucherInput != null && voucherInput.getText().toString().trim().isEmpty()) {
                String code = offer.optString("promo_code", "").trim();
                if (!code.isEmpty()) {
                    voucherInput.setText(code);
                    if (voucherChoiceBtn != null) voucherChoiceBtn.setText("🏷 " + code);
                }
            }
        }));
        applySharedLocationIntent();
        requestLocationIfNeeded();
        if (savedInstanceState == null && getIntent() != null
                && getIntent().getBooleanExtra("focus_destination_search", false)) {
            mode = "delivery";
            featureRuntime.post(mainHandler, () -> { if (!destroyed) openDestinationAutocomplete(); });
        }
    }

    protected void readUser() {
        SharedPreferences sp = getSharedPreferences("transiva", MODE_PRIVATE);

        try {
            SessionManager session = new SessionManager(this);
            // Token dibaca terpisah dari status session agar session lama yang masih valid
            // tetap dapat dimigrasikan oleh SessionManager.getToken().
            authToken = session.getToken();
            if (session.isLoggedIn()) {
                username = firstNonEmpty(
                        session.getUsername(),
                        session.getName(),
                        sp.getString("username", ""),
                        sp.getString("user_username", ""),
                        sp.getString("player_username", "")
                );

                try {
                    userId = Integer.parseInt(firstNonEmpty(
                            session.getId(),
                            session.getUserId(),
                            String.valueOf(sp.getInt("user_id", 0)),
                            String.valueOf(sp.getInt("id", 0)),
                            "0"
                    ));
                } catch (Exception ignored) {
                    userId = 0;
                }

                return;
            }
        } catch (Exception ignored) {}

        username = firstNonEmpty(
                sp.getString("username", ""),
                sp.getString("user_username", ""),
                sp.getString("player_username", "")
        );

        userId = sp.getInt(
                "user_id",
                sp.getInt("id", 0)
        );

        if (userId <= 0) {
            try {
                userId = Integer.parseInt(firstNonEmpty(
                        sp.getString("user_id", ""),
                        sp.getString("id", ""),
                        "0"
                ));
            } catch (Exception ignored) {
                userId = 0;
            }
        }
    }

    protected void buildLayout() {
        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(Color.parseColor("#F4F8FD"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final int screenHeightDp = (int) (getResources().getDisplayMetrics().heightPixels / getResources().getDisplayMetrics().density);
        final boolean compactScreen = screenHeightDp < 700;
        final boolean shortScreen = screenHeightDp < 780;
        root.setPadding(dp(10), dp(compactScreen ? 6 : 8), dp(10), dp(compactScreen ? 6 : 8));
        page.addView(root, new FrameLayout.LayoutParams(-1, -1));

        /* =========================
         * HEADER COMPACT
         * ========================= */
        LinearLayout topCard = new LinearLayout(this);
        topCard.setOrientation(LinearLayout.VERTICAL);
        topCard.setPadding(dp(14), dp(compactScreen ? 8 : 10), dp(14), dp(compactScreen ? 8 : 10));
        topCard.setBackground(roundStroke("#FFFFFF", "#D7E6F8", dp(22), 1));
        root.addView(topCard, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        topCard.addView(titleRow, new LinearLayout.LayoutParams(-1, dp(compactScreen ? 26 : 28)));

        TextView title = text(serviceTitle(), compactScreen ? 18 : 19, "#0B3A78", true);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -1, 1));

        wizardStepText = text("● Tujuan   ›   2 Harga   ›   3 Pesan", compactScreen ? 9 : 10, "#0B7CFF", true);
        wizardStepText.setPadding(0, dp(2), 0, dp(2));
        topCard.addView(wizardStepText, new LinearLayout.LayoutParams(-1, -2));

        modeText = text("Pilih tujuan", compactScreen ? 9 : 10, "#64748B", false);
        modeText.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        titleRow.addView(modeText, new LinearLayout.LayoutParams(0, -1, 1.25f));

        Button close = smallButton("×", "#FEE2E2", "#DC2626", "#FECACA");
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(dp(30), dp(30));
        closeLp.setMargins(dp(4), 0, 0, 0);
        titleRow.addView(close, closeLp);
        backBtn = close;
        close.setOnClickListener(v -> finish());

        LinearLayout pointRow = new LinearLayout(this);
        pointRow.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams pointRowLp = new LinearLayout.LayoutParams(-1, -2);
        pointRowLp.setMargins(0, dp(compactScreen ? 4 : 5), 0, 0);
        topCard.addView(pointRow, pointRowLp);

        pickupBtn = compactPointButton("●  Jemput", "Lokasi Anda • ketuk untuk ubah   ›", "#16A34A");
        deliveryBtn = compactPointButton("●  Mau ke mana?", "Cari tujuan / tempel link Maps   ›", "#EF4444");
        pointRow.addView(pickupBtn, new LinearLayout.LayoutParams(-1, dp(compactScreen ? 52 : 56)));
        pickupGpsBadge = text("GPS • menunggu lokasi terbaru", 11, "#64748B", true);
        pickupGpsBadge.setPadding(dp(12), dp(2), dp(8), dp(3));
        pointRow.addView(pickupGpsBadge, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams deliveryLp = new LinearLayout.LayoutParams(-1, dp(compactScreen ? 46 : 50));
        deliveryLp.setMargins(0, dp(4), 0, 0);
        waypointHeaderRows = new LinearLayout(this);
        waypointHeaderRows.setOrientation(LinearLayout.VERTICAL);
        pointRow.addView(waypointHeaderRows, new LinearLayout.LayoutParams(-1, -2));
        pointRow.addView(deliveryBtn, deliveryLp);
        refreshWaypointHeader();

        pickupText = text("Penjemputan: belum dipilih", 9, "#334155", false);
        deliveryText = text("Pengantaran: belum dipilih", 9, "#334155", false);
        pickupText.setVisibility(View.GONE);
        deliveryText.setVisibility(View.GONE);

        // Input teknis tersembunyi untuk resolver link Google Maps.
        // UI publik cukup dua field di atas agar tidak membingungkan pengguna awam.
        googleMapInput = new EditText(this);
        googleMapInput.setVisibility(View.GONE);
        topCard.addView(googleMapInput, new LinearLayout.LayoutParams(1, 1));
        useLinkBtn = new Button(this);
        useLinkBtn.setVisibility(View.GONE);

        /* =========================
         * MAP - PRIORITAS RUANG UTAMA
         * ========================= */
        FrameLayout mapBox = new FrameLayout(this);
        mapBox.setBackground(roundStroke("#EAF4FF", "#AFCFF2", dp(14), 1));
        LinearLayout.LayoutParams mapLp = new LinearLayout.LayoutParams(-1, 0, 1);
        mapLp.setMargins(0, dp(compactScreen ? 4 : 5), 0, dp(compactScreen ? 4 : 5));
        root.addView(mapBox, mapLp);

        mapView = new TransivaGoogleMapView(this, TransivaGoogleMapView.Mode.PICKER);
        FrameLayout.LayoutParams webLp = new FrameLayout.LayoutParams(-1, -1);
        webLp.setMargins(dp(1), dp(1), dp(1), dp(1));
        mapBox.addView(mapView, webLp);
        mapView.initialize(null, new TransivaGoogleMapView.Listener() {
            @Override public void onReady(double lat, double lng) {
                mapReady = true;
                centerLat = lat; centerLng = lng; pickLat = lat; pickLng = lng;
                if (smartFavoriteIntent && validCoord(deliveryLat, deliveryLng) && mapView != null) {
                    mapView.setDelivery(deliveryLat, deliveryLng, deliveryAddress);
                }
                goToMyLocation();
                loadMapPlaces();
                loadOnlineDrivers();
                refreshSmartAvailability();
            }
            @Override public void onCenterChanged(double lat, double lng) {
                centerLat = lat; centerLng = lng; pickLat = lat; pickLng = lng;
            }
        });
        mapView.setGestureListener(new TransivaGoogleMapView.GestureListener() {
            @Override public void onGestureStart() {
                // Setelah tujuan dikonfirmasi, menggeser peta tidak boleh mengubah nama tujuan.
                if (selectingWaypoint || (!"pickup".equals(mode) && deliverySelectionLocked)) return;
                destinationResolveGeneration++;
                pendingDeliveryTitle = ""; pendingDeliverySubtitle = "";
                if ("pickup".equals(mode)) { if (pickupBtn != null) pickupBtn.setText("●  Jemput di mana?\nMencari lokasi…"); }
                else if (deliveryBtn != null) deliveryBtn.setText("●  Mau ke mana?\nMencari lokasi…");
            }
            @Override public void onGestureEnd() {
                if (selectingWaypoint || (!"pickup".equals(mode) && deliverySelectionLocked)) return;
                final double lat = pickLat, lng = pickLng;
                if (!validCoord(lat, lng)) return;
                final boolean resolvingPickup = "pickup".equals(mode);
                final long generation = ++destinationResolveGeneration;
                featureRuntime.newThread(() -> {
                    final String road = reverseAddress(lat, lng);
                    final String admin = buildAdministrativeFallback(lat, lng);
                    featureRuntime.post(mainHandler, () -> resolveNearestGooglePlace(lat, lng, googleName -> {
                        if (destroyed || selectingWaypoint || resolvingPickup != "pickup".equals(mode) || (!resolvingPickup && deliverySelectionLocked) || generation != destinationResolveGeneration) return;
                        String localName = findNearestPlaceName(lat, lng);
                        String landmark = cleanLandmarkName(firstNonEmpty(googleName, localName, ""));
                        String title;
                        if (!landmark.isEmpty()) title = "Dekat " + landmark;
                        else if (!admin.isEmpty()) title = "Dekat " + admin;
                        else if (!road.isEmpty()) title = compactDisplayName(road);
                        else title = String.format(Locale.US, "%.5f, %.5f", lat, lng);
                        String subtitle = compactDisplayName(road);
                        if (subtitle.equalsIgnoreCase(title) || (!landmark.isEmpty() && subtitle.toLowerCase(Locale.ROOT).contains(landmark.toLowerCase(Locale.ROOT)))) subtitle = admin;
                        if (resolvingPickup) {
                            if (pickupBtn != null) pickupBtn.setText("●  Jemput di mana?\n" + title + "?");
                            return;
                        }
                        pendingDeliveryTitle = title;
                        pendingDeliverySubtitle = subtitle;
                        if (deliveryBtn != null) deliveryBtn.setText("●  Mau ke mana?\nKe " + title + "?");
                    }));
                }).start();
            }
        });

        mapView.setCenterActionListener(() -> {
            if (selectingWaypoint) {
                addWaypointFromCenter();
                return;
            }
            if (!deliverySelectionLocked && "delivery".equals(mode) && validCoord(pickLat, pickLng)) {
                confirmPendingDeliveryFromMap();
                return;
            }
            if ("pickup".equals(mode)) { setPointFromCenter(); return; }
            if (validCoord(pickupLat, pickupLng) && validCoord(deliveryLat, deliveryLng)) handleWizardPrimaryAction();
            else setPointFromCenter();
        });

        gpsBtn = smallButton("⌖", "#FFFFFF", "#0B7CFF", "#9DCAFF");
        gpsBtn.setTextSize(18);
        FrameLayout.LayoutParams gpsMapLp = new FrameLayout.LayoutParams(dp(42), dp(42));
        gpsMapLp.gravity = Gravity.BOTTOM | Gravity.END;
        gpsMapLp.setMargins(0, 0, dp(10), dp(10));
        mapBox.addView(gpsBtn, gpsMapLp);


        /* =========================
         * DRAIV-STYLE BOTTOM CARD
         * ========================= */
        LinearLayout bottomCard = new LinearLayout(this);
        bookingDetailsCard = bottomCard;
        bottomCard.setOrientation(LinearLayout.VERTICAL);
        bottomCard.setPadding(dp(10), dp(9), dp(10), dp(10));
        bottomCard.setBackground(roundStroke("#FFFFFF", "#D7E6F8", dp(22), 1));
        LinearLayout.LayoutParams bottomLp = new LinearLayout.LayoutParams(-1, -2);
        bottomLp.setMargins(0, dp(6), 0, 0);
        root.addView(bottomCard, bottomLp);
        bottomCard.setVisibility(View.GONE);

        driverAvailabilityText = text("Tidak ada driver " + serviceName() + " yang online", 10, "#B91C1C", true);
        driverAvailabilityText.setGravity(Gravity.CENTER);
        driverAvailabilityText.setPadding(dp(8), dp(5), dp(8), dp(5));
        driverAvailabilityText.setBackground(roundStroke("#FEF2F2", "#FECACA", dp(10), 1));
        driverAvailabilityText.setVisibility(View.GONE);
        LinearLayout.LayoutParams driverAvailabilityLp = new LinearLayout.LayoutParams(-1, -2);
        driverAvailabilityLp.setMargins(0, 0, 0, dp(6));
        bottomCard.addView(driverAvailabilityText, driverAvailabilityLp);

        voucherInput = new EditText(this);
        voucherInput.setSingleLine(true);
        noteInput = new EditText(this);
        noteInput.setSingleLine(true);
        noteInput.setText(driverNoteDraft);

        // 2.3 FINAL ORDER UX: tampilkan hanya keputusan utama. Fitur lanjutan tetap tersedia
        // melalui panel Opsi perjalanan agar halaman final tidak terasa penuh.
        bottomCard.addView(text("⏱ Smart Waiting: gratis 5 menit setiap titik; berikutnya Rp500 per menit tambahan yang dimulai.",10,"#0B477D",false));
        TextView finalSectionTitle = text("Ringkasan & pembayaran", 12, "#0B3A78", true);
        finalSectionTitle.setPadding(dp(2), 0, dp(2), dp(5));
        bottomCard.addView(finalSectionTitle, new LinearLayout.LayoutParams(-1, -2));
        tripRouteSummaryText = text("Jemput → Tujuan", 9, "#64748B", false);
        tripRouteSummaryText.setSingleLine(true);
        tripRouteSummaryText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bottomCard.addView(tripRouteSummaryText, new LinearLayout.LayoutParams(-1, dp(24)));

        LinearLayout quickRow = new LinearLayout(this);
        quickRow.setOrientation(LinearLayout.HORIZONTAL);
        quickRow.setGravity(Gravity.CENTER_VERTICAL);
        bottomCard.addView(quickRow, new LinearLayout.LayoutParams(-1, dp(42)));

        paymentChoiceBtn = smallButton("balance".equals(paymentMethod) ? "💳 Pembayaran: Transiva Pay" : "💵 Pembayaran: Tunai", "#FFFFFF", "#0B3A78", "#C8D9EC");
        voucherChoiceBtn = smallButton("🏷 Voucher", "#FFFFFF", "#0B3A78", "#C8D9EC");
        paymentChoiceBtn.setTextSize(10);
        voucherChoiceBtn.setTextSize(10);
        quickRow.addView(paymentChoiceBtn, new LinearLayout.LayoutParams(0, -1, 1.2f));
        LinearLayout.LayoutParams voucherLp = new LinearLayout.LayoutParams(0, -1, 0.8f);
        voucherLp.setMargins(dp(6), 0, 0, 0);
        quickRow.addView(voucherChoiceBtn, voucherLp);

        LinearLayout scheduleRow = new LinearLayout(this);
        scheduleRow.setOrientation(LinearLayout.HORIZONTAL);
        scheduleRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams scheduleRowLp = new LinearLayout.LayoutParams(-1, dp(40));
        scheduleRowLp.setMargins(0, dp(5), 0, 0);
        bottomCard.addView(scheduleRow, scheduleRowLp);
        Button scheduleBtn = smallButton("🕒 Jadwal: Sekarang", "#FFFFFF", "#0B3A78", "#C8D9EC");
        Button optionsBtn = smallButton("＋ Opsi perjalanan", "#F8FBFF", "#0B7CFF", "#BBD8F7");
        scheduleRow.addView(scheduleBtn, new LinearLayout.LayoutParams(0,-1,1));
        LinearLayout.LayoutParams optionsLp = new LinearLayout.LayoutParams(0,-1,1);
        optionsLp.setMargins(dp(6),0,0,0);
        scheduleRow.addView(optionsBtn, optionsLp);

        noteChoiceBtn = smallButton(driverNoteDraft.isEmpty()?"📝 Catatan":"📝 Catatan tersimpan", "#FFFFFF", "#0B3A78", "#C8D9EC");
        Button hematBtn = smallButton("Hemat", "#EAF4FF", "#0B7CFF", "#9DCAFF");
        hematBtn.setTextSize(11);
        hematBtn.setMinWidth(0); hematBtn.setMinimumWidth(0);
        TierBadgeUi.applyToButton(hematBtn, TierBadgeUi.getCachedActiveTier(this), dp(18), dp(2));
        familyBtn = smallButton(familyMemberId > 0 ? "👨‍👩‍👧 " + firstNonEmpty(familyMemberName,"Family") : "👨‍👩‍👧 Family", "#FFFFFF", "#0B3A78", "#C8D9EC");
        waypointBtn = smallButton("＋ Tambahkan persinggahan (Opsional)", "#FFFFFF", "#0B3A78", "#C8D9EC");
        groupRideBtn = smallButton("💳 Split Pay", "#FFFFFF", "#0B3A78", "#C8D9EC");
        safetyRideBtn = smallButton("🛡 Guardian", "#EAF4FF", "#0B7CFF", "#9DCAFF");

        LinearLayout advancedBox = new LinearLayout(this);
        advancedBox.setOrientation(LinearLayout.VERTICAL);
        advancedBox.setPadding(0, dp(5), 0, 0);
        advancedBox.setVisibility(View.GONE);
        bottomCard.addView(advancedBox, new LinearLayout.LayoutParams(-1, -2));
        // Persinggahan is a first-class option, visible for cash and wallet alike.
        Button waypointQuickBtn = smallButton("＋ Tambahkan persinggahan (Opsional)", "#F0F7FF", "#0B7CFF", "#BBD8F7");
        LinearLayout.LayoutParams waypointQuickLp = new LinearLayout.LayoutParams(-1, dp(44));
        waypointQuickLp.setMargins(0, dp(6), 0, dp(2));
        bottomCard.addView(waypointQuickBtn, waypointQuickLp);
        waypointQuickBtn.setOnClickListener(v -> showWaypointDialog());
        waypointQuickButton = waypointQuickBtn;


        LinearLayout advancedRow1 = new LinearLayout(this); advancedRow1.setOrientation(LinearLayout.HORIZONTAL);
        advancedBox.addView(advancedRow1, new LinearLayout.LayoutParams(-1, dp(40)));
        advancedRow1.addView(noteChoiceBtn,new LinearLayout.LayoutParams(0,-1,1));
        LinearLayout.LayoutParams hLp=new LinearLayout.LayoutParams(0,-1,1); hLp.setMargins(dp(5),0,dp(5),0); advancedRow1.addView(hematBtn,hLp);
        advancedRow1.addView(familyBtn,new LinearLayout.LayoutParams(0,-1,1));

        ecosystemRow = new LinearLayout(this); ecosystemRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams ecosystemLp = new LinearLayout.LayoutParams(-1, dp(40)); ecosystemLp.setMargins(0,dp(5),0,0);
        advancedBox.addView(ecosystemRow, ecosystemLp);
        ecosystemRow.addView(waypointBtn,new LinearLayout.LayoutParams(0,-1,1));
        LinearLayout.LayoutParams grpLp=new LinearLayout.LayoutParams(0,-1,1); grpLp.setMargins(dp(5),0,dp(5),0); ecosystemRow.addView(groupRideBtn,grpLp);
        ecosystemRow.addView(safetyRideBtn,new LinearLayout.LayoutParams(0,-1,1));

        optionsBtn.setOnClickListener(v -> {
            boolean open = advancedBox.getVisibility() != View.VISIBLE;
            advancedBox.setVisibility(open ? View.VISIBLE : View.GONE);
            optionsBtn.setText(open ? "− Tutup opsi" : "＋ Opsi perjalanan");
            advancedBox.setAlpha(0f); advancedBox.animate().alpha(1f).setDuration(180L).start();
        });
        scheduleBtn.setOnClickListener(v -> showScheduleDialog(scheduleBtn));
        hematBtn.setOnClickListener(v -> {
            if ("hemat".equals(priceMode)) { priceMode = "standard"; TierBadgeUi.restoreHematButton(hematBtn, TierBadgeUi.getCachedActiveTier(this), dp(18), dp(2)); requestPaymentQuote(); }
            else checkHematAccess(hematBtn);
        });
        familyBtn.setOnClickListener(v -> startActivity(new Intent(this, TransivaFamilyActivity.class)));
        waypointBtn.setOnClickListener(v -> showWaypointDialog());
        groupRideBtn.setOnClickListener(v -> showGroupRideDialog());
        safetyRideBtn.setOnClickListener(v -> showSafetyOptionsDialog());
        voucherChoiceBtn.setOnClickListener(v -> showVoucherDialog());
        noteChoiceBtn.setOnClickListener(v -> showNoteDialog());
        paymentChoiceBtn.setOnClickListener(v -> showPaymentDialog());
        updateTransPayOnlyFeatures();

        LinearLayout estimateRow = new LinearLayout(this);
        estimateRow.setOrientation(LinearLayout.HORIZONTAL);
        estimateRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams estimateLp = new LinearLayout.LayoutParams(-1, dp(62));
        estimateLp.setMargins(dp(2), dp(6), dp(2), dp(5));
        bottomCard.addView(estimateRow, estimateLp);

        LinearLayout tripInfo = new LinearLayout(this);
        tripInfo.setOrientation(LinearLayout.VERTICAL);
        tripInfo.setGravity(Gravity.CENTER_VERTICAL);
        estimateRow.addView(tripInfo, new LinearLayout.LayoutParams(0, -1, 1));

        distanceInfoText = text("TransRide • Jarak -", 11, "#0B3A78", true);
        durationInfoText = text("Estimasi waktu -", 10, "#64748B", false);
        tripInfo.addView(distanceInfoText, new LinearLayout.LayoutParams(-1, dp(28)));
        tripInfo.addView(durationInfoText, new LinearLayout.LayoutParams(-1, dp(28)));

        LinearLayout priceBox = new LinearLayout(this);
        priceBox.setOrientation(LinearLayout.VERTICAL);
        priceBox.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        estimateRow.addView(priceBox, new LinearLayout.LayoutParams(0, -1, 1));

        originalPriceText = text("", 10, "#94A3B8", false);
        originalPriceText.setGravity(Gravity.END);
        originalPriceText.setVisibility(View.GONE);
        priceBox.addView(originalPriceText, new LinearLayout.LayoutParams(-1, dp(20)));

        finalPriceText = text("Rp -", 19, "#0B3A78", true);
        finalPriceText.setOnClickListener(v -> {
            if (!ordering && !locationQuoteReady && !quoteLoading && locationReady()) requestPaymentQuote();
        });
        finalPriceText.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        priceBox.addView(finalPriceText, new LinearLayout.LayoutParams(-1, dp(30)));

        discountInfoText = text("", 9, "#16A34A", true);
        discountInfoText.setGravity(Gravity.END);
        discountInfoText.setVisibility(View.GONE);
        priceBox.addView(discountInfoText, new LinearLayout.LayoutParams(-1, dp(16)));

        fareText = text("Tarif dihitung dari database", 8, "#64748B", false);
        fareText.setVisibility(View.GONE);
        paymentSummaryText = text("", 8, "#64748B", false);
        paymentSummaryText.setVisibility(View.GONE);

        // CTA wizard selalu berada di posisi yang sama pada bagian bawah kartu.
        orderBtn = smallButton("LANJUT — PILIH TUJUAN", "#0B7CFF", "#FFFFFF", "#0B7CFF");
        orderBtn.setTextSize(14);
        orderBtn.setVisibility(View.GONE);
        orderBtn.setEnabled(false);
        LinearLayout.LayoutParams orderLp = new LinearLayout.LayoutParams(-1, dp(50));
        orderLp.setMargins(0, dp(7), 0, 0);
        bottomCard.addView(orderBtn, orderLp);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams progressLp = new FrameLayout.LayoutParams(dp(50), dp(50));
        progressLp.gravity = Gravity.CENTER;
        page.addView(progressBar, progressLp);

        CustomerAdaptivePanels.install(root, mapBox);
        setContentView(page);
        CustomerAppSettings.apply(this);
        bindActions();
        updateModeUI();
    }



    protected void showScheduleDialog(Button target) {
        final java.util.Calendar c = java.util.Calendar.getInstance();
        new android.app.DatePickerDialog(this, (d, y, m, day) -> {
            new android.app.TimePickerDialog(this, (t, hour, minute) -> {
                java.util.Calendar chosen = java.util.Calendar.getInstance();
                chosen.set(y, m, day, hour, minute, 0);
                if (chosen.getTimeInMillis() < System.currentTimeMillis() + 15 * 60 * 1000L) {
                    toastDialog("Jadwal minimal 15 menit dari sekarang."); return;
                }
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                scheduledAt = f.format(chosen.getTime());
                java.text.SimpleDateFormat label = new java.text.SimpleDateFormat("dd/MM HH:mm", Locale.getDefault());
                target.setText("🕒 " + label.format(chosen.getTime()));
                if ("balance".equals(paymentMethod)) { paymentMethod = "cash"; if (paymentChoiceBtn != null) paymentChoiceBtn.setText("💵 Pembayaran: Tunai"); }
            }, c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), true).show();
        }, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH)).show();
    }

    protected void showVoucherDialog() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(voucherInput == null ? "" : voucherInput.getText().toString());
        input.setHint("Masukkan kode voucher");
        input.setPadding(dp(14), dp(8), dp(14), dp(8));

        new TransivaAlertDialogBuilder(this)
                .setTitle("Voucher " + serviceName())
                .setMessage("Masukkan kode voucher lalu tekan Gunakan.")
                .setView(input)
                .setNegativeButton("Hapus", (dialog, which) -> {
                    if (voucherInput != null) voucherInput.setText("");
                    voucherChoiceBtn.setText("🏷 Voucher");
                    requestPaymentQuote();
                })
                .setNeutralButton("Batal", null)
                .setPositiveButton("Gunakan", (dialog, which) -> {
                    String code = input.getText().toString().trim().toUpperCase(Locale.US);
                    if (voucherInput != null) voucherInput.setText(code);
                    voucherChoiceBtn.setText(code.isEmpty() ? "🏷 Voucher" : "🏷 " + code);
                    requestPaymentQuote();
                })
                .show();
    }

    protected void showNoteDialog() {
        final EditText input = new EditText(this);
        input.setSingleLine(false);
        input.setMinLines(3);
        input.setMaxLines(5);
        input.setText(noteInput == null ? "" : noteInput.getText().toString());
        input.setHint("Contoh: jemput di depan pagar");
        input.setPadding(dp(14), dp(8), dp(14), dp(8));

        final android.app.AlertDialog noteDialog = new TransivaAlertDialogBuilder(this)
                .setTitle("Catatan untuk driver")
                .setView(input)
                .setNegativeButton("Batal", (dialog, which) -> orderAfterNote=false)
                .setPositiveButton("Simpan", null)
                .create();
        noteDialog.setOnCancelListener(dialog -> orderAfterNote=false);
        noteDialog.show();
        noteDialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            String note=input.getText().toString().trim();
            if(orderAfterNote && note.isEmpty()) { input.setError("Isi petunjuk singkat untuk driver"); input.requestFocus(); return; }
            driverNoteDraft=note;
            if(noteInput!=null) noteInput.setText(note);
            if(noteChoiceBtn!=null) noteChoiceBtn.setText(note.isEmpty()?"📝 Catatan":"📝 Catatan tersimpan");
            boolean resumeOrder=orderAfterNote; orderAfterNote=false; noteDialog.dismiss();
            if(resumeOrder) createOrder();
        });
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putString("driver_note_draft", noteInput==null?driverNoteDraft:noteInput.getText().toString());
        super.onSaveInstanceState(outState);
    }

    protected void showWaypointDialog() {
        if (isRouteConfirmationLocked()) return;
        int count = ecosystemFeatures.waypoints.length();
        if (count >= 2) {
            new TransivaAlertDialogBuilder(this)
                    .setTitle("Tambah Tujuan")
                    .setMessage("Sudah ada 2 pemberhentian. Hapus semua stop jika ingin memilih ulang.")
                    .setNegativeButton("Hapus semua", (d,w) -> {
                        ecosystemFeatures.clearWaypoints();
                        selectingWaypoint = false;
                        if (mapView != null) {
                            mapView.clearWaypoints();
                            updateModeUI();
                        }
                        updateWaypointButton();
                        requestPaymentQuote();
                        requestVisibleOsrmRoute();
                    })
                    .setPositiveButton("Tutup", null)
                    .show();
            return;
        }

        selectingWaypoint = true;
        if (mapView != null) {
            mapView.setWaypointSelectionMode(count + 1);
            mapView.showCenterPin(true);
        }
        if (modeText != null) {
            modeText.setText("Geser peta ke Stop " + (count + 1) + ", lalu tekan TAMBAH STOP " + (count + 1));
        }
        android.widget.Toast.makeText(this, "Geser peta ke lokasi stop lalu tekan TAMBAH STOP " + (count + 1), android.widget.Toast.LENGTH_LONG).show();
    }

    protected void addWaypointFromCenter() {
        double lat = centerLat;
        double lng = centerLng;
        if (mapView != null && mapView.isReady()) {
            com.google.android.gms.maps.model.LatLng c = mapView.getCenter();
            if (c != null && validCoord(c.latitude, c.longitude)) {
                lat = c.latitude;
                lng = c.longitude;
                centerLat = lat;
                centerLng = lng;
                pickLat = lat;
                pickLng = lng;
            }
        }
        if (!validCoord(lat,lng)) { toastDialog("Posisi peta belum tersedia."); return; }

        final int sequence = ecosystemFeatures.waypoints.length() + 1;
        if (!ecosystemFeatures.addWaypoint(lat,lng,"Stop " + sequence)) {
            toastDialog("Maksimal 2 pemberhentian.");
            selectingWaypoint = false;
            updateModeUI();
            return;
        }

        selectingWaypoint = false;
        if (mapView != null) mapView.setWaypoints(ecosystemFeatures.waypoints);
        updateWaypointButton();
        updateModeUI();
        requestPaymentQuote();
        requestVisibleOsrmRoute();

        final double stopLat = lat, stopLng = lng;
        featureRuntime.newThread(() -> {
            String address = buildSmartAddress(stopLat, stopLng);
            featureRuntime.post(mainHandler, () -> {
                if (destroyed) return;
                try {
                    JSONObject wp = ecosystemFeatures.waypoints.optJSONObject(sequence - 1);
                    if (wp != null && address != null && !address.trim().isEmpty()) wp.put("address", address);
                } catch (Exception ignored) {}
                if (mapView != null) mapView.setWaypoints(ecosystemFeatures.waypoints);
                updateWaypointButton();
                requestPaymentQuote();
            });
        }, "transiva-waypoint-geocode").start();

        updateModeUI();
        showWaypointNotePopup(sequence);
    }

    protected void showWaypointNotePopup(final int sequence) {
        String existingGlobalNote = noteInput == null ? "" : noteInput.getText().toString().trim();
        if (!existingGlobalNote.isEmpty()) {
            try { JSONObject wp = ecosystemFeatures.waypoints.optJSONObject(sequence - 1); if (wp != null && wp.optString("note","").trim().isEmpty()) wp.put("note", existingGlobalNote); } catch (Exception ignored) {}
            if (mapView != null) mapView.setWaypoints(ecosystemFeatures.waypoints);
            requestPaymentQuote(); requestVisibleOsrmRoute();
            return;
        }
        final EditText input = new EditText(this);
        input.setHint("Contoh: Singgah di ATM / ambil barang / tunggu di depan");
        input.setSingleLine(false);
        input.setMinLines(2);
        input.setMaxLines(4);
        input.setPadding(dp(14), dp(10), dp(14), dp(10));
        new TransivaAlertDialogBuilder(this)
                .setTitle("Catatan Stop " + sequence)
                .setMessage("Tambahkan catatan agar driver tahu apa yang perlu dilakukan di titik ini.")
                .setView(input)
                .setNegativeButton("Lewati", (d,w) -> { requestPaymentQuote(); requestVisibleOsrmRoute(); })
                .setPositiveButton("Simpan", (d,w) -> {
                    String note = input.getText().toString().trim();
                    try {
                        JSONObject wp = ecosystemFeatures.waypoints.optJSONObject(sequence - 1);
                        if (wp != null) wp.put("note", note);
                    } catch (Exception ignored) {}
                    if (mapView != null) mapView.setWaypoints(ecosystemFeatures.waypoints);
                    requestPaymentQuote();
                    requestVisibleOsrmRoute();
                    android.widget.Toast.makeText(this, "Stop " + sequence + " berhasil ditambahkan", android.widget.Toast.LENGTH_SHORT).show();
                }).show();
    }

    protected boolean isRouteConfirmationLocked() {
        return bookingDetailsCard != null && bookingDetailsCard.getVisibility() == View.VISIBLE;
    }

    protected void refreshWaypointHeader() {
        if (waypointHeaderRows == null) return;
        waypointHeaderRows.removeAllViews();
        int count = ecosystemFeatures.waypoints.length();
        for (int i = 0; i < count; i++) {
            final int position = i;
            JSONObject wp = ecosystemFeatures.waypoints.optJSONObject(i);
            String name = count == 1 ? "Persinggahan" : (i == 0 ? "Persinggahan Pertama" : "Persinggahan Kedua");
            String address = wp == null ? "" : wp.optString("address", "");
            if (address.trim().isEmpty()) address = "Titik dipilih di peta";
            Button row = compactPointButton("●  " + name, address, "#D97706");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(50));
            lp.topMargin = dp(4);
            waypointHeaderRows.addView(row, lp);
            row.setContentDescription(name + ", " + address + ". Ketuk untuk mengubah atau menghapus.");
            row.setEnabled(!isRouteConfirmationLocked());
            row.setOnClickListener(v -> { if (isRouteConfirmationLocked()) return; new TransivaAlertDialogBuilder(this)
                    .setTitle(name)
                    .setMessage(addressForWaypoint(position))
                    .setPositiveButton("Ubah lokasi", (dialog, which) -> {
                        removeWaypointAt(position);
                        showWaypointDialog();
                    })
                    .setNegativeButton("Hapus", (dialog, which) -> removeWaypointAt(position))
                    .setNeutralButton("Batal", null).show(); });
        }
        if (count < 2 && !isRouteConfirmationLocked()) {
            Button add = smallButton("＋ Tambah persinggahan (Opsional)", "#F0F7FF", "#0B7CFF", "#BBD8F7");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(40));
            lp.topMargin = dp(4);
            waypointHeaderRows.addView(add, lp);
            add.setOnClickListener(v -> showWaypointDialog());
        }
    }
    protected String addressForWaypoint(int index) {
        JSONObject wp = ecosystemFeatures.waypoints.optJSONObject(index);
        return wp == null ? "" : wp.optString("address", "Titik persinggahan");
    }
    protected void removeWaypointAt(int index) {
        if (isRouteConfirmationLocked()) return;
        JSONArray updated = new JSONArray();
        for (int i = 0; i < ecosystemFeatures.waypoints.length(); i++)
            if (i != index) updated.put(ecosystemFeatures.waypoints.optJSONObject(i));
        ecosystemFeatures.clearWaypoints();
        for (int i = 0; i < updated.length(); i++) {
            JSONObject wp = updated.optJSONObject(i);
            if (wp == null) continue;
            if (ecosystemFeatures.addWaypoint(wp.optDouble("latitude"), wp.optDouble("longitude"), wp.optString("address"))) {
                JSONObject target = ecosystemFeatures.waypoints.optJSONObject(i);
                if (target != null) try { target.put("note", wp.optString("note")); } catch (Exception ignored) {}
            }
        }
        if (mapView != null) mapView.setWaypoints(ecosystemFeatures.waypoints);
        updateWaypointButton();
        updateModeUI();
        requestPaymentQuote();
        requestVisibleOsrmRoute();
    }
    protected void updateWaypointButton(){
        int count = ecosystemFeatures.waypoints.length();
        if (waypointBtn != null) waypointBtn.setText(count >= 2 ? "✓ Persinggahan 2/2" : "＋ Persinggahan " + count + "/2");
        if (waypointQuickButton != null) waypointQuickButton.setVisibility(View.GONE);
        refreshWaypointHeader();
    }

    protected void showGroupRideDialog() {
        if (!"balance".equals(paymentMethod)) { toastDialog("Split Pay hanya tersedia jika pembayaran Transiva Pay dipilih."); return; }
        final String[] options={"Sendiri (tanpa split)","2 orang","3 orang","4 orang"};
        new TransivaAlertDialogBuilder(this).setTitle("Split Pay").setSingleChoiceItems(options, Math.max(0, Math.min(3, ecosystemFeatures.groupSize-1)), (d,which)->{
            d.dismiss();
            if(which==0){ ecosystemFeatures.groupSize=1; ecosystemFeatures.splitFareMode="none"; if(splitBillManager!=null)splitBillManager.clear(); if(groupRideBtn!=null)groupRideBtn.setText("💳 Split Pay"); return; }
            int size=which+1; ecosystemFeatures.groupSize=size; ecosystemFeatures.splitFareMode="custom";
            if(splitBillManager!=null) splitBillManager.start(size,lastQuotedFare,"ride");
        }).setNegativeButton("Batal",null).show();
    }

    protected void updateTransPayOnlyFeatures() {
        boolean show="balance".equals(paymentMethod);
        if(familyBtn!=null) familyBtn.setVisibility(show || familyMemberId > 0 ? View.VISIBLE : View.GONE);
        if(waypointBtn!=null) waypointBtn.setVisibility(View.GONE);
        if(groupRideBtn!=null) groupRideBtn.setVisibility(show?View.VISIBLE:View.GONE);
        if(!show){
            // Payment selection must not erase the passenger selected in Transiva Family.
            ecosystemFeatures.groupSize=1; ecosystemFeatures.splitFareMode="none";
        }
    }

    protected void showSafetyOptionsDialog() {
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(8),0,dp(8),0);
        final android.widget.Switch guardian=new android.widget.Switch(this); guardian.setText("Trip Monitoring otomatis"); guardian.setChecked(ecosystemFeatures.tripMonitoring); box.addView(guardian);
        final android.widget.Switch audio=new android.widget.Switch(this); audio.setText("AudioProtect / Safety Recording"); audio.setChecked(ecosystemFeatures.audioProtect); box.addView(audio);
        TextView info=text("AudioProtect hanya aktif saat perjalanan dan file disimpan privat di aplikasi. Android akan menampilkan notifikasi saat mikrofon aktif.",11,"#64748B",false); info.setPadding(0,dp(8),0,0); box.addView(info);
        new TransivaAlertDialogBuilder(this).setTitle("Proteksi perjalanan").setView(box).setNegativeButton("Batal",null).setPositiveButton("Simpan",(d,w)->{
            ecosystemFeatures.tripMonitoring=guardian.isChecked(); ecosystemFeatures.audioProtect=audio.isChecked();
            if(audio.isChecked() && android.os.Build.VERSION.SDK_INT>=23 && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},91);
            if(safetyRideBtn!=null)safetyRideBtn.setText(ecosystemFeatures.tripMonitoring?"🛡 Guardian":"🛡 Safety");
        }).show();
    }

    protected void refreshSmartAvailability(){
        if(!validCoord(pickupLat,pickupLng))return;
        SmartAvailabilityClient.check(this,pickupLat,pickupLng,serviceName(),r->{
            if(r==null||!r.optBoolean("success",false)||driverAvailabilityText==null)return;
            String label=r.optString("label",""); int score=r.optInt("availability_score",-1); boolean low=r.optBoolean("low_availability",false);
            if(!label.isEmpty()){driverAvailabilityText.setText((low?"⚠ ":"✓ ")+label+(score>=0?" • "+score+"%":""));driverAvailabilityText.setVisibility(View.VISIBLE);}
        });
    }

    protected void checkHematAccess(Button hematBtn) {
        if (!ensureAuthTokenForOrder()) return;
        hematBtn.setEnabled(false);
        TierBadgeUi.showSpinner(hematBtn, dp(20));
        featureRuntime.newThread(() -> {
            try {
                JSONObject res = postJson(HEMAT_STATUS_URL, new JSONObject());
                featureRuntime.post(mainHandler, () -> {
                    hematBtn.setEnabled(true);
                    if (!res.optBoolean("success", false)) {
                        TierBadgeUi.restoreHematButton(hematBtn, TierBadgeUi.getCachedActiveTier(this), dp(20), dp(2));
                        toastDialog(firstNonEmpty(res.optString("message", ""), "Status Transiva Coin belum dapat diperiksa."));
                        return;
                    }
                    JSONObject coin = res.optJSONObject("coin");
                    if (coin == null) coin = res.optJSONObject("ride");
                    int balanceCoin = coin == null ? 0 : coin.optInt("balance", coin.optInt("remaining", 0));
                    int minRedeem = coin == null ? 1000 : coin.optInt("min_redeem_coins", coin.optInt("limit", 1000));
                    int value = coin == null ? 1 : coin.optInt("coin_value_rupiah", 1);
                    String tier = coin == null ? "BRONZE" : coin.optString("tier", "BRONZE");
                    boolean canRedeem = coin != null && coin.optBoolean("can_redeem", balanceCoin >= minRedeem);
                    TierBadgeUi.saveActiveTier(this, tier);
                    TierBadgeUi.restoreHematButton(hematBtn, tier, dp(20), dp(2));
                    if (!canRedeem) {
                        priceMode = "standard";
                        new TransivaAlertDialogBuilder(this)
                                .setTitle("Koin belum cukup")
                                .setMessage("Saldo kamu " + balanceCoin + " koin. Minimal " + minRedeem + " koin untuk memakai Hemat.")
                                .setPositiveButton("Mengerti", null).show();
                        return;
                    }
                    priceMode = "hemat";
                    new TransivaAlertDialogBuilder(this)
                            .setTitle("Hemat dengan Transiva Coin")
                            .setMessage("Saldo " + balanceCoin + " koin • 1 koin = Rp" + value + ". Sistem akan memakai koin otomatis sesuai batas aman yang diatur admin.")
                            .setPositiveButton("Gunakan Koin", (d, w) -> requestPaymentQuote())
                            .setNegativeButton("Batal", (d, w) -> { priceMode = "standard"; TierBadgeUi.restoreHematButton(hematBtn, tier, dp(20), dp(2)); })
                            .show();
                });
            } catch (Exception e) {
                featureRuntime.post(mainHandler, () -> { hematBtn.setEnabled(true); TierBadgeUi.restoreHematButton(hematBtn, TierBadgeUi.getCachedActiveTier(this), dp(20), dp(2)); toastDialog(TransivaUserMessage.network()); });
            }
        }).start();
    }

    protected void showPaymentDialog() {
        String[] methods = {"Tunai", "Transiva Pay"};
        int checked = paymentMethod.equals("balance") ? 1 : 0;

        new TransivaAlertDialogBuilder(this)
                .setTitle("Pilih metode pembayaran")
                .setSingleChoiceItems(methods, checked, (dialog, which) -> {
                    paymentMethod = which == 1 ? "balance" : "cash";
                    paymentChoiceBtn.setText(which == 1 ? "💳 Pembayaran: Transiva Pay" : "💵 Pembayaran: Tunai");
                    updateTransPayOnlyFeatures();
                    if (!"balance".equals(paymentMethod) && splitBillManager != null) splitBillManager.clear();
                    dialog.dismiss();
                    requestPaymentQuote();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    protected void bindActions() {
        pickupBtn.setOnClickListener(v -> { if (isRouteConfirmationLocked()) return; showGpsPickupDialog(); });
        deliveryBtn.setOnClickListener(v -> { if (isRouteConfirmationLocked()) return; mode = "delivery"; openDestinationAutocomplete(); });
        gpsBtn.setOnClickListener(v -> goToMyLocation());
        backBtn.setOnClickListener(v -> finish());
        orderBtn.setOnClickListener(v -> handleWizardPrimaryAction());
        googleMapInput.setOnClickListener(v -> openDestinationAutocomplete());
        useLinkBtn.setOnClickListener(v -> openDestinationAutocomplete());
    }

    /**
     * Smart destination search for TransRide + TransCar.
     * Places only selects a destination; Transiva's existing order, route and fare flow stays unchanged.
     */
    protected void initializePlacesAutocomplete() {
        try {
            if (Places.isInitialized()) return;
            String apiKey = getString(R.string.google_maps_key);
            if (apiKey != null && !apiKey.trim().isEmpty()) {
                Places.initializeWithNewPlacesApiEnabled(getApplicationContext(), apiKey.trim());
            }
        } catch (Exception ignored) {
            // Map picker remains available as a safe fallback.
        }
    }

    protected void openDestinationAutocomplete() {
        showSmartDestinationSearch();
    }

    /**
     * Full-screen Transiva destination finder: friendly blue/white UX, nearby Places
     * autocomplete, distance from pickup/current map center, map fallback and Maps-link paste.
     */
    protected void showSmartDestinationSearch() {
        initializePlacesAutocomplete();
        final FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Color.parseColor("#F7FAFF"));
        addContentView(overlay, new android.view.ViewGroup.LayoutParams(-1, -1));

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(14), dp(12), dp(14), dp(12));
        overlay.addView(shell, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        Button back = smallButton("‹", "#EAF4FF", "#0B3A78", "#C8D9EC");
        back.setTextSize(28);
        head.addView(back, new LinearLayout.LayoutParams(dp(46), dp(46)));
        TextView heading = text("pickup".equals(mode) ? "Lokasi jemput" : "Mau ke mana?", 20, "#0B3A78", true);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(0, -2, 1); hlp.setMargins(dp(12),0,0,0);
        head.addView(heading, hlp);
        Button map = smallButton("Peta", "#EAF4FF", "#0B7CFF", "#9DCAFF");
        head.addView(map, new LinearLayout.LayoutParams(dp(72), dp(44)));
        shell.addView(head, new LinearLayout.LayoutParams(-1, -2));

        TextView pickup = text("●  Lokasi Jemput\n" + shortAddress(firstNonEmpty(pickupAddress, "Lokasi saat ini")), 13, "#0F5132", true);
        pickup.setPadding(dp(16), dp(12), dp(16), dp(12));
        pickup.setBackground(roundStroke("#F0FDF4", "#86EFAC", dp(16), 1));
        LinearLayout.LayoutParams plp=new LinearLayout.LayoutParams(-1,-2); plp.setMargins(0,dp(10),0,dp(8)); shell.addView(pickup,plp);

        EditText search = new EditText(this);
        search.setSingleLine(true); search.setTextSize(16); search.setHint("pickup".equals(mode) ? "Ketik lokasi jemput…" : "Cari Alfamidi…");
        search.setPadding(dp(16),0,dp(16),0); search.setBackground(roundStroke("#FFFFFF", "#0B7CFF", dp(18), 2));
        shell.addView(search, new LinearLayout.LayoutParams(-1, dp(52)));

        LinearLayout tools = new LinearLayout(this); tools.setGravity(Gravity.CENTER_VERTICAL);
        Button paste = smallButton("Tempel link Maps", "#FFFFFF", "#0B3A78", "#C8D9EC");
        LinearLayout.LayoutParams tlp=new LinearLayout.LayoutParams(0,-2,1); tlp.setMargins(0,dp(10),dp(6),0); tools.addView(paste,tlp);
        Button chooseMap = smallButton("Pilih di peta", "#0B7CFF", "#FFFFFF", "#0B7CFF");
        LinearLayout.LayoutParams mlp=new LinearLayout.LayoutParams(0,-2,1); mlp.setMargins(dp(6),dp(10),0,0); tools.addView(chooseMap,mlp);
        for (Button action : new Button[]{paste, chooseMap}) {
            action.setMinHeight(dp(48)); action.setMinimumHeight(dp(48));
            action.setPadding(dp(10), dp(12), dp(10), dp(12));
            action.setGravity(Gravity.CENTER); action.setMaxLines(2);
        }
        tools.setClipChildren(false); tools.setClipToPadding(false);
        shell.addView(tools, new LinearLayout.LayoutParams(-1,-2));

        TextView label=text("Hasil terdekat",16,"#0B3A78",true); LinearLayout.LayoutParams llp=new LinearLayout.LayoutParams(-1,-2); llp.setMargins(0,dp(18),0,dp(8)); shell.addView(label,llp);
        ScrollView scroll=new ScrollView(this); LinearLayout results=new LinearLayout(this); results.setOrientation(LinearLayout.VERTICAL); scroll.addView(results,new ScrollView.LayoutParams(-1,-2)); shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView hint=text("Ketik nama tempat atau alamat. Hasil terdekat dari lokasimu akan muncul di sini.",13,"#64748B",false); hint.setPadding(dp(12),dp(18),dp(12),dp(18)); results.addView(hint);

        back.setOnClickListener(v -> ((android.view.ViewGroup)overlay.getParent()).removeView(overlay));
        View.OnClickListener mapAction=v->{ ((android.view.ViewGroup)overlay.getParent()).removeView(overlay); hideKeyboard(); updateModeUI(); };
        map.setOnClickListener(mapAction); chooseMap.setOnClickListener(mapAction);
        paste.setOnClickListener(v -> {
            try {
                ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                if(cm!=null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount()>0){
                    String link=String.valueOf(cm.getPrimaryClip().getItemAt(0).coerceToText(this)).trim();
                    if(link.contains("google.com/maps")||link.contains("maps.app.goo.gl")||link.contains("goo.gl/maps")){
                        ((android.view.ViewGroup)overlay.getParent()).removeView(overlay); googleMapInput.setText(link); useGoogleMapLinkForMode();
                    } else toastDialog("Tautan yang ditempel bukan link Google Maps.");
                } else toastDialog("Belum ada link di clipboard.");
            } catch(Exception e){ toastDialog("Link belum bisa ditempel. Coba salin lagi dari Google Maps."); }
        });

        final Handler debounce=new Handler(Looper.getMainLooper()); final Runnable[] pending=new Runnable[1];
        final AutocompleteSessionToken token=AutocompleteSessionToken.newInstance();
        search.addTextChangedListener(new TextWatcher(){ public void beforeTextChanged(CharSequence c,int st,int count,int after){} public void onTextChanged(CharSequence c,int st,int before,int count){
            if(pending[0]!=null) debounce.removeCallbacks(pending[0]); final String q=c.toString().trim();
            pending[0]=()->{ if(q.length()<PLACES_MIN_QUERY_CHARS){results.removeAllViews(); results.addView(hint); return;} loadSmartPredictions(q, token, results, overlay); }; debounce.postDelayed(pending[0],PLACES_DEBOUNCE_MS);
        } public void afterTextChanged(Editable e){} });
        if (getIntent() != null && "delivery".equals(mode)) {
            String query = getIntent().getStringExtra("destination_query");
            if (query != null && !query.trim().isEmpty()) {
                getIntent().removeExtra("destination_query");
                search.setText(query.trim());
                search.setSelection(search.length());
            }
        }
        // Animated contextual search hint, Grab-style: changes only while the field is empty.
        final String[] smartHints = "pickup".equals(mode)
                ? new String[]{"Cari lokasi jemput…", "Cari nama jalan…", "Cari gedung terdekat…"}
                : new String[]{"Cari Alfamidi…", "Cari SPBU…", "Cari rumah sakit…", "Cari alamat tujuan…"};
        final Handler hintHandler = new Handler(Looper.getMainLooper());
        final int[] hintIndex = {0};
        final Runnable hintTicker = new Runnable() {
            @Override public void run() {
                if (overlay.getParent() == null) return;
                if (search.getText().length() == 0) {
                    search.animate().alpha(0.45f).setDuration(110L).withEndAction(() -> {
                        hintIndex[0] = (hintIndex[0] + 1) % smartHints.length;
                        search.setHint(smartHints[hintIndex[0]]);
                        search.animate().alpha(1f).setDuration(150L).start();
                    }).start();
                }
                hintHandler.postDelayed(this, 1900L);
            }
        };
        hintHandler.postDelayed(hintTicker, 1900L);
        search.requestFocus(); ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
    }

    protected void loadSmartPredictions(String query, AutocompleteSessionToken token, LinearLayout results, FrameLayout overlay){
        try {
            final String normalized = query == null ? "" : query.trim().toLowerCase(new Locale("id","ID"));
            if (normalized.length() < PLACES_MIN_QUERY_CHARS) return;
            if (System.currentTimeMillis() < placesCooldownUntilMs) {
                showPlacesFriendlyFallback(results, "Pencarian tempat sedang dibatasi. Pilih tujuan melalui peta atau tempel link Google Maps.");
                return;
            }
            java.util.List<AutocompletePrediction> cached = placesPredictionCache.get(normalized);
            if (cached != null) {
                java.util.LinkedHashMap<String,AutocompletePrediction> map = new java.util.LinkedHashMap<>();
                for (AutocompletePrediction ap : cached) if (ap != null && ap.getPlaceId()!=null) map.put(ap.getPlaceId(), ap);
                renderSmartPredictions(Places.createClient(this), map, token, currentSearchOriginLat(), currentSearchOriginLng(), results, overlay);
                return;
            }
            final PlacesClient client = Places.createClient(this);
            final double olat=currentSearchOriginLat(), olng=currentSearchOriginLng();
            FindAutocompletePredictionsRequest.Builder b = FindAutocompletePredictionsRequest.builder()
                    .setQuery(query.trim()).setCountries(java.util.Arrays.asList("ID")).setSessionToken(token);
            if(validCoord(olat,olng)){
                b.setOrigin(new LatLng(olat,olng));
                double radiusKm=25d, latDelta=radiusKm/111.32d;
                double lngDelta=latDelta/Math.max(0.25d,Math.abs(Math.cos(Math.toRadians(olat))));
                b.setLocationBias(RectangularBounds.newInstance(new LatLng(olat-latDelta,olng-lngDelta),new LatLng(olat+latDelta,olng+lngDelta)));
            }
            client.findAutocompletePredictions(b.build()).addOnSuccessListener(r -> {
                java.util.List<AutocompletePrediction> list=new java.util.ArrayList<>(r.getAutocompletePredictions());
                placesPredictionCache.put(normalized,list);
                java.util.LinkedHashMap<String,AutocompletePrediction> found=new java.util.LinkedHashMap<>();
                for(AutocompletePrediction ap:list) if(ap.getPlaceId()!=null) found.put(ap.getPlaceId(),ap);
                renderSmartPredictions(client,found,token,olat,olng,results,overlay);
            }).addOnFailureListener(e -> {
                placesErrorDetail("AUTOCOMPLETE", e); // Logcat only; never expose credentials/diagnostics to customer.
                if(e instanceof ApiException) placesCooldownUntilMs=System.currentTimeMillis()+PLACES_ERROR_COOLDOWN_MS;
                showPlacesFriendlyFallback(results,"Pencarian tempat sedang tidak tersedia. Pilih di peta atau tempel link Google Maps.");
            });
        } catch(Exception e){
            placesErrorDetail("AUTOCOMPLETE_SETUP", e);
            showPlacesFriendlyFallback(results,"Pencarian tempat sedang tidak tersedia. Pilih di peta atau tempel link Google Maps.");
        }
    }

    protected double currentSearchOriginLat(){
        if("delivery".equals(mode)&&validCoord(pickupLat,pickupLng)) return pickupLat;
        if(validCoord(pickLat,pickLng)) return pickLat;
        if(validCoord(centerLat,centerLng)) return centerLat;
        return validCoord(deliveryLat,deliveryLng)?deliveryLat:0d;
    }
    protected double currentSearchOriginLng(){
        if("delivery".equals(mode)&&validCoord(pickupLat,pickupLng)) return pickupLng;
        if(validCoord(pickLat,pickLng)) return pickLng;
        if(validCoord(centerLat,centerLng)) return centerLng;
        return validCoord(deliveryLat,deliveryLng)?deliveryLng:0d;
    }
    protected void showPlacesFriendlyFallback(LinearLayout results,String message){
        if(results==null)return; results.removeAllViews();
        TextView er=text(message,14,"#64748B",false); er.setPadding(dp(12),dp(20),dp(12),dp(20)); results.addView(er);
    }

    protected void renderSmartPredictions(PlacesClient client,
                                           java.util.LinkedHashMap<String,AutocompletePrediction> found,
                                           AutocompleteSessionToken token, double olat, double olng,
                                           LinearLayout results, FrameLayout overlay) {
        results.removeAllViews();
        java.util.List<AutocompletePrediction> predictions=new java.util.ArrayList<>(found.values());
        java.util.Collections.sort(predictions,(x,y)->{
            Integer dx=x.getDistanceMeters(),dy=y.getDistanceMeters();
            if(dx==null&&dy==null)return 0; if(dx==null)return 1; if(dy==null)return -1; return Integer.compare(dx,dy);
        });

        // Jangan membuang prediction hanya karena distanceMeters kosong. Google mendokumentasikan bahwa field
        // ini memang dapat tidak hadir. Bila jarak tersedia, batasi hasil jauh di atas 50 km.
        java.util.List<AutocompletePrediction> visible=new java.util.ArrayList<>();
        for(AutocompletePrediction ap:predictions){
            Integer dm=ap.getDistanceMeters();
            if(dm==null || dm<=50000) visible.add(ap);
        }
        if(visible.isEmpty()){
            TextView empty=text("Belum menemukan tempat di sekitar lokasi ini. Geser peta ke lokasi terbaru atau ketik nama tempat lebih lengkap.",14,"#64748B",false);
            empty.setPadding(dp(12),dp(20),dp(12),dp(20)); results.addView(empty); return;
        }
        int n=Math.min(10,visible.size());
        for(int i=0;i<n;i++){
            AutocompletePrediction ap=visible.get(i);
            LinearLayout row=new LinearLayout(this); row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(14),dp(12),dp(14),dp(12)); row.setBackground(roundStroke("#FFFFFF","#E2E8F0",dp(14),1));
            String km=ap.getDistanceMeters()==null?"":String.format(new Locale("id","ID")," • %.2f km",ap.getDistanceMeters()/1000.0);
            TextView a=text("📍  "+ap.getPrimaryText(null)+km,15,"#172033",true);
            TextView d=text(ap.getSecondaryText(null).toString(),12,"#64748B",false); d.setPadding(dp(28),dp(3),0,0);
            row.addView(a); row.addView(d);
            LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2); rp.setMargins(0,0,0,dp(8)); results.addView(row,rp);
            row.setOnClickListener(v->selectSmartPrediction(client,ap,token,overlay));
        }
    }

    protected void selectSmartPrediction(PlacesClient client, AutocompletePrediction ap, AutocompleteSessionToken token, FrameLayout overlay){
        java.util.List<Place.Field> fields=java.util.Arrays.asList(Place.Field.ID,Place.Field.NAME,Place.Field.ADDRESS,Place.Field.LAT_LNG);
        client.fetchPlace(FetchPlaceRequest.builder(ap.getPlaceId(),fields).setSessionToken(token).build()).addOnSuccessListener(r->{
            Place p=r.getPlace(); LatLng pt=p.getLatLng(); if(pt==null){toastDialog("Lokasi tempat belum tersedia. Pilih tempat lain.");return;}
            String address=firstNonEmpty(p.getName(),p.getAddress(),ap.getPrimaryText(null).toString());
            if ("pickup".equals(mode)) {
                pickupLat=pt.latitude; pickupLng=pt.longitude; pickupAddress=address;
                pickupText.setText("Penjemputan: "+address); pickupBtn.setText("●  Lokasi Jemput\n"+shortAddress(address));
                if(mapView!=null){mapView.setPickup(pickupLat,pickupLng,pickupAddress);mapView.moveTo(pickupLat,pickupLng,16f);}
            } else {
                deliveryLat=pt.latitude; deliveryLng=pt.longitude; deliveryAddress=address; deliverySelectionLocked=true;
                deliveryText.setText("Pengantaran: "+address); deliveryBtn.setText("●  Mau ke mana?\n"+shortAddress(address));
                if(mapView!=null){mapView.setDelivery(deliveryLat,deliveryLng,deliveryAddress);mapView.moveTo(deliveryLat,deliveryLng,16f);}
            }
            try{((android.view.ViewGroup)overlay.getParent()).removeView(overlay);}catch(Exception ignored){} hideKeyboard(); updateModeUI(); requestPaymentQuote();
         }).addOnFailureListener(e->{ placesErrorDetail("FETCH_PLACE", e); toastDialog("Tempat belum dapat dibuka. Coba pilih hasil lain atau gunakan peta."); });
    }

    protected String placesErrorDetail(String stage, Exception e) {
        String type = e == null ? "null" : e.getClass().getName();
        String msg = (e == null || e.getMessage() == null || e.getMessage().trim().isEmpty()) ? "(tanpa pesan)" : e.getMessage().trim();
        String cause = "";
        if (e != null && e.getCause() != null) {
            cause = "\nCause: " + e.getCause().getClass().getSimpleName() + ": " + String.valueOf(e.getCause().getMessage());
        }
        int statusCode = -1;
        String statusName = "NON_API_EXCEPTION";
        if (e instanceof ApiException) {
            ApiException ae = (ApiException)e;
            statusCode = ae.getStatusCode();
            statusName = placesStatusName(statusCode);
        }
        String runtimeKey = "";
        try { runtimeKey = getString(R.string.google_maps_key).trim(); } catch (Exception ignored) {}
        String keyTail = runtimeKey.length() <= 7 ? runtimeKey : runtimeKey.substring(runtimeKey.length() - 7);
        String sha1 = runtimeSigningSha1();
        String expectedTail = "ThmOXqo";
        String keyMatch = expectedTail.equals(keyTail) ? "COCOK" : "TIDAK COCOK";
        String diag = "Places gagal [" + stage + "]" +
                "\nStatus: " + statusName + " (" + statusCode + ")" +
                "\nException: " + type +
                "\nMessage: " + msg + cause +
                "\nSDK mode: Places API (New)" +
                "\nPackage: " + getPackageName() +
                "\nAPI key runtime: ..." + keyTail +
                "\nKey Cloud (...ThmOXqo): " + keyMatch +
                "\nSHA-1 runtime: " + sha1 +
                "\nJika status 9011 + key/SHA cocok: cek Billing dan restriction Places API (New) pada project key ini.";
        Log.e("TransivaPlaces", diag, e);
        return diag;
    }

    @SuppressWarnings("deprecation")
    protected String runtimeSigningSha1() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_SIGNATURES);
            if (pi.signatures == null || pi.signatures.length == 0) return "TIDAK TERBACA";
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(pi.signatures[0].toByteArray());
            StringBuilder out = new StringBuilder();
            for (byte b : digest) {
                if (out.length() > 0) out.append(':');
                out.append(String.format(java.util.Locale.US, "%02X", b & 0xFF));
            }
            return out.toString();
        } catch (Exception e) {
            Log.e("TransivaPlaces", "Gagal membaca SHA-1 runtime", e);
            return "ERROR: " + e.getClass().getSimpleName();
        }
    }

    protected String placesStatusName(int code) {
        switch (code) {
            case 0: return "SUCCESS";
            case 7: return "NETWORK_ERROR";
            case 8: return "INTERNAL_ERROR";
            case 10: return "DEVELOPER_ERROR";
            case 13: return "ERROR";
            case 14: return "INTERRUPTED";
            case 15: return "TIMEOUT";
            case 16: return "CANCELED";
            case 17: return "API_NOT_CONNECTED";
            default: return "STATUS_" + code;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PLACE_AUTOCOMPLETE) return;

        if (resultCode == RESULT_OK && data != null) {
            try {
                Place place = Autocomplete.getPlaceFromIntent(data);
                LatLng point = place.getLatLng();
                if (point == null || !validCoord(point.latitude, point.longitude)) {
                    toastDialog("Lokasi tempat belum tersedia. Coba pilih tempat lain atau gunakan peta.");
                    return;
                }

                deliveryLat = point.latitude;
                deliveryLng = point.longitude;
                deliveryAddress = firstNonEmpty(place.getName(), place.getAddress(), "Tujuan dipilih");
                googleMapInput.setText(deliveryAddress);
                deliveryText.setText("Pengantaran: " + deliveryAddress);
                deliveryBtn.setText("●  Tujuan\n" + shortAddress(deliveryAddress));
                mode = "delivery";

                if (mapView != null) {
                    mapView.setDelivery(deliveryLat, deliveryLng, deliveryAddress);
                    mapView.moveTo(deliveryLat, deliveryLng, 17f);
                }

                hideKeyboard();
                updateModeUI();
                requestPaymentQuote();
            } catch (Exception e) {
                toastDialog("Tempat tidak dapat dibuka. Coba lagi atau pilih langsung dari peta.");
            }
        } else if (resultCode == AutocompleteActivity.RESULT_ERROR && data != null) {
            try {
                Status status = Autocomplete.getStatusFromIntent(data);
                String message = status == null ? "" : firstNonEmpty(status.getStatusMessage(), "");
                if (!message.toLowerCase(Locale.ROOT).contains("cancel")) {
                    showPlacesErrorDialog();
                }
            } catch (Exception ignored) {
                showPlacesErrorDialog();
            }
        }
    }


    /** Pesan ramah pengguna saat layanan pencarian tempat belum dapat dipakai. */
    protected void showPlacesErrorDialog() {
        try {
            new TransivaAlertDialogBuilder(this)
                    .setTitle("Cari tujuan")
                    .setMessage("Pencarian tempat belum dapat digunakan. Coba lagi, atau pilih titik tujuan langsung dari peta.")
                    .setNegativeButton("Pilih di Peta", (d, w) -> {
                        mode = "delivery";
                        updateModeUI();
                        if (mapView != null && validCoord(centerLat, centerLng)) mapView.moveTo(centerLat, centerLng, 16f);
                    })
                    .setPositiveButton("Coba Lagi", (d, w) -> openDestinationAutocomplete())
                    .show();
        } catch (Exception ignored) {
            mode = "delivery";
            updateModeUI();
        }
    }

    /** Tombol ringkasan di atas mengaktifkan pemilihan ulang titik terkait. */
    protected void handlePointButtonClick(String requestedMode) {
        mode = requestedMode;
        if ("pickup".equals(requestedMode)) {
            pickupLat = 0;
            pickupLng = 0;
            if (mapView != null) mapView.clearPickup();
        } else {
            deliveryLat = 0;
            deliveryLng = 0;
            deliverySelectionLocked = false; pendingDeliveryTitle = ""; pendingDeliverySubtitle = "";
            if (mapView != null) mapView.clearDelivery();
        }
        updateModeUI();
    }




    /** Mengunci kandidat tujuan peta. Nama/koordinat tidak lagi mengikuti gesture setelah dikonfirmasi. */
    protected void confirmPendingDeliveryFromMap() {
        final double lat = validCoord(pickLat, pickLng) ? pickLat : centerLat;
        final double lng = validCoord(pickLat, pickLng) ? pickLng : centerLng;
        if (!validCoord(lat, lng)) return;
        String title = firstNonEmpty(pendingDeliveryTitle, buildAdministrativeFallback(lat, lng), compactDisplayName(reverseAddress(lat, lng)), String.format(Locale.US, "%.5f, %.5f", lat, lng));
        String subtitle = firstNonEmpty(pendingDeliverySubtitle, "");
        deliveryLat = lat; deliveryLng = lng;
        deliveryAddress = subtitle.isEmpty() ? title : title + ", " + subtitle;
        deliverySelectionLocked = true;
        deliveryText.setText("Pengantaran: " + deliveryAddress);
        deliveryBtn.setText("●  Tujuan\n" + title + (subtitle.isEmpty() ? "" : "  •  " + subtitle));
        deliveryBtn.setScaleX(0.97f); deliveryBtn.setScaleY(0.97f); deliveryBtn.setAlpha(0.72f);
        deliveryBtn.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(260L).start();
        if (mapView != null) {
            mapView.setDelivery(deliveryLat, deliveryLng, deliveryAddress);
            mapView.showCenterPin(false);
        }
        setWizardProgress(1);
        updateModeUI();
        requestPaymentQuote();
    }

    protected void setPointFromCenter() {
        double selectedLat = validCoord(pickLat, pickLng) ? pickLat : centerLat;
        double selectedLng = validCoord(pickLat, pickLng) ? pickLng : centerLng;
        if (!validCoord(selectedLat, selectedLng)) return;

        if ("pickup".equals(mode)) {
            pickupGpsSelected = false; pickupGpsPinned = true;
            pickupLat = selectedLat;
            pickupLng = selectedLng;
            pickupAddress = "Mencari alamat jemput...";

            pickupText.setText("Penjemputan: " + pickupAddress);
            pickupBtn.setText("●  Jemput\nMencari alamat...");
            if (mapView != null) mapView.setPickup(pickupLat, pickupLng, pickupAddress);

            resolveAddressAsync(true, pickupLat, pickupLng);
            mode = "delivery";
        } else {
            deliveryLat = selectedLat;
            deliveryLng = selectedLng;
            deliveryAddress = "Mencari alamat pengantaran...";

            deliveryText.setText("Pengantaran: " + deliveryAddress);
            deliveryBtn.setText("●  Tujuan\nMencari alamat...");
            if (mapView != null) mapView.setDelivery(deliveryLat, deliveryLng, deliveryAddress);

            resolveAddressAsync(false, deliveryLat, deliveryLng);
        }

        updateModeUI();

    }

    private android.location.LocationManager liveGpsManager;
    private android.location.LocationListener liveGpsListener;
    private Location liveGpsBest;
    private boolean pickupGpsSelected = true;
    private boolean pickupGpsPinned = false;
    private long pickupAddressRequest = 0;
    private android.widget.TextView pickupGpsBadge;
    private boolean gpsRefreshActive = false;
    private long lastGpsAppliedAt = 0L;
    private final android.os.Handler gpsHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private void updateGpsBadge(Location loc) {
        if (pickupGpsBadge == null) return;
        String quality = gpsQuality(loc);
        pickupGpsBadge.setText(quality + (pickupGpsPinned ? "  •  Titik jemput tetap" : ""));
        boolean fresh = loc != null && Math.abs(System.currentTimeMillis()-loc.getTime()) <= 15000 && loc.hasAccuracy();
        int color = !fresh || loc.getAccuracy()>50 ? 0xFFCC4444 : loc.getAccuracy()>20 ? 0xFFAD7900 : 0xFF16865B;
        pickupGpsBadge.setTextColor(color);
    }
    private String gpsQuality(Location loc) {
        if (loc == null) return "GPS mencari sinyal…";
        long age = Math.max(0, System.currentTimeMillis() - loc.getTime());
        if (age > 15000) return "🔴 GPS lama • perbarui lokasi";
        if (!loc.hasAccuracy()) return "🔴 Akurasi belum diketahui";
        int m = Math.round(loc.getAccuracy());
        return (m <= 20 ? "🟢 " : m <= 50 ? "🟡 " : "🔴 ") + "Akurasi GPS ±" + m + " m";
    }
    private void showGpsPickupDialog() {
        Location loc = liveGpsBest;
        new TransivaAlertDialogBuilder(this).setTitle("Lokasi jemput & GPS")
            .setMessage(gpsQuality(loc) + "\n\n" + (loc == null ? "Menunggu lokasi terkini." :
                "Ketelitian adalah perkiraan radius GPS, bukan jaminan titik tepat."))
            .setPositiveButton("⌖ Tetapkan GPS terbaru", (d,w) -> goToMyLocation())
            .setNegativeButton("Pilih manual", (d,w) -> { pickupGpsSelected=false; mode="pickup"; openDestinationAutocomplete(); })
            .setNeutralButton("Tutup", null).show();
    }
    private void applyGpsPickup(Location loc, boolean force) {
        if (loc == null || !loc.hasAccuracy() || loc.getAccuracy() > 100f) return;
        if (Math.abs(System.currentTimeMillis()-loc.getTime()) > 15000L) return;
        if (!force && (!pickupGpsSelected || pickupGpsPinned || isRouteConfirmationLocked())) return;
        if (!force && liveGpsBest != null && lastGpsAppliedAt > 0 &&
                loc.getTime() < lastGpsAppliedAt) return;
        pickupLat=loc.getLatitude(); pickupLng=loc.getLongitude();
        lastGpsAppliedAt=loc.getTime();
        pickupAddress="Mencari alamat lokasi terbaru…";
        if (pickupBtn != null) pickupBtn.setText("●  Jemput\n"+shortAddress(pickupAddress));
        updateGpsBadge(loc);
        if (pickupText != null) pickupText.setText("Penjemputan: "+pickupAddress);
        if (mapView != null) { mapView.setPickup(pickupLat,pickupLng,pickupAddress); if (force) mapView.moveTo(pickupLat,pickupLng,17f); }
        resolveAddressAsync(true,pickupLat,pickupLng);
    }
    private void startLiveGps() {
        if (gpsRefreshActive || checkSelfPermissionCompat(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        try {
            liveGpsManager=(android.location.LocationManager)getSystemService(LOCATION_SERVICE);
            if (liveGpsManager == null) return;
            liveGpsListener=new android.location.LocationListener() {
                @Override public void onLocationChanged(Location loc) {
                    if (loc == null || loc.isFromMockProvider()) return;
                    long age=Math.abs(System.currentTimeMillis()-loc.getTime());
                    if (age>15000L || !loc.hasAccuracy()) return;
                    if (liveGpsBest==null || loc.getTime()>liveGpsBest.getTime() ||
                       (loc.getTime()>=liveGpsBest.getTime()-3000 && loc.getAccuracy()<liveGpsBest.getAccuracy()))
                        liveGpsBest=loc;
                    updateGpsBadge(liveGpsBest);
                    if (pickupGpsSelected && !pickupGpsPinned && !isRouteConfirmationLocked() &&
                        (lastGpsAppliedAt==0 || (System.currentTimeMillis()-lastGpsAppliedAt>12000 && loc.getAccuracy()<=35f)))
                        applyGpsPickup(loc,false);
                }
                @Override public void onStatusChanged(String p,int status,android.os.Bundle extras) {}
                @Override public void onProviderEnabled(String p) {}
                @Override public void onProviderDisabled(String p) {}
            };
            boolean registered=false;
            for (String provider : new String[]{android.location.LocationManager.GPS_PROVIDER,android.location.LocationManager.NETWORK_PROVIDER}) {
                if (liveGpsManager.isProviderEnabled(provider)) {
                    liveGpsManager.requestLocationUpdates(provider,2000L,0f,liveGpsListener,android.os.Looper.getMainLooper());
                    registered=true;
                }
            }
            gpsRefreshActive=registered;
        } catch (SecurityException ignored) { stopLiveGps(); }
          catch (Exception ignored) { stopLiveGps(); }
    }
    private void stopLiveGps() {
        gpsRefreshActive=false;
        if (liveGpsManager!=null && liveGpsListener!=null) {
            try {liveGpsManager.removeUpdates(liveGpsListener);} catch(Exception ignored) {}
        }
        liveGpsListener=null;
    }
    protected void goToMyLocation() {
        if (isRouteConfirmationLocked()) return;
        if (checkSelfPermissionCompat(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},REQ_LOCATION);return;
        }
        pickupGpsSelected=true;
        pickupGpsPinned=false;
        startLiveGps();
        TransivaFreshLocation.request(this,new TransivaFreshLocation.Callback() {
            @Override public void onLocation(Location loc,boolean fresh) {
                if (!fresh || loc==null || !loc.hasAccuracy() || loc.getAccuracy()>100f) {
                    toastDialog("Lokasi GPS belum cukup akurat. Tunggu sinyal membaik atau pilih titik manual.");return;
                }
                if (isFinishing() || destroyed || isRouteConfirmationLocked()) return;
                liveGpsBest=loc; applyGpsPickup(loc,true);
                pickupGpsPinned=true; updateGpsBadge(loc);
                mode="delivery";updateModeUI();
                if (mapView != null && !validCoord(deliveryLat, deliveryLng)) mapView.separateInitialDestinationPicker();
            }
            @Override public void onFailure(String msg) {toastDialog(msg);}
        });
    }

    /** Smart Favorite: tujuan dari server favorit, jemput otomatis dari GPS saat ini. */
    protected void applySmartFavoriteIntent() {
        Intent intent = getIntent();
        if (intent == null || !intent.getBooleanExtra("smart_favorite", false)) return;
        double lat = intent.getDoubleExtra("smart_destination_lat", 0d);
        double lng = intent.getDoubleExtra("smart_destination_lng", 0d);
        if (!validCoord(lat, lng)) return;
        smartFavoriteIntent = true;
        deliveryLat = lat; deliveryLng = lng;
        deliveryAddress = firstNonEmpty(intent.getStringExtra("smart_destination_address"), intent.getStringExtra("smart_destination_label"), "Tujuan favorit");
        if (deliveryText != null) deliveryText.setText("Pengantaran: " + deliveryAddress);
        if (deliveryBtn != null) deliveryBtn.setText("●  Tujuan\n" + deliveryAddress);
        mode = "delivery";
        updateModeUI();
    }

    /** Applies a WhatsApp/Google Maps location received through SharedLocationActivity. */
    protected void applySharedLocationIntent() {
        Intent intent = getIntent();
        if (intent == null) return;

        String shared = intent.getStringExtra("shared_location_uri");
        String role = intent.getStringExtra("shared_location_role");
        if (shared == null || shared.trim().isEmpty()) return;

        final boolean asPickup = "pickup".equalsIgnoreCase(role);
        final String rawLocation = shared.trim();
        setLoading(true);

        featureRuntime.newThread(() -> {
            String resolved = rawLocation;
            try {
                if (resolved.startsWith("geo:")) {
                    resolved = resolved.substring(4);
                }

                if (resolved.contains("maps.app.goo.gl") || resolved.contains("goo.gl/maps")) {
                    JSONObject response = postJson(
                            RESOLVE_MAPS_URL,
                            new JSONObject().put("url", resolved)
                    );
                    if (response.optBoolean("success", false)
                            && !response.optString("url", "").isEmpty()) {
                        resolved = response.optString("url");
                    }
                }

                final double[] coordinate = extractLatLng(resolved);
                featureRuntime.post(mainHandler, () -> {
                    setLoading(false);
                    if (coordinate == null || !validCoord(coordinate[0], coordinate[1])) {
                        toastDialog("Lokasi dari WhatsApp/Google Maps tidak bisa dibaca.");
                        return;
                    }

                    if (asPickup) {
                        pickupLat = coordinate[0];
                        pickupLng = coordinate[1];
                        pickupAddress = "Mencari alamat jemput...";
                        pickupText.setText("Penjemputan: " + pickupAddress);
                        pickupBtn.setText("●  Jemput\nMencari alamat...");
                        mode = "pickup";
                        if (mapView != null) {
                            mapView.setPickup(pickupLat, pickupLng, pickupAddress);
                            mapView.moveTo(pickupLat, pickupLng, 17f);
                        }
                        resolveAddressAsync(true, pickupLat, pickupLng);
                    } else {
                        deliveryLat = coordinate[0];
                        deliveryLng = coordinate[1];
                        deliveryAddress = "Mencari alamat pengantaran...";
                        deliveryText.setText("Pengantaran: " + deliveryAddress);
                        deliveryBtn.setText("●  Tujuan\\nMencari alamat...");
                        mode = "delivery";
                        if (mapView != null) {
                            mapView.setDelivery(deliveryLat, deliveryLng, deliveryAddress);
                            mapView.moveTo(deliveryLat, deliveryLng, 17f);
                        }
                        resolveAddressAsync(false, deliveryLat, deliveryLng);
                    }
                    updateModeUI();
                });
            } catch (Exception ignored) {
                featureRuntime.post(mainHandler, () -> {
                    setLoading(false);
                    toastDialog("Gagal membuka lokasi yang dibagikan.");
                });
            }
        }, "shared-location-resolver").start();
    }

    protected void useGoogleMapLink() {
        String link = googleMapInput.getText().toString().trim();
        if (link.length() == 0) {
            toastDialog("Masukkan link Google Maps pengantaran terlebih dahulu.");
            return;
        }

        hideKeyboard();
        setLoading(true);

        featureRuntime.newThread(() -> {
            String finalLink = link;

            try {
                if (link.contains("maps.app.goo.gl") || link.contains("goo.gl/maps")) {
                    JSONObject res = postJson(RESOLVE_MAPS_URL, new JSONObject().put("url", link));
                    if (res.optBoolean("success", false) && res.optString("url", "").length() > 0) {
                        finalLink = res.optString("url");
                    }
                }

                double[] c = extractLatLng(finalLink);

                featureRuntime.post(mainHandler, () -> {
                    setLoading(false);

                    if (c == null || !validCoord(c[0], c[1])) {
                        toastDialog("Link Google Maps tidak bisa dibaca.");
                        return;
                    }

                    if ("pickup".equals(mode)) {
                        pickupLat=c[0]; pickupLng=c[1]; pickupAddress="Mencari alamat jemput...";
                        pickupText.setText("Penjemputan: "+pickupAddress); pickupBtn.setText("●  Lokasi Jemput\nMencari alamat...");
                        if(mapView!=null){mapView.setPickup(pickupLat,pickupLng,pickupAddress);mapView.moveTo(pickupLat,pickupLng,17f);}
                        resolveAddressAsync(true,pickupLat,pickupLng);
                    } else {
                        deliveryLat=c[0]; deliveryLng=c[1]; deliveryAddress="Mencari alamat pengantaran...";
                        deliveryText.setText("Pengantaran: "+deliveryAddress); deliveryBtn.setText("●  Mau ke mana?\nMencari alamat...");
                        if(mapView!=null){mapView.setDelivery(deliveryLat,deliveryLng,deliveryAddress);mapView.moveTo(deliveryLat,deliveryLng,17f);}
                        resolveAddressAsync(false,deliveryLat,deliveryLng);
                    }
                    googleMapInput.setText("");
                    updateModeUI();
                });
            } catch (Exception e) {
                featureRuntime.post(mainHandler, () -> {
                    setLoading(false);
                    toastDialog("Gagal membaca link Google Maps.");
                });
            }
        }).start();
    }


    protected void useGoogleMapLinkForMode() {
        useGoogleMapLink();
    }

    protected double[] extractLatLng(String link) {
        try {
            String decoded = java.net.URLDecoder.decode(link, "UTF-8");
            String[] patterns = new String[]{
                    "[?&]q=(-?\\d+(?:\\.\\d+)?),\\s*(-?\\d+(?:\\.\\d+)?)",
                    "@(-?\\d+(?:\\.\\d+)?),\\s*(-?\\d+(?:\\.\\d+)?)",
                    "!3d(-?\\d+(?:\\.\\d+)?)!4d(-?\\d+(?:\\.\\d+)?)",
                    "(-?\\d+(?:\\.\\d+)?),\\s*(-?\\d+(?:\\.\\d+)?)"
            };
            for (String p : patterns) {
                Matcher m = Pattern.compile(p).matcher(decoded);
                if (m.find()) return new double[]{Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2))};
            }
        } catch (Exception ignored) {}
        return null;
    }


    protected void resolveAddressAsync(boolean isPickup, double lat, double lng) {
        final long requestId = isPickup ? ++pickupAddressRequest : ++deliveryAddressRequest;
        if(isPickup) { pickupAddressResolving=true; resolvingPickupLat=lat; resolvingPickupLng=lng; }
        locationQuoteReady=false; ++quoteVersion; updateWizardState();
        featureRuntime.newThread(() -> {
            String road = reverseAddress(lat, lng);
            String localMerchant = findNearestPlaceName(lat, lng);
            featureRuntime.post(mainHandler, () -> resolveNearestGooglePlace(lat, lng, (googleName) -> {
                if (destroyed || (isPickup && (requestId != pickupAddressRequest ||
                    Math.abs(lat-pickupLat)>0.000001 || Math.abs(lng-pickupLng)>0.000001))) return;
                if(!isPickup && (requestId!=deliveryAddressRequest || Math.abs(lat-deliveryLat)>0.000001 || Math.abs(lng-deliveryLng)>0.000001)) return;
                if(isPickup) pickupAddressResolving=false;
                String nearName = firstNonEmpty(googleName, localMerchant, "");
                String address;
                if (!nearName.isEmpty() && !road.isEmpty()) address = "Dekat " + nearName + ", " + road;
                else if (!nearName.isEmpty()) address = "Dekat " + nearName;
                else if (!road.isEmpty()) address = road;
                else address = String.format(Locale.US, "%.6f, %.6f", lat, lng);
                applyResolvedAddress(isPickup, address);
            }));
        }).start();
    }

    protected interface NearbyPlaceCallback { void onResult(String name); }

    /** Cari landmark Google terdekat agar titik hasil geser peta mudah dipahami driver. */
    protected void resolveNearestGooglePlace(double lat, double lng, NearbyPlaceCallback callback) {
        try {
            if (!Places.isInitialized() || System.currentTimeMillis() < placesCooldownUntilMs) { callback.onResult(""); return; }
            final String cacheKey=String.format(Locale.US,"%.4f,%.4f",lat,lng);
            if(nearbyLandmarkCache.containsKey(cacheKey)){ callback.onResult(nearbyLandmarkCache.get(cacheKey)); return; }
            PlacesClient client = Places.createClient(this);
            java.util.List<Place.Field> fields = java.util.Arrays.asList(Place.Field.ID, Place.Field.NAME, Place.Field.LAT_LNG);
            SearchNearbyRequest request = SearchNearbyRequest.builder(CircularBounds.newInstance(new LatLng(lat, lng), 250.0), fields)
                    .setMaxResultCount(10).build();
            client.searchNearby(request).addOnSuccessListener(response -> {
                String bestName = ""; double best = 121.0;
                for (Place place : response.getPlaces()) {
                    LatLng point = place.getLatLng(); String name = cleanLandmarkName(place.getName());
                    if (point == null || name.isEmpty()) continue;
                    double d = distanceMeter(lat, lng, point.latitude, point.longitude);
                    // A POI farther than ~120 m is often misleading for a driver's pickup/dropoff label.
                    if (d <= 120.0 && d < best) { best = d; bestName = name; }
                }
                nearbyLandmarkCache.put(cacheKey,bestName);
                callback.onResult(bestName);
            }).addOnFailureListener(error -> {
                placesErrorDetail("NEARBY_LANDMARK", error);
                if(error instanceof ApiException) placesCooldownUntilMs=System.currentTimeMillis()+PLACES_ERROR_COOLDOWN_MS;
                callback.onResult("");
            });
        } catch (Exception ignored) { callback.onResult(""); }
    }

    /** Removes placeholder/generic names that must never be sent to drivers. */
    protected String cleanLandmarkName(String value) {
        String v = firstNonEmpty(value, "").trim();
        if (v.isEmpty()) return "";
        String n = v.toLowerCase(Locale.ROOT);
        if (n.equals("lokasi dipilih") || n.equals("selected location") || n.equals("pin dipilih") ||
                n.equals("lokasi") || n.equals("unnamed road") || n.equals("jalan tanpa nama")) return "";
        if (n.startsWith("dekat ")) v = v.substring(6).trim();
        return v;
    }

    /**
     * Driver-safe fallback when Google has no useful POI near the pin.
     * Prefer village/sub-locality + district/sub-admin-area + postal code instead of a generic placeholder.
     */
    protected String buildAdministrativeFallback(double lat, double lng) {
        try {
            if (!Geocoder.isPresent()) return "";
            Geocoder g = new Geocoder(this, new Locale("id", "ID"));
            List<Address> rows = g.getFromLocation(lat, lng, 1);
            if (rows == null || rows.isEmpty()) return "";
            Address a = rows.get(0);
            String village = firstNonEmpty(a.getSubLocality(), a.getLocality(), "");
            String district = firstNonEmpty(a.getSubAdminArea(), "");
            String postal = firstNonEmpty(a.getPostalCode(), "");
            StringBuilder out = new StringBuilder();
            if (!village.isEmpty()) out.append(village);
            if (!district.isEmpty() && !district.equalsIgnoreCase(village)) {
                if (out.length() > 0) out.append(", ");
                out.append(district);
            }
            if (!postal.isEmpty()) {
                if (out.length() > 0) out.append(" • ");
                out.append(postal);
            }
            return out.toString().trim();
        } catch (Exception ignored) { return ""; }
    }

    protected void applyResolvedAddress(boolean isPickup, String address) {
        if (destroyed) return;
        if (isPickup) {
            pickupAddress = address;
            pickupText.setText("Penjemputan: " + address);
            pickupBtn.setText("●  Jemput\n" + shortAddress(address));
            updateGpsBadge(liveGpsBest);
            if (mapView != null) mapView.setPickup(pickupLat, pickupLng, address);
        } else {
            deliveryAddress = address;
            deliveryText.setText("Pengantaran: " + address);
            deliveryBtn.setText("●  Tujuan\n" + shortAddress(address));
            if (mapView != null) mapView.setDelivery(deliveryLat, deliveryLng, address);
        }
        if (validCoordinate(pickupLat, pickupLng) && validCoordinate(deliveryLat, deliveryLng)) requestPaymentQuote();
    }



    protected String buildSmartAddress(double lat, double lng) {
        String nearName = findNearestPlaceName(lat, lng);
        String roadName = reverseAddress(lat, lng);

        if (nearName.length() > 0 && roadName.length() > 0) {
            return "Dekat " + nearName + ", " + roadName;
        }

        if (nearName.length() > 0) {
            return "Dekat " + nearName;
        }

        if (roadName.length() > 0) {
            return roadName;
        }

        return String.format(Locale.US, "%.6f, %.6f", lat, lng);
    }

    protected String reverseAddress(double lat, double lng) {
        return geocodingRepository.reverse(lat, lng);
    }


    protected String compactDisplayName(String value) {
        String v = firstNonEmpty(value, "");
        if (v.length() == 0) return "";

        String[] parts = v.split(",");
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < parts.length && i < 3; i++) {
            String part = parts[i].trim();
            if (part.length() == 0) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(part);
        }

        return sb.length() > 0 ? sb.toString() : v;
    }

    protected String findNearestPlaceName(double lat, double lng) {
        String food = findNearestFromUrl(GET_BUSINESSES_URL, "businesses", lat, lng, 150);
        if (food.length() > 0) return food;

        String laundry = findNearestFromUrl(GET_LAUNDRIES_URL, "laundries", lat, lng, 150);
        if (laundry.length() > 0) return laundry;

        return "";
    }

    protected String findNearestFromUrl(String urlText, String arrayKey, double lat, double lng, double maxMeter) {
        try {
            JSONObject res = getJson(urlText + "?v=" + System.currentTimeMillis());

            if (!res.optBoolean("success", false)) return "";

            JSONArray arr = res.optJSONArray(arrayKey);
            if (arr == null) return "";

            String bestName = "";
            double bestDistance = maxMeter;

            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;

                double itemLat = item.optDouble("latitude", 0);
                double itemLng = item.optDouble("longitude", 0);

                if (!validCoord(itemLat, itemLng)) continue;

                double d = distanceMeter(lat, lng, itemLat, itemLng);

                if (d <= bestDistance) {
                    bestDistance = d;
                    bestName = firstNonEmpty(
                            item.optString("name", ""),
                            item.optString("business_name", ""),
                            item.optString("title", "")
                    );
                }
            }

            return bestName;
        } catch (Exception ignored) {
            return "";
        }
    }

    protected void loadMapPlaces() {
        featureRuntime.newThread(() -> {
            try {
                JSONObject food = getJson(GET_BUSINESSES_URL + "?v=" + System.currentTimeMillis());
                JSONObject laundry = getJson(GET_LAUNDRIES_URL + "?v=" + System.currentTimeMillis());

                featureRuntime.post(mainHandler, () -> {
                    if (destroyed || !mapReady) return;

                    if (mapView != null) mapView.clearPlaces();
                    drawPlaces(food.optJSONArray("businesses"), "TransFood");
                    drawPlaces(laundry.optJSONArray("laundries"), "TransLaundry");
                });
            } catch (Exception ignored) {}
        }).start();
    }

    protected void drawPlaces(JSONArray arr, String type) {
        if (arr == null) return;

        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) continue;

            double lat = item.optDouble("latitude", 0);
            double lng = item.optDouble("longitude", 0);

            if (!validCoord(lat, lng)) continue;

            String name = firstNonEmpty(
                    item.optString("name", ""),
                    item.optString("business_name", ""),
                    type
            );

            String address = firstNonEmpty(
                    item.optString("address", ""),
                    item.optString("category", ""),
                    ""
            );

            if (mapView != null) mapView.addPlace(lat, lng, name, type, address);
        }
    }

    protected void loadOnlineDrivers() {
        if (destroyed) return;

        featureRuntime.newThread(() -> {
            try {
                JSONObject res = getJson(GET_ONLINE_DRIVERS_URL + "?type=" + driverType() + "&v=" + System.currentTimeMillis());
                JSONArray arr = res.optJSONArray("drivers");

                featureRuntime.post(mainHandler, () -> {
                    if (destroyed || !mapReady) return;

                    if (mapView != null) mapView.clearOnlineDrivers();

                    int onlineCount = arr == null ? 0 : arr.length();
                    int availableCount = 0;
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject driver = arr.optJSONObject(i);
                            if (driver != null && !isDriverBusy(driver)) availableCount++;
                        }
                    }
                    boolean noDriversOnline = onlineCount == 0;
                    boolean allDriversBusy = onlineCount > 0 && availableCount == 0;
                    if (driverAvailabilityText != null) {
                        driverAvailabilityText.setVisibility((noDriversOnline || allDriversBusy) ? View.VISIBLE : View.GONE);
                        if (noDriversOnline) {
                            driverAvailabilityText.setText("Semua driver menjalankan tugas, orderan tetap kami terima");
                            driverAvailabilityText.setTextColor(Color.parseColor("#B45309"));
                            driverAvailabilityText.setBackground(roundStroke("#FFF7ED", "#FED7AA", dp(10), 1));
                        } else if (allDriversBusy) {
                            driverAvailabilityText.setText("Semua driver menjalankan tugas, orderan tetap kami terima");
                            driverAvailabilityText.setTextColor(Color.parseColor("#1D4ED8"));
                            driverAvailabilityText.setBackground(roundStroke("#EFF6FF", "#BFDBFE", dp(10), 1));
                        }
                    }

                    if (arr == null) return;

                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject d = arr.optJSONObject(i);
                        if (d == null) continue;

                        double lat = d.optDouble("latitude", 0);
                        double lng = d.optDouble("longitude", 0);

                        if (!validCoord(lat, lng)) continue;

                        String name = firstNonEmpty(
                                d.optString("name", ""),
                                d.optString("username", ""),
                                "Driver"
                        );

                        if (mapView != null) mapView.addOnlineDriver(lat, lng, name, isCarService());
                    }
                });
            } catch (Exception ignored) {}
        }).start();

        mainHandler.postDelayed(() -> {
            if (!destroyed && mapReady && !isFinishing()) {
                loadOnlineDrivers();
            }
        }, 15000);
    }

    protected JSONObject getJson(String urlText) throws Exception {
        HttpURLConnection conn = null;

        try {
            conn = CustomerApiClient.open(this, urlText);
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setRequestProperty("Accept", "application/json");
            String requestToken = refreshAuthToken();
            if (!requestToken.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + requestToken);
                conn.setRequestProperty("X-App-Scope", "customer");
            }
            conn.setRequestProperty("X-Device-UUID", DeviceIdentityManager.getInstallationUuid(this));

            String body = readStream(conn.getInputStream()).trim();
            return body.length() == 0 ? new JSONObject() : new JSONObject(body);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    protected double distanceMeter(double lat1, double lng1, double lat2, double lng2) {
        return CustomerGeoMath.distanceMeters(lat1, lng1, lat2, lng2);
    }

    protected String refreshAuthToken() {
        try {
            String latest = new SessionManager(this).getToken();
            if (latest != null && !latest.trim().isEmpty()) {
                authToken = latest.trim();
            }
        } catch (Exception ignored) {}
        return authToken == null ? "" : authToken.trim();
    }

    protected boolean ensureAuthTokenForOrder() {
        if (!refreshAuthToken().isEmpty()) return true;
        toastDialog("Sesi login tidak memiliki token autentikasi. Silakan login ulang sekali untuk memperbarui sesi.");
        return false;
    }

    protected void createOrder() {
        if (ordering) return;
        if(!locationReady() || !locationQuoteReady) { updateWizardState(); return; }

        if (userId <= 0) {
            readUser();
        }

        if (userId <= 0) {
            toastDialog("User ID tidak ditemukan. Silakan login ulang.");
            return;
        }

        if (!ensureAuthTokenForOrder()) {
            return;
        }

        if (!validCoord(pickupLat, pickupLng)) {
            toastDialog("Pilih lokasi jemput terlebih dahulu.");
            return;
        }

        if (!validCoord(deliveryLat, deliveryLng)) {
            toastDialog("Pilih lokasi pengantaran terlebih dahulu.");
            return;
        }

        if (noteInput == null || noteInput.getText().toString().trim().isEmpty()) {
            orderAfterNote=true;
            showNoteDialog();
            return;
        }

        final String submittedDriverNote=noteInput.getText().toString().trim();
        ordering = true;
        setLoading(true);
        orderBtn.setEnabled(false);
        orderBtn.setText("Mencari driver...");

        featureRuntime.newThread(() -> {
            try {
                String orderId = "ORD-" + System.currentTimeMillis();

                JSONObject pickup = new JSONObject();
                pickup.put("latitude", pickupLat);
                pickup.put("longitude", pickupLng);
                pickup.put("address", firstNonEmpty(pickupAddress, "Lokasi Jemput"));

                JSONObject delivery = new JSONObject();
                delivery.put("latitude", deliveryLat);
                delivery.put("longitude", deliveryLng);
                delivery.put("address", firstNonEmpty(deliveryAddress, "Lokasi Pengantaran"));

                JSONObject userLocation = new JSONObject();
                userLocation.put("latitude", pickupLat);
                userLocation.put("longitude", pickupLng);

                JSONObject payload = new JSONObject();
                payload.put("id", orderId);
                payload.put("user_id", userId);
                payload.put("username", username);
                payload.put("customer", username);
                payload.put("order_type", serviceName());
                payload.put("driver_type", driverType());
                payload.put("service_type", serviceName());
                payload.put("service_name", serviceName());
                payload.put("price_mode", priceMode);
                payload.put("scheduled_at", scheduledAt);
                payload.put("family_member_id", familyMemberId);
                payload.put("price_guarantee", true);
                payload.put("pickup", pickup);
                payload.put("delivery", delivery);
                payload.put("pickup_address", firstNonEmpty(pickupAddress, "Lokasi Jemput"));
                payload.put("delivery_address", firstNonEmpty(deliveryAddress, "Lokasi Pengantaran"));
                payload.put("userLocation", userLocation);
                payload.put("note", submittedDriverNote);
                payload.put("payment_method", paymentMethod);
                payload.put("voucher_code", voucherInput == null ? "" : voucherInput.getText().toString().trim().toUpperCase(Locale.US));
                JSONObject eco = ecosystemFeatures.toJson();
                payload.put("ecosystem", eco);
                payload.put("waypoints", eco.optJSONArray("waypoints"));
                payload.put("trip_monitoring", ecosystemFeatures.tripMonitoring);
                payload.put("audio_protect", ecosystemFeatures.audioProtect);
                payload.put("group_size", ecosystemFeatures.groupSize);
                payload.put("split_fare_mode", ecosystemFeatures.splitFareMode);
                if (splitBillManager != null && !splitBillManager.sessionKey().isEmpty()) payload.put("split_session_key", splitBillManager.sessionKey());
                if (splitBillManager != null && splitBillManager.groupSize() > 1) payload.put("split_bill_required", true);

                JSONObject res = ReliableOrderClient.create(this, CREATE_ORDER_URL, payload, TIMEOUT_MS);
                featureRuntime.post(mainHandler, () -> handleOrderResult(res));
            } catch (Exception e) {
                featureRuntime.post(mainHandler, () -> {
                    resetOrderButton();
                    toastDialog(ReliableOrderClient.failureMessage(e));
                });
            }
        }).start();
    }

    protected void handleOrderResult(JSONObject res) {
        resetOrderButton();

        if (res == null || !res.optBoolean("success", false)) {
            String msg = res != null
                    ? res.optString("message", "Gagal membuat order " + orderNoun() + ".")
                    : "Gagal membuat order " + orderNoun() + ".";
            toastDialog(TransivaUserMessage.fromServer(msg));
            return;
        }

        String orderId = firstNonEmpty(res.optString("order_id", ""), res.optString("id", ""));
        syncEcosystemOrder(orderId);
        if (orderId.length() == 0) {
            toastDialog("Order berhasil, tetapi ID order tidak ditemukan.");
            return;
        }

        if ("scheduled".equalsIgnoreCase(res.optString("status", ""))) {
            toastDialog("Schedule Ride tersimpan untuk " + res.optString("scheduled_at", scheduledAt) + ". Driver akan dicari sekitar 15 menit sebelum jadwal.");
            finish();
            return;
        }

        getSharedPreferences("transiva", MODE_PRIVATE).edit()
                .putString("active_order_id", orderId)
                .putString("active_order_type", serviceName())
                .putString("active_driver_type", driverType())
                .putString("active_service_name", serviceName())
                .putString("pickup_lat", String.valueOf(pickupLat))
                .putString("pickup_lng", String.valueOf(pickupLng))
                .putString("delivery_lat", String.valueOf(deliveryLat))
                .putString("delivery_lng", String.valueOf(deliveryLng))
                .putString("pickup_address", firstNonEmpty(pickupAddress, "Lokasi Jemput"))
                .putString("delivery_address", firstNonEmpty(deliveryAddress, "Lokasi Pengantaran"))
                .putString("active_order_price", res.optString("price", ""))
                .putString("active_order_payment_method", res.optString("payment_method", paymentMethod))
                .putBoolean("active_order_trip_monitoring", ecosystemFeatures.tripMonitoring)
                .putBoolean("active_order_audio_protect", ecosystemFeatures.audioProtect)
                .putInt("active_order_group_size", ecosystemFeatures.groupSize)
                .putString("active_order_split_fare_mode", ecosystemFeatures.splitFareMode)
                .putString("active_order_voucher", res.optString("voucher_code", ""))
                .apply();

        try {
            Intent i = new Intent(this, SearchDriverActivity.class);
            i.putExtra("order_id", orderId);
            i.putExtra("active_order_id", orderId);
            i.putExtra("active_driver_type", driverType());
            i.putExtra("driver_type", driverType());
            i.putExtra("active_order_type", serviceName());
            i.putExtra("pickup_lat", String.valueOf(pickupLat));
            i.putExtra("pickup_lng", String.valueOf(pickupLng));
            i.putExtra("delivery_lat", String.valueOf(deliveryLat));
            i.putExtra("delivery_lng", String.valueOf(deliveryLng));
            i.putExtra("pickup_address", firstNonEmpty(pickupAddress, "Lokasi Jemput"));
            i.putExtra("delivery_address", firstNonEmpty(deliveryAddress, "Lokasi Pengantaran"));
            startActivity(i);
            finish();
        } catch (Exception e) {
            toastDialog("Order " + serviceName() + " berhasil dibuat. ID: " + orderId);
        }
    }

    protected void syncEcosystemOrder(String orderId){
        if(orderId==null||orderId.trim().isEmpty())return;
        JSONObject b=new JSONObject(); try{b.put("action","sync");b.put("order_id",orderId);b.put("ecosystem",ecosystemFeatures.toJson());}catch(Exception ignored){}
        featureRuntime.newThread(()->{try{RideSafetyApi.post(this,"ride_ecosystem_order.php",b);}catch(Exception ignored){}}).start();
    }




    private int routePreviewVersion = 0;
    private int quoteVersion = 0;
    private boolean locationQuoteReady;
    private boolean quoteLoading;
    private boolean pickupAddressResolving;
    private double resolvingPickupLat, resolvingPickupLng;
    private long deliveryAddressRequest;
    private boolean locationReady() {
        boolean busy=pickupAddressResolving && Math.abs(pickupLat-resolvingPickupLat)<0.000001 && Math.abs(pickupLng-resolvingPickupLng)<0.000001;
        return !busy && validCoord(pickupLat,pickupLng) && validCoord(deliveryLat,deliveryLng)
            && addressReady(pickupAddress) && addressReady(deliveryAddress);
    }
    private boolean addressReady(String address) {
        String value=address==null?"":address.trim().toLowerCase(Locale.ROOT);
        return !value.isEmpty() && !value.contains("mencari") && !value.contains("mengambil") && !value.contains("belum") && !value.contains("menunggu");
    }


    private void requestPaymentQuote() {
        final int quoteRequest = ++quoteVersion;
        locationQuoteReady=false;
        quoteLoading=false;
        updateWizardState();
        if(!locationReady()) return;
        quoteLoading=true;
        requestVisibleOsrmRoute();
        if (!validCoordinate(pickupLat, pickupLng)
                || !validCoordinate(deliveryLat, deliveryLng)) {
            if (paymentSummaryText != null) {
                paymentSummaryText.setText("Pilih titik penjemputan dan pengantaran");
            }
            if (distanceInfoText != null) distanceInfoText.setText(serviceName() + " • Jarak -");
            if (durationInfoText != null) durationInfoText.setText("Estimasi waktu -");
            if (finalPriceText != null) finalPriceText.setText("Rp -");
            if (originalPriceText != null) originalPriceText.setVisibility(View.GONE);
            if (discountInfoText != null) discountInfoText.setVisibility(View.GONE);
            return;
        }

        double fallbackDistance = 0;
        double previousLat = pickupLat, previousLng = pickupLng;
        for (int i = 0; i < ecosystemFeatures.waypoints.length(); i++) {
            JSONObject stop = ecosystemFeatures.waypoints.optJSONObject(i);
            if (stop == null) continue;
            double lat = stop.optDouble("latitude", Double.NaN);
            double lng = stop.optDouble("longitude", Double.NaN);
            if (!validCoord(lat, lng)) continue;
            fallbackDistance += distanceMeter(previousLat, previousLng, lat, lng);
            previousLat = lat; previousLng = lng;
        }
        fallbackDistance += distanceMeter(previousLat, previousLng, deliveryLat, deliveryLng);
        final double fallbackKm = Math.max(0.1, fallbackDistance * 1.25 / 1000.0);

        // Tampilkan estimasi jarak/waktu segera, lalu harga diisi dari database.
        final double fallbackMinutes = Math.max(
                1.0,
                (fallbackKm / 25.0) * 60.0
        );

        distanceInfoText.setText(String.format(new Locale("id", "ID"), serviceName() + " • %.1f km", fallbackKm));
        durationInfoText.setText(String.format(new Locale("id", "ID"), "± %.0f menit • estimasi perjalanan", fallbackMinutes));
        finalPriceText.setTextSize(19);
        finalPriceText.setContentDescription("Sedang menghitung tarif");
        finalPriceText.setText("Menghitung...");
        finalPriceText.setTextColor(Color.parseColor("#64748B"));
        paymentSummaryText.setText("Mengambil tarif dari database...");

        featureRuntime.newThread(() -> {
            try {
                JSONObject payload = new JSONObject();

                JSONObject pickup = new JSONObject();
                pickup.put("latitude", pickupLat);
                pickup.put("longitude", pickupLng);

                JSONObject delivery = new JSONObject();
                delivery.put("latitude", deliveryLat);
                delivery.put("longitude", deliveryLng);

                payload.put("pickup", pickup);
                payload.put("delivery", delivery);

                // Field datar ditambahkan untuk kompatibilitas endpoint lama/hosting cache.
                payload.put("pickup_lat", pickupLat);
                payload.put("pickup_lng", pickupLng);
                payload.put("delivery_lat", deliveryLat);
                payload.put("delivery_lng", deliveryLng);
                payload.put("service_type", isCarService() ? "Transcar" : "Transbike");
                payload.put("payment_method", paymentMethod);
                payload.put("price_mode", priceMode);
                payload.put("ecosystem", ecosystemFeatures.toJson());
                payload.put("waypoints", ecosystemFeatures.waypoints);
                payload.put(
                        "voucher_code",
                        voucherInput == null
                                ? ""
                                : voucherInput.getText().toString()
                                .trim()
                                .toUpperCase(Locale.US)
                );

                JSONObject res = postJson(PAYMENT_QUOTE_URL, payload);

                featureRuntime.post(mainHandler, () -> {
                    if (destroyed || quoteRequest != quoteVersion) return;
                    quoteLoading=false;

                    if (!res.optBoolean("success", false)) {
                        String message = firstNonEmpty(
                                res.optString("message", ""),
                                "Tarif belum dapat dihitung"
                        );
                        paymentSummaryText.setText(TransivaUserMessage.fromServer(message));
                        showQuoteRetry();
                        finalPriceText.setTextColor(Color.parseColor("#0B3A78"));
                        return;
                    }

                    int original = jsonInt(
                            res,
                            "original_price",
                            jsonInt(res, "standard_price", 0)
                    );
                    int discount = jsonInt(res, "discount", 0);
                    int total = jsonInt(
                            res,
                            "price",
                            jsonInt(res, "final_price", original)
                    );
                    int balance = jsonInt(res, "balance", 0);
                    int hematRemaining = jsonInt(res, "hemat_remaining", -1);
                    int hematLimit = jsonInt(res, "hemat_limit", -1);
                    String hematTier = res.optString("hemat_tier", "");

                    double distanceKm = jsonDouble(
                            res,
                            "distance_km",
                            fallbackKm
                    );
                    double durationMinutes = jsonDouble(
                            res,
                            "duration_minutes",
                            jsonDouble(res, "estimated_minutes", fallbackMinutes)
                    );

                    distanceKm = Math.max(0.1, distanceKm);
                    durationMinutes = Math.max(1.0, durationMinutes);

                    distanceInfoText.setText(String.format(new Locale("id", "ID"), serviceName() + " • %.1f km", distanceKm));
                    durationInfoText.setText(String.format(new Locale("id", "ID"), "± %.0f menit • estimasi perjalanan", durationMinutes));

                    if (total <= 0) {
                        paymentSummaryText.setText("Tarif database tidak valid");
                        showQuoteRetry();
                        finalPriceText.setTextColor(Color.parseColor("#0B3A78"));
                        return;
                    }

                    locationQuoteReady=true;
                    lastQuotedFare = total;
                    finalPriceText.setContentDescription("Tarif Rp " + formatMoney(total));
                    finalPriceText.setText("Rp " + formatMoney(total));
                    updateWizardState();

                    if (discount > 0 && original > total) {
                        originalPriceText.setVisibility(View.VISIBLE);
                        originalPriceText.setText("Rp " + formatMoney(original));
                        originalPriceText.setPaintFlags(
                                originalPriceText.getPaintFlags()
                                        | Paint.STRIKE_THRU_TEXT_FLAG
                        );
                        finalPriceText.setTextColor(Color.parseColor("#16A34A"));
                        discountInfoText.setVisibility(View.VISIBLE);
                        discountInfoText.setText(
                                "Hemat Rp " + formatMoney(discount)
                        );
                    } else {
                        originalPriceText.setVisibility(View.GONE);
                        originalPriceText.setPaintFlags(
                                originalPriceText.getPaintFlags()
                                        & ~Paint.STRIKE_THRU_TEXT_FLAG
                        );
                        finalPriceText.setTextColor(Color.parseColor("#0B3A78"));
                        discountInfoText.setVisibility(View.GONE);
                    }

                    String label = paymentMethod.equals("balance")
                            ? "Transiva Pay"
                            : "Tunai";
                    paymentChoiceBtn.setText(paymentMethod.equals("balance")
                            ? "💳 Pembayaran: Transiva Pay"
                            : "💵 Pembayaran: Tunai");

                    String info = label;
                    if (paymentMethod.equals("balance")) {
                        info += " • Saldo Rp" + formatMoney(balance);
                    }
                    if (ecosystemFeatures.groupSize > 1) {
                        int share = (int)Math.ceil(total / (double)ecosystemFeatures.groupSize);
                        info += " • Group " + ecosystemFeatures.groupSize + " ≈ Rp" + formatMoney(share) + "/orang";
                    }
                    if (ecosystemFeatures.waypoints.length() > 0) {
                        info += " • " + ecosystemFeatures.waypoints.length() + " stop";
                    }

                    fareText.setText("Tarif database: Rp" + formatMoney(total));
                    paymentSummaryText.setText(info);
                });
            } catch (Exception e) {
                featureRuntime.post(mainHandler, () -> {
                    if (destroyed || quoteRequest != quoteVersion) return;
                    quoteLoading=false;
                    paymentSummaryText.setText("Gagal terhubung ke server tarif");
                    showQuoteRetry();
                    finalPriceText.setTextColor(Color.parseColor("#0B3A78"));
                });
            }
        }).start();
    }

    private void showQuoteRetry() {
        finalPriceText.setTextSize(13);
        finalPriceText.setText("Coba lagi ↻");
        finalPriceText.setContentDescription("Tarif belum tersedia. Ketuk untuk mencoba lagi");
        updateWizardState();
    }

    private int jsonInt(JSONObject json, String key, int fallback) {
        return CustomerJsonValues.intValue(json, key, fallback);
    }

    private double jsonDouble(JSONObject json, String key, double fallback) {
        return CustomerJsonValues.doubleValue(json, key, fallback);
    }

    private String formatMoney(int value) {
        return CustomerCommonFormatters.formatMoneyInt(value);
    }

    private boolean validCoordinate(double latitude, double longitude) {
        return CustomerGeoMath.valid(latitude, longitude);
    }

    private JSONObject postJson(String urlText, JSONObject payload) throws Exception {
        return TransivaHttpRepository.postJson(this, urlText, payload, TIMEOUT_MS);
    }

    private String readStream(InputStream stream) throws Exception {
        return CustomerIo.readUtf8(stream);
    }

    private void updateModeUI() {
        boolean pickupMode = "pickup".equals(mode);

        boolean routeComplete = validCoord(pickupLat, pickupLng) && validCoord(deliveryLat, deliveryLng);
        if (bookingDetailsCard != null) bookingDetailsCard.setVisibility(routeComplete ? View.VISIBLE : View.GONE);
        if (pickupBtn != null) { pickupBtn.setEnabled(!routeComplete); pickupBtn.setClickable(!routeComplete); }
        if (deliveryBtn != null) { deliveryBtn.setEnabled(!routeComplete); deliveryBtn.setClickable(!routeComplete); }
        if (waypointQuickButton != null) waypointQuickButton.setVisibility(View.GONE);
        if (waypointBtn != null) waypointBtn.setVisibility(View.GONE);
        refreshWaypointHeader();
        modeText.setText(
                routeComplete
                        ? "Rute siap"
                        : (pickupMode ? "Cek titik jemput" : "Cari atau pilih tujuan")
        );
        if (tripRouteSummaryText != null && routeComplete) {
            String from = compactDisplayName(firstNonEmpty(pickupAddress, "Lokasi jemput"));
            String to = compactDisplayName(firstNonEmpty(deliveryAddress, "Tujuan"));
            StringBuilder itinerary = new StringBuilder(from);
            for (int i = 0; i < ecosystemFeatures.waypoints.length(); i++) {
                itinerary.append("  →  ").append(compactDisplayName(addressForWaypoint(i)));
            }
            itinerary.append("  →  ").append(to);
            tripRouteSummaryText.setText(itinerary.toString());
        }

        pickupBtn.setAlpha(pickupMode ? 1f : .80f);
        deliveryBtn.setAlpha(pickupMode ? .80f : 1f);
        if (mapView != null) {
            if (routeComplete) {
                mapView.showOrderAction(false, "");
            } else {
                mapView.setSelectionMode(pickupMode ? "pickup" : "delivery");
                mapView.showCenterPin(true);
            }
        }        updateWizardState();
    }

    private void requestVisibleOsrmRoute() {
        if (!mapReady || mapView == null || !validCoord(pickupLat, pickupLng)) return;
        final int previewRequest = ++routePreviewVersion;
        final JSONArray routeStops = new JSONArray();
        try {
            routeStops.put(new JSONArray().put(pickupLat).put(pickupLng));
            for (int i = 0; i < ecosystemFeatures.waypoints.length(); i++) {
                JSONObject wp = ecosystemFeatures.waypoints.optJSONObject(i);
                if (wp == null) continue;
                double lat = wp.optDouble("latitude", Double.NaN);
                double lng = wp.optDouble("longitude", Double.NaN);
                if (validCoord(lat, lng)) routeStops.put(new JSONArray().put(lat).put(lng));
            }
            if (validCoord(deliveryLat, deliveryLng)) routeStops.put(new JSONArray().put(deliveryLat).put(deliveryLng));
        } catch (Exception ignored) {}

        if (routeStops.length() < 2) return;
        featureRuntime.newThread(() -> {
            try {
                JSONArray merged = new JSONArray();
                for (int i = 0; i < routeStops.length() - 1; i++) {
                    JSONArray a = routeStops.optJSONArray(i);
                    JSONArray b = routeStops.optJSONArray(i + 1);
                    if (a == null || b == null) continue;
                    StableRouteEngine.Result segment = StableRouteEngine.fetch(
                            a.optDouble(0), a.optDouble(1), b.optDouble(0), b.optDouble(1));
                    JSONArray pts = segment.latLngPoints;
                    for (int j = 0; pts != null && j < pts.length(); j++) {
                        if (i > 0 && j == 0) continue;
                        JSONArray pt = pts.optJSONArray(j);
                        if (pt != null) merged.put(pt);
                    }
                }
                featureRuntime.post(mainHandler, () -> {
                    if (!destroyed && previewRequest == routePreviewVersion && mapView != null) {
                        mapView.setWaypoints(ecosystemFeatures.waypoints);
                        mapView.drawRideRoute(merged);
                    }
                });
            } catch (Exception ignored) {}
        }, "transiva-google-osrm-multistop-preview").start();
    }
    private boolean validCoord(double lat, double lng) {
        return CustomerGeoMath.valid(lat, lng);
    }
    private boolean isDriverBusy(JSONObject driver) {
        if (driver == null) return false;
        Object raw = driver.opt("is_busy");
        if (raw == null || raw == JSONObject.NULL) raw = driver.opt("busy");
        if (raw == null || raw == JSONObject.NULL) raw = driver.opt("status");
        String value = String.valueOf(raw == null ? "" : raw).trim().toLowerCase(Locale.US);
        return "1".equals(value) || "true".equals(value) || "busy".equals(value)
                || "sibuk".equals(value) || "on_trip".equals(value)
                || "in_progress".equals(value) || "driver_accepted".equals(value);
    }

    private void handleWizardPrimaryAction() {
        if(!locationReady()) { updateWizardState(); return; }
        if (!validCoord(deliveryLat, deliveryLng)) {
            mode = "delivery";
            updateModeUI();
            toastDialog("Tentukan tujuan perjalanan terlebih dahulu.");
            return;
        }
        if (!validCoord(pickupLat, pickupLng)) {
            mode = "pickup";
            updateModeUI();
            toastDialog("Periksa dan tentukan lokasi jemput.");
            return;
        }
        if (!locationQuoteReady || finalPriceText == null || finalPriceText.getText().toString().contains("-")) {
            requestPaymentQuote();
            toastDialog("Sedang menghitung harga perjalanan. Coba lagi sebentar.");
            return;
        }
        if (wizardStepText != null) {
            String green = "#16A34A";
            wizardStepText.setText(android.text.Html.fromHtml("<font color='"+green+"'><b>✓ Tujuan</b></font> &nbsp;›&nbsp; <font color='"+green+"'><b>✓ Harga</b></font> &nbsp;›&nbsp; <font color='"+green+"'><b>✓ Pesan</b></font>"));
            wizardStepText.setScaleX(.96f); wizardStepText.setScaleY(.96f);
            wizardStepText.animate().scaleX(1f).scaleY(1f).setDuration(180L).start();
        }
        createOrder();
    }

    private void setWizardProgress(int stage) {
        if (wizardStepText == null) return;
        String green = "#16A34A", blue = "#0B7CFF", gray = "#94A3B8";
        String a = stage > 0 ? "<font color='"+green+"'><b>✓ Tujuan</b></font>" : "<font color='"+blue+"'><b>● Tujuan</b></font>";
        String b = stage > 1 ? "<font color='"+green+"'><b>✓ Harga</b></font>" : (stage == 1 ? "<font color='"+blue+"'><b>● Harga</b></font>" : "<font color='"+gray+"'>2 Harga</font>");
        String c = stage >= 2 ? "<font color='"+blue+"'><b>● Pesan</b></font>" : "<font color='"+gray+"'>3 Pesan</font>";
        wizardStepText.setText(android.text.Html.fromHtml(a + " &nbsp;›&nbsp; " + b + " &nbsp;›&nbsp; " + c));
        wizardStepText.setAlpha(0.55f);
        wizardStepText.animate().alpha(1f).setDuration(220L).start();
    }

    private void updateWizardState() {
        if (orderBtn == null) return;
        if (ordering) {
            orderBtn.setVisibility(View.VISIBLE);
            orderBtn.setEnabled(false);
            return;
        }
        boolean ready = locationReady() && locationQuoteReady && lastQuotedFare > 0;
        orderBtn.setVisibility(ready ? View.VISIBLE : View.GONE);
        orderBtn.setEnabled(ready);
        orderBtn.setAlpha(1f);
        if (wizardStepText != null) setWizardProgress(ready ? 2 : (locationReady() ? 1 : 0));
        if (ready) orderBtn.setText("PESAN " + serviceName().toUpperCase(Locale.US));
    }

    private void resetOrderButton() { ordering = false; setLoading(false); orderBtn.setEnabled(true); updateWizardState(); }
    private void setLoading(boolean b) { if (progressBar != null) progressBar.setVisibility(b ? View.VISIBLE : View.GONE); }
    private int checkSelfPermissionCompat(String p) { return android.os.Build.VERSION.SDK_INT >= 23 ? checkSelfPermission(p) : PackageManager.PERMISSION_GRANTED; }
    private void requestLocationIfNeeded() { if (checkSelfPermissionCompat(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && android.os.Build.VERSION.SDK_INT >= 23) requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION); }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) goToMyLocation();
    }

    private void hideKeyboard() { try { ((InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(googleMapInput == null ? null : googleMapInput.getWindowToken(), 0); } catch (Exception ignored) {} }
    private void toastDialog(String msg) { try { new TransivaAlertDialogBuilder(this).setTitle("Transiva").setMessage(msg).setPositiveButton("OK", null).show(); } catch (Exception ignored) {} }
    private String firstNonEmpty(String... v) {
        return CustomerCommonFormatters.firstBasic(v);
    }

    private Button compactPointButton(String title, String sub, String color) {
        Button b = new Button(this);
        b.setText(title + "\n" + sub);
        b.setAllCaps(false);
        b.setTextSize(10.5f);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(dp(10), dp(2), dp(8), dp(2));
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(Color.parseColor(color));
        b.setBackground(roundStroke("#FFFFFF", "#E2E8F0", dp(14), 1));
        return b;
    }

    private String shortAddress(String value) {
        String v = firstNonEmpty(value, "");
        if (v.length() > 38) return v.substring(0, 38) + "...";
        return v;
    }

    private String shortCoord(double lat, double lng) {
        return String.format(Locale.US, "%.5f, %.5f", lat, lng);
    }

    private String cleanError(String value) {
        String v = firstNonEmpty(value, "");
        if (v.length() == 0) return "";
        if (v.length() > 160) v = v.substring(0, 160);
        return v;
    }



    private int getDrawableId(String... names) {
        try {
            for (String name : names) {
                int id = getResources().getIdentifier(name, "drawable", getPackageName());
                if (id > 0) return id;
            }
        } catch (Exception ignored) {}
        return 0;
    }



    private Button rowButton(String value, String color) { Button b = smallButton(value, "#FFFFFF", color, "#E2E8F0"); b.setGravity(Gravity.CENTER_VERTICAL); b.setPadding(dp(12), 0, dp(12), 0); return b; }
    private Button smallButton(String value, String bg, String fg, String stroke) { Button b = new Button(this); b.setText(value); b.setAllCaps(false); b.setTextSize(13); b.setTypeface(Typeface.DEFAULT_BOLD); b.setTextColor(Color.parseColor(fg)); b.setBackground(roundStroke(bg, stroke, dp(16), 1)); return b; }
    private TextView text(String value, int sp, String color, boolean bold) { TextView tv = new TextView(this); tv.setText(value); tv.setTextSize(sp); tv.setTextColor(Color.parseColor(color)); if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD); return tv; }
    private GradientDrawable round(String color, int radius) { GradientDrawable gd = new GradientDrawable(); gd.setColor(Color.parseColor(color)); gd.setCornerRadius(radius); return gd; }
    private GradientDrawable roundStroke(String color, String stroke, int radius, int width) { GradientDrawable gd = round(color, radius); gd.setStroke(dp(width), Color.parseColor(stroke)); return gd; }
    private int dp(int v) {
        return CustomerUiPrimitives.dp(this, v);
    }

    @Override protected void onStart() {
        super.onStart();
        if (mapView != null) mapView.onStartMap();
    }

    @Override protected void onResume() {
        super.onResume();
        featureRuntime.onResume();
        liveGpsBest=null;
        lastGpsAppliedAt=0L;
        updateGpsBadge(null);
        startLiveGps();
        if (mapView != null) mapView.onResumeMap();
    }

    @Override protected void onPause() {
        stopLiveGps();
        featureRuntime.onPause();
        if (mapView != null) mapView.onPauseMap();
        super.onPause();
    }

    @Override public void onLowMemory() {
        super.onLowMemory();
        if (mapView != null) mapView.onLowMemoryMap();
    }

    @Override protected void onStop() {
        if (mapView != null) mapView.onStopMap();
        super.onStop();
    }

    @Override protected void onDestroy() {
        featureRuntime.destroy();
        destroyed = true;
        try {
            mainHandler.removeCallbacksAndMessages(null);
            if (mapView != null) {
                mapView.onDestroyMap();
                mapView = null;
            }
        } catch (Exception ignored) {}
        super.onDestroy();
    }

}
