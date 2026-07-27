# Transiva Driver Android

Repository Android khusus **Driver Transiva**.

## Identitas aplikasi

- Application ID: `com.transiva.driver`
- Java namespace: `com.transiva.app`
- App name: `Transiva Driver`
- Min SDK: 23
- Target SDK: 35

Namespace Java sengaja tetap `com.transiva.app` agar source stabil. Identitas instalasi Android ditentukan oleh `applicationId`.

## Source yang dipertahankan

Repository ini hanya memuat source Driver dan helper bersama yang memang dibutuhkan Driver:
dashboard, order/trip, navigasi, chat, riwayat, pendapatan, profil, BPJS,
top-up/withdraw, foreground/background location, Firebase notification,
session/PIN/login, dan updater.

Source Java Customer, Merchant, dan Admin telah dihapus dari source aktif.
Login dan session juga dikunci untuk role `driver`.

## Firebase

Sebelum release production:

1. Tambahkan Android app `com.transiva.driver` di Firebase project Transiva.
2. Download `google-services.json` resmi.
3. Replace `app/google-services.json`.

File konfigurasi dalam repository memiliki entry sementara yang cocok dengan
`com.transiva.driver` agar struktur project dapat diproses, tetapi file resmi
Firebase wajib digunakan untuk FCM production.

## GitHub Actions signing

Tambahkan Repository Secrets:

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

Keystore tidak boleh di-commit ke repository.

Lalu buka **Actions → Build Transiva Driver Signed Release → Run workflow**.
