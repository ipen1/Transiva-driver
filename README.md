TRANSIVA DRIVER FIX — API 36 + OVERLAY UX + TESTS + MAPLIBRE PRIMARY

File yang diubah/ditambahkan:
1. build.gradle
   - Android Gradle Plugin 8.10.1.
2. app/build.gradle
   - compileSdk 36, targetSdk 36, minSdk tetap 23.
   - JUnit 4.13.2.
3. .github/workflows/build-aab.yml
   - Gradle 8.11.1.
   - testDebugUnitTest wajib lulus sebelum release APK/AAB.
4. app/src/main/java/com/transiva/app/SplashActivity.java
   - Tidak meminta izin overlay saat startup.
5. app/src/main/java/com/transiva/app/DriverSettingsActivity.java
   - Overlay menjadi opsi sadar di Pengaturan Driver > Panggilan Masuk.
6. app/src/main/java/com/transiva/app/DriverTripActivity.java
   - Jalur utama navigasi selalu DriverNavigationActivity (MapLibre).
   - Fallback web Transiva dihapus; Google Navigation hanya fallback darurat bila Activity native gagal.
7. app/src/main/AndroidManifest.xml
   - DriverLeafletNavigationActivity tidak lagi didaftarkan sebagai Activity aktif.
8. app/src/test/java/com/transiva/app/DriverMessageStatusTest.java
   - Regression tests untuk pending, accepted, trip aktif, completed/cancelled, normalisasi status, TransFood dan TransRide.

Tidak diubah:
- applicationId
- minSdk
- endpoint API
- signing configuration
- Firebase/FCM
- WebRTC signaling
- foreground/background service
- logika update status perjalanan
- layout dan renderer MapLibre DriverNavigationActivity

Cara pakai:
Ekstrak ZIP ini ke root repository Transiva Driver dan izinkan replace file.
