#!/usr/bin/env python3
from pathlib import Path
import sys,re
root=Path(__file__).resolve().parents[1]
service=(root/'app/src/main/java/com/transiva/app/TransivaFirebaseService.java').read_text()
rich=(root/'app/src/main/java/com/transiva/app/TransivaRichNotificationManager.java')
workflow=(root/'.github/workflows/build-app.yml').read_text()
errors=[]
if not rich.exists(): errors.append('Rich manager missing')
if 'if (TransivaRichNotificationManager.isRichType(type))' not in service or 'return;' not in service[service.find('if (TransivaRichNotificationManager.isRichType(type))'):][:300]: errors.append('Rich FCM path is not isolated')
if 'assembleDebugAndroidTest' not in workflow: errors.append('Instrumentation compile missing')
if 'Clean stale instrumentation harness' not in workflow: errors.append('Stale harness cleanup missing from CI')

# Support direct Gradle and an explicitly invoked Bash diagnostic runner.
emulator_block = workflow[workflow.find('name: Run connected instrumentation tests'):]
direct = 'script: ./gradlew connectedDebugAndroidTest --stacktrace' in emulator_block
wrapped = 'script: bash tools/ci/run_android_runtime_tests.sh' in emulator_block
if wrapped:
    runner_path = root/'tools/ci/run_android_runtime_tests.sh'
    if not runner_path.is_file():
        errors.append('Diagnostic instrumentation runner missing')
    else:
        runner = runner_path.read_text()
        for required in ('set -uo pipefail', './gradlew connectedDebugAndroidTest --stacktrace',
                         'gradle_exit=${PIPESTATUS[0]}', 'exit "$gradle_exit"'):
            if required not in runner:
                errors.append('Diagnostic runner missing required behavior: '+required)
        if not (root/'tools/ci/print_android_test_failures.py').is_file():
            errors.append('Instrumentation failure reporter missing')
if not direct and not wrapped:
    errors.append('Connected test must invoke Gradle directly or the validated Bash diagnostic runner')
if 'script: |' in emulator_block:
    errors.append('Instrumentation runner must not use multiline shell script')
for api in ('26','29','31','34','35'):
    if api not in workflow: errors.append('API '+api+' missing from emulator matrix')
if not (root/'app/src/androidTest/java/com/transiva/app/RichNotificationInstrumentedTest.java').exists(): errors.append('Rich instrumentation missing')
if not (root/'app/src/debug/java/com/transiva/app/TestHarnessActivity.java').exists(): errors.append('Debug test harness missing')
if (root/'app/src/androidTest/java/com/transiva/app/TestHarnessActivity.java').exists(): errors.append('Test harness must live in debug target APK, not androidTest APK')
if errors:
    print('STABILITY 4.3 GATE: FAIL'); [print(' - '+e) for e in errors]; sys.exit(1)
print('STABILITY 4.3 GATE: PASS')
