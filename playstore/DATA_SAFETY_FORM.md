# Transiva Driver — Data Safety form worksheet

This worksheet is derived from the Android source and included SDKs. Final Play Console answers must also reflect server-side retention/sharing/deletion behavior.

## Top-level answers

**Does the app collect or share any required user data types?** Yes.

**Is all user data encrypted in transit?** The Android production app disables cleartext traffic and its first-party API endpoints use HTTPS. Answer Yes only if every production backend/third-party transfer remains HTTPS/TLS.

**Can users request data deletion?** Confirm the driver-side/server deletion process before submission. Do not answer Yes solely because the Customer app has deletion functionality.

## Data types to declare as collected when used

| Play category | Data type | Why Transiva Driver uses it | Typical purpose |
|---|---|---|---|
| Location | Approximate location | Driver positioning if coarse location is supplied | App functionality |
| Location | Precise location | ONLINE/active trip, nearby orders, trip progress, navigation | App functionality |
| Personal info | Name | Driver profile/account | Account management, app functionality |
| Personal info | Email address | Driver account where configured | Account management |
| Personal info | Phone number | Driver account/communication where configured | Account management, app functionality |
| App info / identifiers | User/driver ID | Authentication, orders, chat, wallet operations | App functionality, fraud/security |
| Messages | Other in-app messages | Driver/customer/global/merchant chat | App functionality |
| Photos and videos | Photos | User-chosen chat images / transaction proof; camera when intentionally used | App functionality |
| Audio files / voice | Voice/audio | Voice notes and WebRTC call audio when user invokes those features | App functionality |
| Financial info | Purchase/transaction information | Driver wallet, earnings, deposit/withdraw/transfer flows | App functionality, fraud/security |
| App activity | App interactions | Firebase Analytics if collection is enabled in production | Analytics |
| App info and performance | Crash logs | Firebase Crashlytics | App functionality, diagnostics |
| App info and performance | Diagnostics | Crashlytics/navigation diagnostics | App functionality, diagnostics |
| Device or other IDs | Device/app identifiers | FCM token, Firebase app instance/analytics identifiers | App functionality, analytics |

## Sharing guidance
Do not automatically mark Firebase/service-provider processing as "shared" without applying Google Play's Data Safety definitions and exemptions. Confirm whether data is transferred to any third party for that third party's own purposes. First-party Transiva server processing still counts as collection even when it is not "sharing."

## Location-specific settings
- Collection is required only when the driver intentionally enables ONLINE mode or has an active trip.
- Background collection is disclosed before permission request.
- Purpose: app functionality.
- Advertising: no, based on current source/disclosure.

## Media permissions
The release manifest does not request broad storage/gallery permissions (`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`). User-selected files use the system picker; camera permission is requested only for the in-app camera flow.

## Security checklist before submitting
- Confirm production API is HTTPS-only.
- Confirm Google Maps key is restricted to package/signing certificate.
- Confirm Firebase/Analytics settings match this form.
- Confirm retention periods for chat, location, diagnostics, and transaction data.
- Confirm driver-account deletion/request process and public contact method.
- Ensure Privacy Policy matches every category declared here.
