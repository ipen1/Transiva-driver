#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 tools/playstore_release_gate.py
./gradlew clean testDebugUnitTest lintRelease
if [[ -z "${KEYSTORE_PASSWORD:-}" || -z "${KEY_ALIAS:-}" || -z "${KEY_PASSWORD:-}" || ! -s app/transiva-release.jks ]]; then
  echo "Release signing secrets/keystore are not present; tests+lint completed, signed bundle skipped."
  echo "For a signed AAB set KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD and place app/transiva-release.jks."
  exit 2
fi
./gradlew assembleRelease bundleRelease
python3 tools/playstore_release_gate.py
unzip -t app/build/outputs/bundle/release/*.aab >/dev/null
echo "FINAL AAB VALIDATION: PASS"
