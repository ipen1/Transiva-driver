package com.transiva.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLPeerUnverifiedException;

/**
 * Pemantau kontinuitas sertifikat transiva.my.id.
 *
 * HttpsURLConnection sudah melakukan validasi CA + hostname melalui TrustManager
 * Android sebelum method ini dipanggil. Implementasi lama menjadikan SPKI yang
 * tersimpan di SharedPreferences sebagai hard pin dan dapat memutus SEMUA request
 * native ketika sertifikat server/CDN dirotasi, walaupun sertifikat baru sah.
 *
 * Akibat khasnya: login masih berhasil (LoginActivity tidak memakai verifier ini),
 * tetapi dashboard/API native gagal dengan status jaringan 0 dan pesan
 * "Tidak dapat terhubung ke server".
 *
 * Versi ini tetap mencatat active/backup SPKI untuk audit/continuity, tetapi tidak
 * menolak sertifikat baru yang sudah lolos verifikasi TLS Android. Dengan begitu
 * rotasi sertifikat normal tidak mengunci aplikasi produksi.
 */
public final class DriverTlsPinning {
    private static final String HOST = "transiva.my.id";
    private static final String PREF = "transiva_driver_tls";
    private static final String ACTIVE = "active_spki";
    private static final String BACKUP = "backup_spki";
    private static final String CHANGED_AT = "changed_at";

    private DriverTlsPinning() {}

    public static void verify(Context context, HttpsURLConnection connection) throws Exception {
        if (context == null || connection == null || connection.getURL() == null) return;
        if (!HOST.equalsIgnoreCase(connection.getURL().getHost())) return;

        // Bila getServerCertificates() berhasil pada HttpsURLConnection normal,
        // handshake TLS dan verifikasi hostname/CA Android telah berhasil.
        Certificate[] chain = connection.getServerCertificates();
        if (chain == null || chain.length == 0 || !(chain[0] instanceof X509Certificate)) {
            throw new SSLPeerUnverifiedException("Sertifikat server tidak tersedia");
        }

        String current = spki((X509Certificate) chain[0]);
        SharedPreferences p = context.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);

        String active = p.getString(ACTIVE, "");
        String backup = p.getString(BACKUP, "");

        if (current.equals(active) || current.equals(backup)) return;

        SharedPreferences.Editor e = p.edit();
        if (!active.isEmpty()) e.putString(BACKUP, active);
        e.putString(ACTIVE, current);
        e.putLong(CHANGED_AT, System.currentTimeMillis());
        e.apply();
    }

    private static String spki(X509Certificate certificate) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(certificate.getPublicKey().getEncoded());
        return "sha256/" + Base64.encodeToString(digest, Base64.NO_WRAP);
    }
}
