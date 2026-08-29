package com.transiva.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * Compatibility-only Activity. Tidak didaftarkan di manifest build Google Play.
 * Jika dipanggil dari source lama, hanya mengarahkan pengguna ke Google Play.
 */
@Deprecated
public class UpdateDownloadActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String id = getPackageName();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + id)));
        } catch (Exception ignored) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + id)));
        }
        finish();
    }
}
