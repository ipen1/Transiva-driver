TRANSIVA DRIVER — STABILITY 4.0 FIX READY REPLACE

Cara pakai:
1. Backup project Driver Anda.
2. Extract ZIP ini di root project Transiva-driver-main dan pilih overwrite/replace.
3. Jangan menghapus file lain yang tidak ada di ZIP patch.
4. Push ke GitHub lalu jalankan workflow build-app.yml.

Target Stability 4.0:
- 100% app-owned URL.openConnection centralization melalui DriverHttpTransport.
- Bearer/device headers hanya dikirim ke host transiva.my.id / subdomain-nya.
- Dashboard refresh/countdown dipisah ke DashboardRefreshController.
- Trip status normalization/label/endpoint dipisah ke DriverOrderStateMachine.
- Navigation threshold/policy dipisah ke NavigationRoutePolicy.
- Regression suite ditambah dan Stability 4.0 gate dijalankan di GitHub Actions.

Status order inti TIDAK DIUBAH:
driver_accepted -> arrived_pickup -> on_delivery -> arrived_delivery -> finished

Validasi lokal:
PASS tools/stability4_gate.py
PASS tools/stability_p0_gate.py
PASS tools/prelaunch_invariant_check.py
PASS tools/playstore_release_gate.py

Catatan:
Gradle full build tidak dapat dijalankan pada environment pembuat patch karena
services.gradle.org tidak dapat diakses. Workflow GitHub Anda sudah dikonfigurasi
untuk menjalankan Stability 4.0 gate sebelum unit test/lint/release build.
