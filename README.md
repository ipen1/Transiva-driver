# Play Store final hardening upgrade

Changes in this package:

1. Removed `USE_FULL_SCREEN_INTENT` and all broad media/storage permissions from the manifest.
2. Incoming WebRTC calls remain high-priority heads-up notifications. Call channel moved to `transiva_call_channel_v4` and is audible because the full-screen Activity no longer supplies the only alert path.
3. Photo/file selection remains through `ACTION_OPEN_DOCUMENT`, so removing broad media access does not remove chat/top-up file selection.
4. Restored a self-contained `gradlew`, `gradlew.bat`, and checksum-pinned wrapper bootstrap JAR. The distribution is pinned to Gradle 8.11.1 SHA-256 `f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6`.
5. Added JUnit tests for order cancellation status invariants, retry/backoff behavior, and Play Store policy invariants.
6. Added `tools/playstore_release_gate.py` and `tools/validate_release.sh`.
7. Upgraded GitHub Actions to run source policy gate, unit tests, `lintRelease`, signed APK/AAB build, merged-manifest check, and AAB structural validation.
8. Added `playstore/PLAY_CONSOLE_DECLARATIONS_FINAL.md` with the remaining manual Play Console declarations.

Important: Play Console declarations cannot be encoded into the APK. They still must be submitted in the developer's Play Console account. The included document is the exact checklist/template for this source candidate.
