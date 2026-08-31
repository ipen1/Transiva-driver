# Transiva Driver — Play Console declarations (release candidate)

Use this as a submission checklist. The answers must match the exact production binary and public privacy policy.

## 1. Full-screen intent

**Release binary:** does **not** request `USE_FULL_SCREEN_INTENT`.

- Do not request Full-screen intent special access in the Play Console for this release.
- Incoming WebRTC calls use a high-importance `CATEGORY_CALL` heads-up notification and open the call screen only after user interaction with the notification.

## 2. Photos and videos permissions

**Release binary:** does **not** request `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_EXTERNAL_STORAGE`, or `WRITE_EXTERNAL_STORAGE`.

- Do not declare broad Photos/Videos access for this release.
- Chat attachment and top-up proof selection use Android's system document picker (`ACTION_OPEN_DOCUMENT`) and only receive access to the file chosen by the user.

## 3. Background location declaration

Background location remains required because location tracking is a core operational function while a driver is ONLINE / handling a trip.

Suggested short explanation for the declaration form:

> Transiva Driver uses precise location while a driver is ONLINE or completing an active trip so customers can receive current driver position and trip progress. Location may continue to be collected in the background when the driver has intentionally enabled ONLINE mode or has an active trip. Before requesting background location, the app shows a prominent in-app disclosure explaining this use. If background location is denied, the background driver-location service is not started.

Review-video flow to record:
1. Open Transiva Driver and sign in.
2. Tap to change driver status from OFFLINE to ONLINE.
3. Show the prominent background-location disclosure completely.
4. Continue to the Android permission screen and show the requested location permission.
5. Return to the app and show ONLINE status / active driver operation.

Public privacy URL configured by the app:
`https://transiva.my.id/server/privacy.html`

## 4. Overlay / display over other apps

`SYSTEM_ALERT_WINDOW` remains only for the optional driver bubble.

- Bubble is OFF by default for a fresh install.
- Do not present overlay as required for using Transiva Driver.
- Permission is requested only after the driver explicitly enables Bubble in app settings.
- Denial must not block orders, chat, navigation, ONLINE mode, or trips.

## 5. Data Safety form — source-derived checklist

Verify each item against actual server retention and SDK configuration before submitting.

### Data collected/processed by core app flows
- Approximate location — app functionality.
- Precise location, including background use while ONLINE/active trip — app functionality.
- Name / account identifiers — account management and app functionality.
- Phone number and email when present on the driver account — account management / communication.
- User IDs / driver IDs — account and order operation.
- In-app messages — chat functionality.
- Photos/files selected by the driver — chat attachment and transaction-proof functionality; selected through the system picker, not broad gallery access.
- Audio when the driver intentionally uses voice/call features — communication functionality.
- Order/trip information — app functionality.
- Transaction / wallet / deposit / withdrawal information where those features are used — app functionality / fraud prevention.

### SDK / operational data to verify
- Firebase Cloud Messaging device token / app instance identifiers — notifications.
- Firebase Crashlytics crash logs / diagnostics — app stability.
- Firebase Analytics app interactions / diagnostics according to production Analytics configuration.

### Security and disclosure checks
- Use HTTPS for production API communication.
- Ensure the public Privacy Policy describes background location, account/contact information, chat/media/audio, identifiers, Firebase/analytics/crash data, financial/transaction data, retention, security, and deletion/contact process.
- The Data Safety form must match actual production behavior, including third-party SDK behavior.

## 6. Before Production

A candidate is accepted by the project release gate only when:
- `python3 tools/playstore_release_gate.py` passes.
- `./gradlew testDebugUnitTest` passes.
- `./gradlew lintRelease` passes.
- signed `assembleRelease` and `bundleRelease` pass.
- the release merged manifest contains no forbidden full-screen/media permissions.
- the `.aab` passes ZIP/structure validation.
- Play Console Background Location and Data Safety forms are completed from this release's actual behavior.
