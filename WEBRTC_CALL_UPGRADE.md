# Transiva WebRTC Audio Call

Fitur panggilan Driver ↔ Customer sekarang menggunakan audio WebRTC native, bukan dialer.

## Alur

1. Tombol telepon di Chat Room membuka `WebRtcCallActivity`.
2. Backend `webrtc_call.php` memvalidasi order aktif dan pasangan driver/customer.
3. FCM data message mengirim incoming-call ke perangkat lawan.
4. SDP offer/answer dan ICE candidates dipertukarkan lewat signaling HTTPS.
5. Audio berjalan peer-to-peer WebRTC jika jalur langsung tersedia.
6. TURN digunakan sebagai relay bila dikonfigurasi di server.

## Dependency

`io.github.webrtc-sdk:android:144.7559.09`

## TURN

STUN publik sudah tersedia sebagai fallback. Untuk koneksi production yang andal antar operator seluler / CGNAT, pasang TURN (misalnya coturn) lalu buat `server/webrtc_turn_config.php` berdasarkan file contoh di paket server.

## Permission

- INTERNET
- RECORD_AUDIO
- MODIFY_AUDIO_SETTINGS
- USE_FULL_SCREEN_INTENT untuk incoming-call notification

Tidak menggunakan `CALL_PHONE` dan tidak membuka dialer Android.
