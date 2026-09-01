# Background Location / Foreground Service review video

Google Play expects a real Android-device recording of the declared feature. Recommended duration: <= 30 seconds for the background-location declaration.

## Recording preparation
- Install the exact Play release candidate.
- Use a dedicated reviewer driver account.
- Clear app permissions first so the permission flow is visible.
- Start with Driver status OFFLINE.
- Turn on screen recording and keep system permission dialogs visible.

## 25–30 second shot list

**0–3 sec** — Open Transiva Driver and show the Driver dashboard with status **OFFLINE**.

**3–8 sec** — Tap the ONLINE control. Hold long enough for the prominent disclosure to be readable. The disclosure must visibly state that Transiva collects **location** and can use it in the **background / when the app is closed or not in use** for Driver ONLINE / active-trip operation.

**8–13 sec** — Tap **Lanjut** and show the Android foreground location permission prompt. Grant the permission.

**13–19 sec** — Show the second background-location disclosure if it appears, then follow the app into Android Location permission settings and choose **Allow all the time / Izinkan sepanjang waktu** where the Android version presents that option.

**19–23 sec** — Return to Transiva. Show the Driver becoming **ONLINE** and the persistent location/foreground-service notification.

**23–28 sec** — Press Home so Transiva is in the background. Pull down notifications and show that the driver location service remains visibly active while ONLINE.

**28–30 sec** — Return to Transiva and switch **OFFLINE** (if timing permits) to demonstrate that the long-running location operation is tied to the user's operational state.

## Spoken/overlay caption (optional)
"Background location is used only while the driver is intentionally ONLINE or on an active trip, for operational position, nearby-order matching, customer trip progress, and navigation. It is not used for ads."

## Do not submit
- A mockup/slideshow instead of the real app.
- A video that skips the prominent disclosure.
- A video where the permission is already granted and the reviewer cannot see the permission flow.
- A video demonstrating several unrelated background-location features; declare and demonstrate one core feature only: Driver ONLINE/active-trip tracking.
