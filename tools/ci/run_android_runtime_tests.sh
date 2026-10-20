#!/usr/bin/env bash
# Runtime tests remain mandatory. Retry only the known pre-test APK installation timeout once.
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
# Match the pre-emulator compilation. Local ARM devices retain their normal debug ABI set.
abi="$(adb shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r\n')"
gradle_options=()
if [[ "$abi" == x86_64 ]]; then gradle_options+=(-PtransivaCiRuntimeAbi=x86_64); fi
results_dir="app/build/outputs/androidTest-results/connected"
: > "$report_dir/gradle-test.log"
gradle_exit=1
for attempt in 1 2; do
  attempt_log="$report_dir/gradle-test-attempt-$attempt.log"
  # Gradle must create fresh reports, never reuse another attempt's passing XML.
  rm -rf "$results_dir"
  ./gradlew connectedDebugAndroidTest --stacktrace --info --console=plain "${gradle_options[@]}" 2>&1 | tee "$attempt_log"
  gradle_exit=${PIPESTATUS[0]}
  cat "$attempt_log" >> "$report_dir/gradle-test.log"
  if [[ "$gradle_exit" -eq 0 ]]; then break; fi
  if [[ "$attempt" -ne 1 ]] || ! python3 tools/ci/android_runtime_result.py retryable --results "$results_dir" --log "$attempt_log"; then break; fi
  echo "Retrying one APK-install timeout; no instrumentation tests ran."
  if [[ -d "$results_dir" ]]; then cp -a "$results_dir" "$report_dir/install-timeout-results"; fi
  adb logcat -d -v threadtime > "$report_dir/logcat-install-timeout.txt" 2>&1 || true
  # A hung/offline device is a real CI failure. Do not repeatedly rerun against it.
  if ! timeout 30s adb shell pm path android > "$report_dir/package-manager-health.txt" 2>&1; then break; fi
  if ! grep -q '^package:' "$report_dir/package-manager-health.txt"; then break; fi
done
python3 tools/ci/print_android_test_failures.py | tee "$report_dir/test-failures.txt"
report_exit=${PIPESTATUS[0]}
if [[ "$gradle_exit" -eq 0 ]]; then
  python3 tools/ci/android_runtime_result.py require-tests --results "$results_dir" || gradle_exit=1
fi
if [[ "$report_exit" -ne 0 ]]; then gradle_exit=1; fi
printf '%s\n' "$gradle_exit" > "$report_dir/gradle-exit-code.txt"
exit "$gradle_exit"
