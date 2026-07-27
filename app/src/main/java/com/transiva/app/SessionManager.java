package com.transiva.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Locale;

/**
 * SessionManager.java - Transiva Clean Native Session Guard
 *
 * Build Fix:
 * - Mendukung saveSession(String)
 * - Mendukung saveUser(JSONObject)
 * - Mendukung markLoggedOut(String)
 * - Mendukung forceLogout(String)
 *
 * Jadi aman untuk LoginActivity, SplashActivity, NativeSessionGuard,
 * BootReceiver, FCM, dan Service lama.
 */
public class SessionManager {

    private static final String PREF_NAME = "transiva_native_session";
    private static final String LEGACY_PREF_NAME = "transiva";

    private static final long MAX_SESSION_AGE_MS =
            1000L * 60L * 60L * 24L * 30L;

    private final Context appContext;
    private final SharedPreferences prefs;
    private final SharedPreferences legacyPrefs;

    public SessionManager(Context context) {
        appContext = context.getApplicationContext();

        prefs = appContext.getSharedPreferences(
                PREF_NAME,
                Context.MODE_PRIVATE
        );

        legacyPrefs = appContext.getSharedPreferences(
                LEGACY_PREF_NAME,
                Context.MODE_PRIVATE
        );

        prepareFreshStateIfNeeded();
    }

    public boolean saveSession(String json) {
        try {
            JSONObject root = new JSONObject(
                    json == null ? "{}" : json
            );

            JSONObject user;

            if (root.has("user") && root.optJSONObject("user") != null) {
                user = root.optJSONObject("user");
            } else {
                user = root;
            }

            return saveUser(user);

        } catch (Exception e) {
            markLoggedOut("save_session_error");
            return false;
        }
    }

    /**
     * Method kompatibel untuk LoginActivity lama:
     * session.saveUser(user);
     */
    public boolean saveUser(JSONObject user) {
        try {
            JSONObject clean = normalizeUser(user);

            if (!isValidUserObject(clean)) {
                markLoggedOut("invalid_user_payload");
                return false;
            }

            String role = clean.optString("role", "customer");

            SharedPreferences.Editor e = prefs.edit();

            e.putBoolean("logged_in", true);
            e.putBoolean("native_logged_in", true);

            e.putString("session_state", "active");
            e.putString("native_session_state", "active");

            e.putString("session_message", "Session aktif");
            e.putString("native_session_message", "Session aktif");

            e.putString("raw_user", clean.toString());
            e.putString("raw_session", clean.toString());

            e.putString("id", clean.optString("id", ""));
            e.putString("user_id", clean.optString("user_id", ""));
            e.putString("username", clean.optString("username", ""));
            e.putString("name", clean.optString("name", ""));
            e.putString("role", role);
            e.putString("phone", clean.optString("phone", ""));
            e.putString("token", clean.optString("token", ""));
            e.putString("restaurant_id", clean.optString("restaurant_id", ""));
            e.putString("balance", clean.optString("balance", "0"));
            e.putString("driver_type", clean.optString("driver_type", "bike"));
            e.putString("photo", clean.optString("photo", ""));
            e.putString("driver_photo", clean.optString("driver_photo", ""));
            e.putString("plate", clean.optString("plate", ""));
            e.putString("verification_status", clean.optString("verification_status", ""));
            e.putString("driver_is_online", clean.optString("is_online", "0"));
            e.putString("driver_is_busy", clean.optString("is_busy", "0"));

            long now = System.currentTimeMillis();

            e.putLong("saved_at", now);
            e.putLong("last_seen_at", now);
            e.putLong("logout_at", 0L);

            e.apply();

            syncLegacyFlagsAfterLogin(role);

            return true;

        } catch (Exception e) {
            markLoggedOut("save_user_error");
            return false;
        }
    }

    public boolean isLoggedIn() {
        try {
            boolean loggedIn =
                    prefs.getBoolean("logged_in", false)
                            || prefs.getBoolean("native_logged_in", false);

            if (!loggedIn) {
                return false;
            }

            long savedAt = prefs.getLong("saved_at", 0L);

            if (savedAt <= 0L) {
                return false;
            }

            long age = System.currentTimeMillis() - savedAt;

            if (age < 0L || age > MAX_SESSION_AGE_MS) {
                markLoggedOut("session_expired");
                return false;
            }

            String id = safe(getId());
            String username = safe(getUsername());
            String role = normalizeRole(getRole());

            if (id.isEmpty() && username.isEmpty()) {
                return false;
            }

            return isKnownRole(role);

        } catch (Exception e) {
            return false;
        }
    }

    public boolean canRunNativeServices() {
        if (!isLoggedIn()) {
            return false;
        }

        String role = normalizeRole(getRole());

        return role.equals("driver")
                || role.equals("merchant")
                || role.equals("admin")
                || role.equals("wisata");
    }

    public boolean canRunDriverLocation() {
        return isLoggedIn()
                && normalizeRole(getRole()).equals("driver");
    }

    public void touchSession() {
        if (!isLoggedIn()) {
            return;
        }

        prefs.edit()
                .putLong("last_seen_at", System.currentTimeMillis())
                .putString("session_state", "active")
                .putString("native_session_state", "active")
                .putString("session_message", "Session aktif")
                .putString("native_session_message", "Session aktif")
                .apply();
    }

    public void logout() {
        markLoggedOut("manual_logout");
    }

    public void clearSession() {
        markLoggedOut("manual_logout");
    }

    /**
     * Method kompatibel untuk NativeSessionGuard lama:
     * sessionManager.markLoggedOut(reason);
     */
    public void markLoggedOut(String reason) {
        forceLogout(reason);
    }

    public void forceLogout(String reason) {
        try {
            String fcmToken = prefs.getString("fcm_token", "");
            long fcmSavedAt = prefs.getLong("fcm_token_saved_at", 0L);

            SharedPreferences.Editor e = prefs.edit().clear();

            e.putBoolean("logged_in", false);
            e.putBoolean("native_logged_in", false);

            e.putString("session_state", "logged_out");
            e.putString("native_session_state", "logged_out");

            e.putString("session_message", safe(reason));
            e.putString("native_session_message", safe(reason));

            e.putLong("logout_at", System.currentTimeMillis());

            if (!fcmToken.isEmpty()) {
                e.putString("fcm_token", fcmToken);
                e.putLong("fcm_token_saved_at", fcmSavedAt);
            }

            e.apply();

            clearLegacyOnlineFlags();
            TransivaSession.logout(appContext, safe(reason));

        } catch (Exception ignored) {
        }
    }

    public JSONObject getSessionJson() {
        try {
            JSONObject obj = new JSONObject();

            boolean loggedIn = isLoggedIn();

            obj.put("success", loggedIn);
            obj.put("logged_in", loggedIn);
            obj.put("native_logged_in", loggedIn);

            obj.put("id", getId());
            obj.put("user_id", getUserId());
            obj.put("username", getUsername());
            obj.put("name", getName());
            obj.put("role", getRole());
            obj.put("phone", getPhone());
            obj.put("token", getToken());
            obj.put("restaurant_id", getRestaurantId());
            obj.put("balance", getBalance());
            obj.put("driver_type", getDriverType());
            obj.put("photo", getPhoto());
            obj.put("driver_photo", getDriverPhoto());
            obj.put("plate", get("plate"));
            obj.put("verification_status", get("verification_status"));
            obj.put("is_online", get("driver_is_online"));
            obj.put("is_busy", get("driver_is_busy"));

            obj.put("saved_at", prefs.getLong("saved_at", 0L));
            obj.put("last_seen_at", prefs.getLong("last_seen_at", 0L));
            obj.put("logout_at", prefs.getLong("logout_at", 0L));
            obj.put("session_state", prefs.getString("session_state", ""));
            obj.put("session_message", prefs.getString("session_message", ""));

            return obj;

        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public String getSessionString() {
        return getSessionJson().toString();
    }

    private void prepareFreshStateIfNeeded() {
        if (prefs.contains("logged_in")
                || prefs.contains("native_logged_in")) {
            return;
        }

        prefs.edit()
                .putBoolean("logged_in", false)
                .putBoolean("native_logged_in", false)
                .putString("session_state", "fresh_install")
                .putString("native_session_state", "fresh_install")
                .putString("session_message", "Menunggu login")
                .putString("native_session_message", "Menunggu login")
                .apply();

        clearLegacyOnlineFlags();
    }

    private void syncLegacyFlagsAfterLogin(String roleValue) {
        String role = normalizeRole(roleValue);

        /*
         * Login driver tidak boleh langsung dianggap ONLINE.
         * Status online hanya diaktifkan dari DriverDashboardActivity
         * setelah pengguna menekan tombol ONLINE dan izin lokasi tersedia.
         */
        boolean merchantOnline = role.equals("merchant")
                || role.equals("wisata");

        legacyPrefs.edit()
                .putBoolean("driver_online", false)
                .putBoolean("merchant_online", merchantOnline)
                .putString("driver_online_text", "0")
                .putString("merchant_online_text", merchantOnline ? "1" : "0")
                .apply();
    }

    private void clearLegacyOnlineFlags() {
        legacyPrefs.edit()
                .putBoolean("driver_online", false)
                .putBoolean("merchant_online", false)
                .putString("driver_online_text", "0")
                .putString("merchant_online_text", "0")
                .remove("background_sync_running")
                .apply();
    }

    private JSONObject normalizeUser(JSONObject user) throws Exception {
        JSONObject out = new JSONObject(
                user == null ? "{}" : user.toString()
        );

        String id = firstNonEmpty(
                out.optString("id", ""),
                out.optString("user_id", ""),
                out.optString("uid", "")
        );

        String username = firstNonEmpty(
                out.optString("username", ""),
                out.optString("user_name", ""),
                out.optString("name", "")
        );

        String name = firstNonEmpty(
                out.optString("name", ""),
                out.optString("full_name", ""),
                username
        );

        String role = normalizeRole(firstNonEmpty(
                out.optString("role", ""),
                out.optString("user_role", ""),
                "customer"
        ));

        JSONObject nestedProfile = out.optJSONObject("driver_profile");
        if (nestedProfile == null) nestedProfile = out.optJSONObject("profile");
        if (nestedProfile == null) nestedProfile = new JSONObject();

        String driverType = firstNonEmpty(
                out.optString("driver_type", ""),
                nestedProfile.optString("driver_type", ""),
                "bike"
        ).toLowerCase(Locale.US);

        if (!driverType.equals("car")) {
            driverType = "bike";
        }

        out.put("id", id);
        out.put("user_id", firstNonEmpty(
                out.optString("user_id", ""),
                id
        ));
        out.put("username", username);
        out.put("name", name);
        out.put("role", role);
        out.put("driver_type", driverType);

        if (!out.has("phone")) {
            out.put("phone", "");
        }

        String token = firstNonEmpty(
                out.optString("token", ""),
                out.optString("access_token", ""),
                out.optString("auth_token", ""),
                out.optString("api_token", ""),
                out.optString("session_token", "")
        );
        out.put("token", token);

        if (!out.has("restaurant_id")) {
            out.put("restaurant_id", "");
        }

        if (!out.has("balance")) {
            out.put("balance", "0");
        }

        if (!out.has("photo")) {
            out.put("photo", out.optString("driver_photo", ""));
        }

        if (!out.has("driver_photo") || safe(out.optString("driver_photo", "")).isEmpty()) {
            out.put("driver_photo", firstNonEmpty(
                    nestedProfile.optString("driver_photo", ""),
                    nestedProfile.optString("profile_photo", ""),
                    out.optString("photo", "")
            ));
        }
        if (!out.has("plate") || safe(out.optString("plate", "")).isEmpty()) {
            out.put("plate", nestedProfile.optString("plate", ""));
        }
        if (!out.has("verification_status") || safe(out.optString("verification_status", "")).isEmpty()) {
            out.put("verification_status", nestedProfile.optString("verification_status", ""));
        }
        if (!out.has("is_online")) out.put("is_online", nestedProfile.opt("is_online"));
        if (!out.has("is_busy")) out.put("is_busy", nestedProfile.opt("is_busy"));

        return out;
    }

    /**
     * Sinkronkan state driver yang bersumber dari driver_profiles tanpa
     * menimpa token/login utama. Dipanggil setelah dashboard/profile/status API.
     */
    public void updateDriverRuntime(JSONObject driver) {
        if (driver == null) return;
        try {
            SharedPreferences.Editor e = prefs.edit();
            String type = firstNonEmpty(driver.optString("driver_type", ""), getDriverType());
            type = type.toLowerCase(Locale.US);
            if (!"car".equals(type)) type = "bike";
            e.putString("driver_type", type);

            String photo = firstNonEmpty(
                    driver.optString("driver_photo", ""),
                    driver.optString("profile_photo", "")
            );
            if (!photo.isEmpty()) e.putString("driver_photo", photo);
            if (driver.has("plate")) e.putString("plate", safe(driver.optString("plate", "")));
            if (driver.has("verification_status")) {
                e.putString("verification_status", safe(driver.optString("verification_status", "")));
            }
            if (driver.has("is_online")) {
                e.putString("driver_is_online", jsonBooleanText(driver.opt("is_online")));
            }
            if (driver.has("is_busy")) {
                e.putString("driver_is_busy", jsonBooleanText(driver.opt("is_busy")));
            }
            e.putLong("driver_profile_synced_at", System.currentTimeMillis());
            e.apply();
        } catch (Exception ignored) {
        }
    }

    private String jsonBooleanText(Object value) {
        if (value instanceof Boolean) return (Boolean) value ? "1" : "0";
        if (value instanceof Number) return ((Number) value).intValue() != 0 ? "1" : "0";
        String v = safe(String.valueOf(value)).toLowerCase(Locale.US);
        return ("1".equals(v) || "true".equals(v) || "yes".equals(v) || "online".equals(v)) ? "1" : "0";
    }

    private boolean isValidUserObject(JSONObject user) {
        if (user == null) {
            return false;
        }

        String id = safe(user.optString("id", ""));
        String username = safe(user.optString("username", ""));
        String role = normalizeRole(user.optString("role", ""));

        if (id.isEmpty() && username.isEmpty()) {
            return false;
        }

        return isKnownRole(role);
    }

    private boolean isKnownRole(String roleValue) {
        String role = normalizeRole(roleValue);

        return role.equals("customer")
                || role.equals("driver")
                || role.equals("merchant")
                || role.equals("admin")
                || role.equals("wisata");
    }

    public String normalizeRole(String roleValue) {
        if (roleValue == null) {
            return "";
        }

        String role = roleValue.trim().toLowerCase(Locale.US);

        if (role.equals("user")
                || role.equals("pelanggan")
                || role.equals("costumer")
                || role.equals("customer")) {
            return "customer";
        }

        if (role.equals("driver")
                || role.equals("kurir")
                || role.equals("ojek")
                || role.equals("rider")) {
            return "driver";
        }

        if (role.equals("merchant")
                || role.equals("merchen")
                || role.equals("resto")
                || role.equals("restaurant")
                || role.equals("penjual")) {
            return "merchant";
        }

        if (role.equals("admin")
                || role.equals("administrator")
                || role.equals("owner")
                || role.equals("superadmin")) {
            return "admin";
        }

        if (role.equals("wisata")
                || role.equals("wisataowner")
                || role.equals("wisata_owner")
                || role.equals("owner_wisata")) {
            return "wisata";
        }

        return role;
    }

    private String firstNonEmpty(String... values) {
        if (values == null) {
            return "";
        }

        for (String value : values) {
            String clean = safe(value).trim();

            if (!clean.isEmpty()
                    && !clean.equalsIgnoreCase("null")
                    && !clean.equalsIgnoreCase("undefined")) {
                return clean;
            }
        }

        return "";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public String getId() {
        return prefs.getString("id", "");
    }

    public String getUserId() {
        return prefs.getString("user_id", "");
    }

    public String getUsername() {
        return prefs.getString("username", "");
    }

    public String getName() {
        return prefs.getString("name", "");
    }

    public String getRole() {
        return normalizeRole(
                prefs.getString("role", "")
        );
    }

    public String getPhone() {
        return prefs.getString("phone", "");
    }

    public String getToken() {
        String token = firstNonEmpty(
                prefs.getString("token", ""),
                prefs.getString("access_token", ""),
                prefs.getString("auth_token", ""),
                prefs.getString("api_token", ""),
                prefs.getString("session_token", "")
        );

        if (token.isEmpty()) {
            token = tokenFromJson(prefs.getString("raw_user", ""));
        }
        if (token.isEmpty()) {
            token = tokenFromJson(prefs.getString("raw_session", ""));
        }
        if (token.isEmpty()) {
            token = firstNonEmpty(
                    legacyPrefs.getString("token", ""),
                    legacyPrefs.getString("access_token", ""),
                    legacyPrefs.getString("auth_token", ""),
                    legacyPrefs.getString("api_token", ""),
                    legacyPrefs.getString("session_token", "")
            );
        }
        if (token.isEmpty()) {
            token = tokenFromJson(legacyPrefs.getString("user_json", ""));
        }

        if (!token.isEmpty() && !token.equals(prefs.getString("token", ""))) {
            prefs.edit().putString("token", token).apply();
        }
        return token;
    }

    private String tokenFromJson(String json) {
        if (json == null || json.trim().isEmpty()) return "";
        try {
            JSONObject obj = new JSONObject(json);
            if (obj.optJSONObject("user") != null) obj = obj.optJSONObject("user");
            return firstNonEmpty(
                    obj.optString("token", ""),
                    obj.optString("access_token", ""),
                    obj.optString("auth_token", ""),
                    obj.optString("api_token", ""),
                    obj.optString("session_token", "")
            );
        } catch (Exception ignored) {
            return "";
        }
    }

    public String getRestaurantId() {
        return prefs.getString("restaurant_id", "");
    }

    public String getBalance() {
        return prefs.getString("balance", "0");
    }

    public String getDriverType() {
        return prefs.getString("driver_type", "bike");
    }

    public String getPhoto() {
        return prefs.getString("photo", "");
    }

    public String getDriverPhoto() {
        return prefs.getString("driver_photo", "");
    }

    public void put(String key, String value) {
        if (key == null || key.trim().isEmpty()) {
            return;
        }

        prefs.edit()
                .putString(key, safe(value))
                .apply();
    }

    public String get(String key) {
        if (key == null || key.trim().isEmpty()) {
            return "";
        }

        return prefs.getString(key, "");
    }

    public void remove(String key) {
        if (key == null || key.trim().isEmpty()) {
            return;
        }

        prefs.edit()
                .remove(key)
                .apply();
    }

    public void saveFcmToken(String token) {
        prefs.edit()
                .putString("fcm_token", safe(token))
                .putLong("fcm_token_saved_at", System.currentTimeMillis())
                .apply();
    }

    public String getFcmToken() {
        return prefs.getString("fcm_token", "");
    }

    public void saveLastLocation(String latitude, String longitude) {
        prefs.edit()
                .putString("last_latitude", safe(latitude))
                .putString("last_longitude", safe(longitude))
                .putLong("last_location_at", System.currentTimeMillis())
                .apply();
    }

    public JSONObject getLastLocationJson() {
        try {
            JSONObject obj = new JSONObject();

            obj.put("success", true);
            obj.put("latitude", prefs.getString("last_latitude", ""));
            obj.put("longitude", prefs.getString("last_longitude", ""));
            obj.put("saved_at", prefs.getLong("last_location_at", 0L));

            return obj;

        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public String getLastLocationString() {
        return getLastLocationJson().toString();
    }
}
