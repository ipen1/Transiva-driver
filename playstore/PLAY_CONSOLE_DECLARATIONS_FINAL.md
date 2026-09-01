# Transiva Driver — Play Console declarations (release candidate)

Package: `com.transiva.driver`  
Target SDK: 36  
Privacy policy URL: `https://transiva.my.id/server/privacy.html`

> IMPORTANT: The text below is designed to match the current production source. Do not change the declared use cases unless the binary changes.

## 1) Background location declaration

### Main purpose of the app
Transiva Driver is the driver-side application for accepting and completing transportation/delivery orders. Drivers intentionally switch ONLINE to receive nearby work and use the app during an active trip.

### One background-location feature to declare
**Driver ONLINE and active-trip location tracking.**

### Paste-ready explanation
Transiva Driver uses precise location while a driver has intentionally enabled ONLINE mode or is completing an active trip. Background location is required so the app can keep the driver's operational position current when the app is minimized or not in use, allowing nearby-order matching, trip progress/location sharing to the customer, and navigation continuity. The app shows a prominent disclosure before requesting location permission. Background location is not used for advertising. The location foreground service is not started when the driver is OFFLINE and has no active trip.

### Prominent disclosure implemented in app
The normal ONLINE flow displays an in-app dialog before the Android location permission prompt. It explicitly contains the terms **location**, **background**, and **when the app is closed/not in use**, explains the visible driver features that require it, and states that it is not used for ads.

### Review video
Use the recording script in `BACKGROUND_LOCATION_VIDEO_SCRIPT.md`. Record the real Android build; do not submit a slideshow or mockup.

## 2) Foreground Service declaration

### Manifest scope for this release
Only one active FGS type is declared: `location`.

Permissions:
- `android.permission.FOREGROUND_SERVICE`
- `android.permission.FOREGROUND_SERVICE_LOCATION`

Service:
- `.LocationService` with `android:foregroundServiceType="location"`

Legacy disabled services no longer declare a `dataSync` FGS type, and the release manifest no longer requests `FOREGROUND_SERVICE_DATA_SYNC`.

### Use case to select
**Location → Background Location Updates / Navigation / ride tracking / vehicle activity tracking** (select the closest wording shown by Play Console).

### Paste-ready functionality description
When a driver intentionally switches ONLINE or has an active trip, Transiva Driver runs a visible location foreground service. It keeps the driver's operational position current for nearby-order matching, customer trip progress, and driving navigation while the app is minimized. A persistent foreground-service notification makes the task user-perceptible.

### User impact if deferred/interrupted
If the service is delayed or interrupted, the driver's position can become stale, nearby orders may be matched incorrectly or late, customer trip tracking can stop updating, and active-trip navigation/location continuity can be degraded. The service therefore runs only during the user-visible operational state (ONLINE or active trip) and stops when the driver is OFFLINE without an active trip.

### FGS video
The same real-device video can demonstrate: driver turns ONLINE → disclosure/permission → persistent location foreground notification → app is sent to background → ONLINE operation remains active. If Play Console requests a separate URL, upload the same recording or a trimmed FGS-specific version.

## 3) Full-Screen Intent declaration

The release binary requests `USE_FULL_SCREEN_INTENT` only for genuine incoming WebRTC calls between a customer and driver.

### Recommended declaration position
Transiva Driver **has an incoming VoIP calling feature**, but its overall main purpose is driver transport/delivery operations. Do not describe Transiva as a general phone/dialer app. If Play Console asks whether full-screen calling is the app's core/main purpose, answer accurately based on the UI wording presented. The app is already designed to work when automatic full-screen access is not granted.

### Paste-ready usage description
Transiva Driver uses full-screen intent only for an incoming customer-to-driver WebRTC voice call that requires immediate driver attention. The notification uses the call category and provides Accept/Reject actions. On Android versions that require special access, the app checks whether full-screen intent is allowed. If access is unavailable, it gracefully falls back to a high-importance incoming-call notification with ringtone/vibration and Accept/Reject actions. Full-screen intent is not used for orders, chat, promotions, wallet events, or other notifications.

### Reviewer test
1. Sign into the supplied driver review account.
2. Ensure notification permission is granted.
3. In Settings, open the incoming-call/full-screen setting if Android 14+ requires user-granted special access.
4. Trigger a customer-to-driver WebRTC call using the supplied reviewer test account/process.
5. Verify Accept/Reject actions and fallback notification behavior.

## 4) Data Safety

Use `DATA_SAFETY_FORM.md` as the source-derived checklist. It covers precise/approximate location, personal/account data, user IDs, chat/media/audio, financial/transaction data, app activity, crash diagnostics, and device/app identifiers from Firebase. Confirm server retention, deletion, and any third-party sharing before pressing Submit.

## 5) Privacy Policy URL

Store listing and App content privacy policy URL:

`https://transiva.my.id/server/privacy.html`

The app links to the same URL from Login and Driver Settings. A release-ready HTML template matching this app is included at `playstore/privacy.html`. Upload/replace it at the URL above before Play review, then verify the URL works without login, redirects, or download prompts.

## 6) App access for reviewers

Use `APP_ACCESS_REVIEWER.md`. Create a dedicated, persistent review account. Do not use a personal driver account and do not require OTP/SMS/device binding that blocks the reviewer. If a PIN is required after login, include the PIN in Play Console instructions.

## 7) Release checks

Before production submission:
- Run `python3 tools/playstore_release_gate.py`.
- Run `./gradlew testDebugUnitTest lintRelease bundleRelease`.
- Verify the merged release manifest has only `foregroundServiceType="location"`.
- Verify the public privacy-policy URL loads without authentication.
- Upload real-device background-location/FGS demonstration video.
- Complete Background Location, FGS, Full-Screen Intent, Data Safety, and App Access forms with the text above.
