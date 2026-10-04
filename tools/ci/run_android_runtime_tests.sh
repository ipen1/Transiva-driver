#!/usr/bin/env bash
# Run while the emulator is alive. Preserve Gradle's result and capture the cause.
set -uo pipefail
report_dir="artifacts/android-runtime"
mkdir -p "$report_dir"
adb shell getprop > "$report_dir/device-properties.txt" 2>&1 || true
adb shell input keyevent 82 >/dev/null 2>&1 || true
adb shell svc power stayon true >/dev/null 2>&1 || true
adb logcat -c || true
adb logcat -v threadtime > "$report_dir/logcat-live.txt" 2>&1 &
logcat_pid=$!
cleanup() {
  adb logcat -d -v threadtime > "$report_dir/logcat-final.txt" 2>&1 || true
  kill "$logcat_pid" 2>/dev/null || true
  wait "$logcat_pid" 2>/dev/null || true
}
trap cleanup EXIT
./gradlew connectedDebugAndroidTest --stacktrace --info --console=plain 2>&1 | tee "$report_dir/gradle-test.log"
gradle_exit=${PIPESTATUS[0]}
python3 tools/ci/print_android_test_failures.py | tee "$report_dir/test-failures.txt"
printf '%s\n' "$gradle_exit" > "$report_dir/gradle-exit-code.txt"
exit "$gradle_exit"
