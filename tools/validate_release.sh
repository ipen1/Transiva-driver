#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Same cleanup as CI: the harness belongs only to the debug target APK.
rm -f app/src/androidTest/java/com/transiva/app/TestHarnessActivity.java
python3 tools/call_upgrade_regression.py
python3 tools/playstore_release_gate.py
python3 tools/play_compat_gate.py
./gradlew clean testDebugUnitTest lintRelease
if [[ -z "${KEYSTORE_PASSWORD:-}" || -z "${KEY_ALIAS:-}" || -z "${KEY_PASSWORD:-}" || ! -s app/transiva-release.jks ]]; then
  echo "Release signing secrets/keystore are not present; tests+lint completed, signed bundle skipped."
  echo "For a signed AAB set KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD and place app/transiva-release.jks."
  exit 2
fi
./gradlew assembleRelease bundleRelease
python3 tools/verify_webrtc_jni.py app/build/outputs/mapping/release/mapping.txt
python3 tools/play_compat_gate.py --artifacts
python3 tools/playstore_release_gate.py
unzip -t app/build/outputs/bundle/release/*.aab >/dev/null
echo "FINAL AAB VALIDATION: PASS"
