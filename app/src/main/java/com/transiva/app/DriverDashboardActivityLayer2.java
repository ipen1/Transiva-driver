package com.transiva.app;

import androidx.core.content.ContextCompat;
import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.view.animation.OvershootInterpolator;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.transiva.app.driver.data.DriverDashboardRepositoryImpl;
import com.transiva.app.driver.data.DriverApiClient;
import com.transiva.app.driver.domain.DriverDashboardState;
import com.transiva.app.driver.domain.DriverClusterStatus;
import com.transiva.app.driver.domain.DriverOrder;
import com.transiva.app.driver.presentation.DriverDashboardContract;
import com.transiva.app.driver.presentation.DriverDashboardPresenter;
import com.transiva.app.driver.ui.DriverBottomNavigation;
import com.transiva.app.driver.ui.DriverPageTransition;

import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
abstract class DriverDashboardActivityLayer2 extends DriverDashboardActivityLayer1 {

    protected int clusterAccent(int drivers) {
        if (drivers <= 2) return Color.parseColor("#16A34A");
        if (drivers <= 6) return Color.parseColor("#0B7CFF");
        if (drivers <= 15) return Color.parseColor("#F59E0B");
        return Color.parseColor("#EF4444");
    }

    protected void sendEmergency() {
        showLoading(true);
        DriverApiClient api = new DriverApiClient(session);
        api.executor().execute(() -> {
            try {
                JSONObject body = session.getLastLocationJson();
                body.put("message", "Membutuhkan bantuan darurat");
                body.put("current_order_id", clean(session.get("current_order_id")));
                api.post("driver_sos_native.php", body);
                runOnUiThread(() -> { showLoading(false); showMessage("SOS terkirim ke seluruh driver dan admin."); });
            } catch (Exception error) {
                runOnUiThread(() -> { showLoading(false); showMessage("SOS gagal dikirim. Hubungi admin melalui telepon bila kondisi darurat."); });
            }
        });
    }

    protected void buildOrderSections() {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        priorityOrderTitle = text("⚡ Order Prioritas", 16, "#0B3A78", true);
        header.addView(priorityOrderTitle, new LinearLayout.LayoutParams(0, -2, 1));
        TextView live = text("LANGSUNG", 9, "#FFFFFF", true);
        live.setGravity(Gravity.CENTER);
        live.setPadding(dp(9), dp(4), dp(9), dp(4));
        live.setBackground(round("#EF4444", dp(999)));
        header.addView(live);
        add(orderSections, header, 0, dp(11), 0, dp(3));

        activeBox = new LinearLayout(this);
        activeBox.setOrientation(LinearLayout.VERTICAL);
        orderSections.addView(activeBox);

        offerBox = new LinearLayout(this);
        offerBox.setOrientation(LinearLayout.VERTICAL);
        orderSections.addView(offerBox);
    }

    @Override public void showLoading(boolean visible) {
        loading.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    @Override public void showDashboard(DriverDashboardState state) {
        currentState = state;

        nameText.setText(first(state.displayName, state.username, "Driver"));
        verificationText.setText(
                state.verified ? "✓ Terverifikasi" : "• Belum Terverifikasi");
        verificationText.setTextColor(Color.parseColor(
                state.verified ? "#0E9F4B" : "#D97706"));
        verificationText.setBackground(round(
                state.verified ? "#EAFBF1" : "#FFF7E6", dp(12)));

        balanceText.setText(rupiah(state.balance));
        earningText.setText(rupiah(state.todayEarning));
        if (growthScoreText != null) growthScoreText.setText("Driver Score " + state.driverScore + "/100 • " + state.driverScoreLabel);
        if (growthGoalText != null) growthGoalText.setText("Target hari ini " + rupiah(state.todayEarning) + " / " + rupiah(state.dailyGoal) + " • " + state.goalProgress + "%");
        if (growthRateText != null) growthRateText.setText("Pendapatan/jam " + rupiah(state.earningPerHour));
        if (destinationModeText != null) {
            String dm = clean(state.destinationMode);
            String dl = clean(state.destinationLabel);
            destinationModeText.setText("🏠 Mode tujuan: " + ("off".equalsIgnoreCase(dm) ? "Nonaktif" : (dl.isEmpty() ? ("home".equalsIgnoreCase(dm) ? "Pulang" : "Area pilihan") : dl)));
        }
        tripText.setText(String.valueOf(state.todayTrips));
        ratingText.setText(String.format(Locale.US, "%.1f", state.rating));
        onlineMinutesText.setText(formatMinutes(state.onlineMinutes));
        distanceText.setText(String.format(Locale.US, "%.1f km", state.todayDistanceKm));
        queueText.setText(state.queueRank > 0
                ? "Smart Queue: posisi " + state.queueRank + " dari " + Math.max(state.queueTotal, state.queueRank)
                : "Smart Queue: belum aktif");
        queueDetailText.setText(first(state.queueLabel, "Antrean dihitung otomatis dan adil."));
        assistantTitleText.setText(first(state.assistantTitle, "Asisten Transiva"));
        assistantMessageText.setText(buildAssistantMessage(state));
        int localHotspotScore = effectiveHotspotScore(state);
        String localHotspotLevel = localHotspotScore > 0
                ? effectiveHotspotLevel(state, localHotspotScore)
                : first(state.hotspotLevel, "NORMAL");
        hotspotText.setText(first(state.hotspotName, "Area sekitar Anda") + " • "
                + localHotspotLevel + " (" + localHotspotScore + "% )");
        if (clusterCurrentText != null) {
            String regionLabel = clean(state.currentRegionName);
            clusterCurrentText.setText(state.currentClusterId > 0
                    ? "🌐 " + (regionLabel.isEmpty() ? "Regional" : "Regional " + regionLabel)
                        + " • 📍 Cluster " + state.currentClusterName
                    : "📍 Regional/Cluster belum terdeteksi • aktifkan GPS");
        }
        renderClusterGrid(state);

        onlineLabel.setText(state.online ? "ONLINE" : "OFFLINE");
        onlineLabel.setTextColor(Color.parseColor(
                state.online ? "#16A34A" : "#EF4444"));
        setSwitch(state.online);

        session.put("driver_server_online", state.online ? "1" : "0");
        session.put("driver_is_online", state.online ? "1" : "0");

        // Bubble mengikuti status operasional driver, bukan lifecycle Activity.
        // Saat ONLINE bubble dipertahankan oleh service; saat OFFLINE bubble
        // langsung disembunyikan tanpa mematikan preferensi pengguna.
        if (state.online) DriverBubbleController.start(this);
        else DriverBubbleController.stopForOffline(this);

        // Status ONLINE dari server tidak boleh membuat aplikasi crash ketika
        // driver login kembali saat GPS/lokasi sedang mati. Driver tetap boleh
        // masuk ke dashboard; tracking baru dijalankan setelah izin + provider
        // lokasi benar-benar tersedia. Status ONLINE server tetap dipertahankan.
        boolean locationReady = hasLocationPermission() && hasBackgroundLocationPermission() && isLocationProviderEnabled();
        if (state.online && locationReady) {
            DriverServiceController.start(this);
        } else {
            DriverServiceController.stop(this);
        }

        readinessText.setText(
                state.online
                        ? (locationReady
                            ? "Online • Siap Terima Orderan."
                            : "Online • GPS/lokasi mati.")
                        : "Offline • Order tidak ditawarkan."
        );

        lastUpdateText.setText("Baru diperbarui");

        // Dashboard response is authoritative: badge Pesan only belongs to an
        // order that is still active. Cancelled/finished orders are pruned here.
        java.util.Set<String> activeMessageOrders = new java.util.HashSet<>();
        if (state.activeOrders != null) {
            for (DriverOrder active : state.activeOrders) {
                if (active != null && clean(active.id).length() > 0) activeMessageOrders.add(clean(active.id));
            }
        }
        DriverMessageUnreadRepository.retainOnlyActiveOrders(this, activeMessageOrders);
        DriverBottomNavigation.refreshUnread(this, bottomNavigationView);

        activeBox.removeAllViews();
        if (state.activeOrders == null || state.activeOrders.isEmpty()) {
            session.remove("current_order_id");
            // Tidak tampilkan placeholder di panel prioritas. Bila ada tawaran,
            // tawaran langsung menjadi kartu pertama yang terlihat.
        } else {
            session.put("current_order_id", state.activeOrders.get(0).id);
            int slot = 0;
            for (DriverOrder activeOrder : state.activeOrders) {
                slot++;
                if (activeOrder.raw != null) {
                    try {
                        activeOrder.raw.put("concurrent_slot", slot);
                        activeOrder.raw.put("active_count", state.activeOrders.size());
                        activeOrder.raw.put("max_active_orders", 2);
                    } catch (Exception ignored) { }
                }
                activeBox.addView(orderCard(activeOrder, true));
            }
        }

        offerBox.removeAllViews();
        if (offerCountdownController != null) offerCountdownController.beginSnapshot();
        if (!state.online) {
            // Panel prioritas tetap ringkas; status offline sudah terlihat di atas.
        } else if (state.offers.isEmpty()) {
            // Tidak tampilkan placeholder agar order aktif tetap menjadi fokus.
        } else {
            Set<String> activeOfferKeys = new HashSet<>();
            boolean hasFreshOffer = false;
            for (DriverOrder offer : state.offers) {
                String freshKey = offerCountdownController.key(offer);
                if (!seenOfferKeys.contains(freshKey)) hasFreshOffer = true;
            }
            if (!firstOfferSnapshot && hasFreshOffer) playIncomingOrderEffect();
            for (DriverOrder offer : state.offers) {
                String key = offerCountdownController.key(offer);
                activeOfferKeys.add(key);
                offerCountdownController.syncDeadline(offer);
                offerBox.addView(orderCard(offer, false));
            }
            offerCountdownController.retain(activeOfferKeys);
            offerCountdownController.tick();
            seenOfferKeys.clear();
            seenOfferKeys.addAll(activeOfferKeys);
            firstOfferSnapshot = false;
        }
        boolean hasPriorityOrder = (state.activeOrders != null && !state.activeOrders.isEmpty())
                || (state.offers != null && !state.offers.isEmpty());
        if (orderSections != null) {
            orderSections.setVisibility(hasPriorityOrder ? View.VISIBLE : View.GONE);
        }
        if (priorityOrderTitle != null) {
            int activeCount = state.activeOrders == null ? 0 : state.activeOrders.size();
            int offerCount = state.offers == null ? 0 : state.offers.size();
            if (activeCount > 0) {
                priorityOrderTitle.setText("🚗 Order Aktif" + (activeCount > 1 ? " • " + activeCount + " perjalanan" : ""));
            } else if (offerCount > 0) {
                priorityOrderTitle.setText("⚡ Tawaran Masuk" + (offerCount > 1 ? " • " + offerCount + " order" : ""));
            } else {
                priorityOrderTitle.setText("⚡ Order Prioritas");
            }
        }
        if (state.offers == null || state.offers.isEmpty()) {
            seenOfferKeys.clear();
            firstOfferSnapshot = false;
        }
    }

    protected void playIncomingOrderEffect() {
        if (page == null || offerBox == null) return;
        try {
            ObjectAnimator sx1 = ObjectAnimator.ofFloat(offerBox, View.SCALE_X, 0.92f, 1.04f, 1f);
            ObjectAnimator sy1 = ObjectAnimator.ofFloat(offerBox, View.SCALE_Y, 0.92f, 1.04f, 1f);
            ObjectAnimator alpha = ObjectAnimator.ofFloat(offerBox, View.ALPHA, 0.35f, 1f);
            AnimatorSet set = new AnimatorSet();
            set.playTogether(sx1, sy1, alpha);
            set.setDuration(650);
            set.setInterpolator(new OvershootInterpolator());
            set.start();
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0,180,90,180,90,320}, -1));
                else vibrator.vibrate(new long[]{0,180,90,180,90,320}, -1);
            }
            Toast.makeText(this, "🔥 ORDER BARU MASUK! Tetap semangat dan utamakan keselamatan.", Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) { }
    }

    @Override public void showActionLoading(String action, boolean visible) {
        showLoading(visible);
        onlineSwitch.setEnabled(!visible);
    }

    @Override public void showMessage(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override public void showSessionExpired() {
        session.forceLogout("session_expired");
        DriverServiceController.stop(this);
        Intent intent = new Intent(this, LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }

    @Override public void openTrip(DriverOrder order) {
        try {
            session.put("current_order_id", order.id);
            Intent intent = new Intent(this, DriverTripActivity.class);
            intent.putExtra("order_json", order.raw.toString());
            intent.putExtra("order_table", order.source);
            intent.putExtra("driver", session.getUsername());
            intent.putExtra("driver_type",
                    normalizeDriverType(session.getDriverType()));
            startActivity(intent);
        } catch (Exception error) {
            showMessage("Tidak dapat membuka halaman trip.");
        }
    }

    protected JSONObject foodPayload(JSONObject raw) {
        if (raw == null) return new JSONObject();
        JSONObject direct = raw.optJSONObject("food");
        if (direct != null) return direct;
        try {
            String note = raw.optString("note", "");
            if (!note.trim().isEmpty()) {
                JSONObject parsed = new JSONObject(note);
                if ("food".equalsIgnoreCase(parsed.optString("type", "")) || parsed.has("items")) return parsed;
            }
        } catch (Exception ignored) { }
        return new JSONObject();
    }

    protected boolean isFoodOrder(DriverOrder order) {
        if (order == null) return false;
        String s = (clean(order.serviceName) + " " + (order.raw == null ? "" : order.raw.optString("order_type", ""))).toLowerCase(Locale.US);
        return s.contains("food") || s.contains("makanan") || s.contains("restaurant");
    }

    protected String merchantStatusLabel(String raw) {
        String s = clean(raw).toLowerCase(Locale.US);
        if (s.equals("merchant_accepted") || s.equals("accepted")) return "Diterima merchant";
        if (s.equals("preparing") || s.equals("processing")) return "Sedang disiapkan";
        if (s.equals("ready")) return "Siap diambil";
        if (s.equals("merchant_rejected") || s.equals("rejected")) return "Ditolak merchant";
        return raw;
    }

    protected String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) {
            String c = clean(value);
            if (!c.isEmpty() && !"null".equalsIgnoreCase(c)) return c;
        }
        return "";
    }

    protected String customerNote(JSONObject raw) {
        if (raw == null) return "";

        String direct = firstNonEmpty(
                raw.optString("customer_note", ""),
                raw.optString("note_customer", ""),
                raw.optString("order_note", ""),
                raw.optString("special_instructions", ""),
                raw.optString("instructions", "")
        );
        if (!direct.isEmpty()) return direct;

        String note = clean(raw.optString("note", ""));
        if (note.isEmpty() || "-".equals(note)) return "";

        if (note.startsWith("{")) {
            try {
                JSONObject parsed = new JSONObject(note);
                return firstNonEmpty(
                        parsed.optString("customer_note", ""),
                        parsed.optString("note_customer", ""),
                        parsed.optString("text", ""),
                        parsed.optString("note", ""),
                        parsed.optString("special_instructions", ""),
                        parsed.optString("instructions", ""),
                        parsed.optString("message", ""),
                        parsed.optString("remark", "")
                );
            } catch (Exception ignored) {
                return "";
            }
        }

        // Jangan menampilkan JSON/array mentah di kartu order.
        if (note.startsWith("[")) return "";
        return note;
    }

    protected String buildAssistantMessage(DriverDashboardState state) {
        if (state == null) return "Belum ada rekomendasi.";
        java.util.LinkedHashSet<String> services = new java.util.LinkedHashSet<>();
        int activeCount = 0;
        int offerCount = 0;

        if (state.activeOrders != null) {
            for (DriverOrder order : state.activeOrders) {
                if (order == null) continue;
                activeCount++;
                services.add(aiServiceLabel(order));
            }
        }
        if (state.offers != null) {
            for (DriverOrder order : state.offers) {
                if (order == null) continue;
                offerCount++;
                services.add(aiServiceLabel(order));
            }
        }

        if (activeCount > 0) {
            String serviceText = joinServices(services);
            return "Terdeteksi " + activeCount + " order aktif"
                    + (serviceText.isEmpty() ? "" : " (" + serviceText + ")")
                    + ". Fokus selesaikan perjalanan dengan aman. "
                    + (offerCount > 0 ? offerCount + " tawaran lain juga terdeteksi." : "Smart Queue aktif kembali setelah order selesai.");
        }
        if (offerCount > 0) {
            String serviceText = joinServices(services);
            return "AI mendeteksi " + offerCount + " tawaran order"
                    + (serviceText.isEmpty() ? "" : " (" + serviceText + ")")
                    + ". Pilih order sesuai kendaraan dan jarak Anda.";
        }
        return first(state.assistantMessage, "Belum ada rekomendasi.");
    }

    protected int effectiveHotspotScore(DriverDashboardState state) {
        if (state == null) return 0;
        int serverScore = Math.max(0, state.hotspotScore);
        int activeCount = state.activeOrders == null ? 0 : state.activeOrders.size();
        int offerCount = state.offers == null ? 0 : state.offers.size();

        // Fallback lokal agar semua jenis order, termasuk TransSend/pickup,
        // ikut terbaca walaupun backend hotspot lama hanya menghitung tabel orders.
        int localScore = Math.min(100, (activeCount * 25) + (offerCount * 18));
        return Math.max(serverScore, localScore);
    }

    protected String effectiveHotspotLevel(DriverDashboardState state, int score) {
        if (score >= 70) return "RAMAI";
        if (score >= 35) return "SEDANG";
        if (score > 0) return "ADA ORDER";
        return state == null ? "NORMAL" : first(state.hotspotLevel, "NORMAL");
    }

    protected String aiServiceLabel(DriverOrder order) {
        if (order == null) return "";
        String raw = first(order.serviceName,
                order.raw == null ? "" : order.raw.optString("order_type"),
                order.raw == null ? "" : order.raw.optString("service_type"),
                order.source).toLowerCase(Locale.US);
        if (raw.contains("pickup") || raw.contains("send")) return "TransSend";
        if (raw.contains("shop") || raw.contains("mart")) return "TransShop";
        if (raw.contains("food")) return "TransFood";
        if (raw.contains("car") || raw.contains("mobil")) return "TransCar";
        if (raw.contains("ride") || raw.contains("bike") || raw.contains("motor")) return "TransRide";
        return clean(order.serviceName);
    }

    protected String joinServices(java.util.LinkedHashSet<String> services) {
        if (services == null || services.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (String service : services) {
            if (clean(service).isEmpty()) continue;
            if (out.length() > 0) out.append(", ");
            out.append(service);
        }
        return out.toString();
    }

    protected String serviceIcon(String service) {
        String s = clean(service).toLowerCase(Locale.US);
        if (s.contains("food")) return "🍜";
        if (s.contains("send")) return "📦";
        if (s.contains("car")) return "🚗";
        if (s.contains("shop") || s.contains("mart")) return "🛍";
        return "🏍";
    }

    protected String serviceSubtitle(String service) {
        String s = clean(service).toLowerCase(Locale.US);
        if (s.contains("food")) return "Ambil pesanan di merchant lalu antar ke customer";
        if (s.contains("send")) return "Ambil paket dari pengirim lalu antar ke penerima";
        if (s.contains("car")) return "Jemput penumpang • layanan mobil Transiva";
        if (s.contains("shop") || s.contains("mart")) return "Belanja titipan customer lalu antar ke tujuan";
        return "Jemput penumpang • layanan motor Transiva";
    }

    protected View orderCard(DriverOrder order, boolean active) {
        LinearLayout card = card();
        boolean queued = !active && order.raw != null && order.raw.optBoolean("queued", false);
        int queuePosition = order.raw == null ? 0 : order.raw.optInt("queue_position", 0);
        int concurrentSlot = order.raw == null ? 0 : order.raw.optInt("concurrent_slot", 0);
        String cardTitle = active ? (concurrentSlot > 0 ? "Order Aktif " + concurrentSlot + "/2" : "Order Aktif") : (queued ? "Order Antrean" : "Tawaran");
        card.addView(text(
                cardTitle + " #" + order.id,
                17, "#0B3A78", true));
        if (queued) {
            add(card, text("Sudah diterima • antrean " + Math.max(1, queuePosition) + ". Selesaikan order aktif terlebih dahulu.",
                    13, "#B45309", true), 0, dp(6), 0, 0);
        }
        String serviceLabel = aiServiceLabel(order);
        String serviceIcon = serviceIcon(serviceLabel);
        TextView serviceBadge = text(serviceIcon + "  " + serviceLabel, 15, "#0B7CFF", true);
        serviceBadge.setPadding(dp(12), dp(8), dp(12), dp(8));
        serviceBadge.setBackground(roundStroke("#EFF6FF", "#BFDBFE", dp(16), 1));
        add(card, serviceBadge, 0, dp(7), 0, 0);

        JSONObject identityRaw = order.raw == null ? new JSONObject() : order.raw;
        boolean familyOrder = "family".equalsIgnoreCase(identityRaw.optString("account_type", "personal"));
        String customerName = firstNonEmpty(identityRaw.optString("customer_name", ""), "Customer");
        String familyName = firstNonEmpty(identityRaw.optString("family_name", ""), customerName);
        String familyRelation = firstNonEmpty(identityRaw.optString("family_relationship", ""), "Keluarga");
        String identityTitle = familyOrder ? "👨‍👩‍👧 Transiva Family" : "👤 Akun Pribadi";
        String identityName = familyOrder ? (familyName + " • " + familyRelation) : customerName;
        LinearLayout identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.VERTICAL);
        identity.setPadding(dp(12), dp(10), dp(12), dp(10));
        identity.setBackground(roundStroke(familyOrder ? "#F5F3FF" : "#F8FAFC", familyOrder ? "#DDD6FE" : "#E2E8F0", dp(14), 1));
        identity.addView(text(identityTitle, 12, familyOrder ? "#6D28D9" : "#475569", true));
        TextView identityPerson = text(identityName, 16, "#0F172A", true);
        identityPerson.setPadding(0, dp(3), 0, 0);
        identity.addView(identityPerson);
        add(card, identity, 0, dp(8), 0, 0);

        TextView serviceExplain = text(serviceSubtitle(serviceLabel), 12, "#64748B", false);
        add(card, serviceExplain, 0, dp(6), 0, 0);
        add(card, text("Penjemputan:\n" + order.pickupAddress,
                13, "#334155", false), 0, dp(8), 0, 0);
        add(card, text("Pengantaran:\n" + order.destinationAddress,
                13, "#334155", false), 0, dp(6), 0, 0);

        String meta = "Pendapatan " + rupiah(order.driverEarning) + " • " + ("balance".equalsIgnoreCase(order.paymentMethod) ? "💳 TransPay" : "💵 Tunai");
        if (!clean(order.pickupDistanceText).isEmpty()) {
            meta += " • " + order.pickupDistanceText;
        }
        add(card, text(meta, 13, "#0F172A", true), 0, dp(8), 0, 0);

        if (!active) {
            double tripKm = order.raw == null ? 0d : Math.max(order.raw.optDouble("distance_km", 0d), order.raw.optDouble("trip_distance_km", 0d));
            double pickupKm = order.raw == null ? 0d : Math.max(order.raw.optDouble("pickup_distance_km", 0d), order.raw.optDouble("driver_distance_km", 0d));
            double basisKm = tripKm > 0d ? tripKm : pickupKm;
            String radar = "📡 Smart Radar";
            if (pickupKm > 0d) radar += " • " + String.format(Locale.US, "%.1f km ke pickup", pickupKm);
            if (tripKm > 0d) radar += " • trip " + String.format(Locale.US, "%.1f km", tripKm);
            if (basisKm > 0d && order.driverEarning > 0) radar += " • ±" + rupiah(Math.round(order.driverEarning / basisKm)) + "/km";
            add(card, text(radar, 12, "#0B7CFF", true), 0, dp(7), 0, 0);
        }

        String customerNote = customerNote(order.raw);
        if (!customerNote.isEmpty()) {
            add(card, text("📝 Catatan customer: " + customerNote,
                    13, "#7C2D12", true), 0, dp(7), 0, 0);
        }

        if (isFoodOrder(order)) {
            JSONObject raw = order.raw == null ? new JSONObject() : order.raw;
            JSONObject food = foodPayload(raw);
            String merchantStatus = firstNonEmpty(raw.optString("merchant_status", ""), food.optString("merchant_status", ""));
            int cookMinutes = raw.optInt("cook_minutes", food.optInt("cook_minutes", 0));
            String restaurant = firstNonEmpty(raw.optString("restaurant_name", ""), raw.optString("merchant_name", ""),
                    food.optString("restaurant_name", ""), food.optString("merchant_name", ""));
            if (!restaurant.isEmpty()) {
                add(card, text("Merchant: " + restaurant, 13, "#334155", true), 0, dp(6), 0, 0);
            }
            if (!merchantStatus.isEmpty()) {
                String kitchen = "Dapur: " + merchantStatusLabel(merchantStatus);
                if (cookMinutes > 0) kitchen += " • ±" + cookMinutes + " menit";
                add(card, text(kitchen, 13, "#B45309", true), 0, dp(5), 0, 0);
            }
        }

        if (!active && order.remainingSeconds >= 0) {
            String key = offerCountdownController.key(order);
            TextView countdown = text("Menghitung…", 14, "#16A34A", true);
            countdown.setGravity(Gravity.CENTER);
            countdown.setMinWidth(dp(112));
            countdown.setPadding(dp(13), dp(7), dp(13), dp(7));

            LinearLayout countdownRow = new LinearLayout(this);
            countdownRow.setGravity(Gravity.END);
            countdownRow.addView(countdown, new LinearLayout.LayoutParams(-2, -2));
            add(card, countdownRow, 0, dp(10), 0, 0);
            offerCountdownController.bindCountdown(key, countdown);
        }

        boolean capacityReached = !active
                && !queued
                && currentState != null
                && currentState.activeOrders != null
                && currentState.activeOrders.size() >= 2;

        Button action = primaryButton(active ? "Lanjutkan Trip" : (queued ? "Menunggu Order Aktif Selesai" : "Ambil Order"));

        if (active) {
            action.setOnClickListener(v -> openTrip(order));
            add(card, action, 0, dp(12), 0, 0);

            if (canDriverCancel(order.status)) {
                Button cancel = dangerOutlineButton("Batalkan Order");
                cancel.setOnClickListener(v -> { if (cancellationController != null) cancellationController.show(order); });
                add(card, cancel, 0, dp(9), 0, 0);
            }
        } else if (queued) {
            action.setEnabled(false);
            add(card, action, 0, dp(12), 0, 0);
        } else if (capacityReached) {
            TextView capacityNotice = text(
                    "Maksimal 2 orderan yang berjalan. Selesaikan salah satu order aktif untuk menerima order ini.",
                    14, "#B45309", true);
            capacityNotice.setPadding(dp(14), dp(12), dp(14), dp(12));
            capacityNotice.setBackground(roundStroke(
                    "#FFF7E6", "#F59E0B", dp(14), 1));
            add(card, capacityNotice, 0, dp(12), 0, 0);
        } else {
            String key = offerCountdownController.key(order);
            offerCountdownController.bindButton(key, action);
            action.setOnClickListener(v -> {
                if (offerCountdownController.remainingMillis(key) <= 0L) {
                    action.setEnabled(false);
                    action.setText("Tawaran berakhir");
                    showMessage("Tawaran order sudah berakhir.");
                    return;
                }
                presenter.acceptOrder(order.id, clean(order.source));
            });
            add(card, action, 0, dp(12), 0, 0);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(7), 0, dp(12));
        card.setLayoutParams(lp);
        return card;
    }
}
