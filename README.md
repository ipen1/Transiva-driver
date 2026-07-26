# Transiva Driver Android

Repository khusus aplikasi Driver Transiva.

## Identitas aplikasi
- Application ID: `com.transiva.mobile`
- Java namespace: `com.transiva.app`
- Launcher: `SplashActivity`
- Dashboard utama: `DriverDashboardActivity`

`com.transiva.mobile` dipakai karena sudah terdaftar sebagai Android client di `google-services.json`, sehingga aplikasi Driver dapat dipasang berdampingan dengan aplikasi Customer `com.transiva.app`.

## Driver-only guard
Login menolak akun non-driver. Splash dan PIN juga menghapus sesi non-driver agar Customer/Merchant/Admin tidak dapat masuk lewat session lama.

## GitHub Actions
Workflow saat ini memakai debug signing untuk build release-test agar dapat langsung diuji tanpa keystore produksi. Sebelum rilis publik, ganti signing dengan keystore produksi khusus Transiva Driver dan jangan commit file keystore ke repository.
