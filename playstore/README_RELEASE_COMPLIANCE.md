# Transiva Driver Play compliance patch

Changed for Play submission:
- Prominent background-location disclosure aligned with Google Play wording and real ONLINE/active-trip behavior.
- Release manifest narrowed to a single active FGS type: `location`.
- Removed unused `FOREGROUND_SERVICE_DATA_SYNC` permission and disabled-service dataSync type declarations.
- Updated Full-Screen Intent declaration to match the actual incoming WebRTC-call implementation and notification fallback.
- Added real-device background-location/FGS video shot list.
- Added Data Safety worksheet.
- Added reviewer App Access instructions template.
- Added public Privacy Policy HTML template for `https://transiva.my.id/server/privacy.html`.
- Release gate now verifies the prominent disclosure, single location FGS scope, and required Play submission artifacts.

Important: The actual Google Play background-location video must be recorded from the real Android release candidate. Do not submit a generated/mock video.
