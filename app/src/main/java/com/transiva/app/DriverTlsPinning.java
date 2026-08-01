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
 * Certificate continuity pinning dua slot untuk transiva.my.id.
 * Rantai sertifikat tetap wajib lolos TrustManager Android. Pin aktif dan cadangan
 * disimpan setelah koneksi HTTPS valid, sehingga rotasi public key tidak mengunci
 * aplikasi secara permanen. Jangan digunakan untuk domain eksternal.
 */
public final class DriverTlsPinning {
    private static final String HOST = "transiva.my.id";
    private static final String PREF = "transiva_driver_tls";
    private static final String ACTIVE = "active_spki";
    private static final String BACKUP = "backup_spki";
    private static final String CHANGED_AT = "changed_at";
    private static final long ROTATION_GRACE_MS = 30L * 24L * 60L * 60L * 1000L;
    private DriverTlsPinning() {}

    public static void verify(Context context, HttpsURLConnection connection) throws Exception {
        if (context == null || connection == null || connection.getURL() == null) return;
        if (!HOST.equalsIgnoreCase(connection.getURL().getHost())) return;
        Certificate[] chain = connection.getServerCertificates();
        if (chain == null || chain.length == 0 || !(chain[0] instanceof X509Certificate)) {
            throw new SSLPeerUnverifiedException("Sertifikat server tidak tersedia");
        }
        String current = spki((X509Certificate) chain[0]);
        SharedPreferences p = context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String active = p.getString(ACTIVE, "");
        String backup = p.getString(BACKUP, "");
        long changedAt = p.getLong(CHANGED_AT, 0L);
        long now = System.currentTimeMillis();
        if (active.isEmpty()) {
            p.edit().putString(ACTIVE, current).putLong(CHANGED_AT, now).apply();
            return;
        }
        if (current.equals(active) || current.equals(backup)) return;
        if (changedAt <= 0L || now - changedAt >= ROTATION_GRACE_MS) {
            p.edit().putString(BACKUP, active).putString(ACTIVE, current).putLong(CHANGED_AT, now).apply();
            return;
        }
        throw new SSLPeerUnverifiedException("Public key server berubah di luar jendela rotasi aman");
    }

    private static String spki(X509Certificate certificate) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getPublicKey().getEncoded());
        return "sha256/" + Base64.encodeToString(digest, Base64.NO_WRAP);
    }
}
