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
if 'connectedDebugAndroidTest' not in workflow: errors.append('Connected test missing')
if 'Clean stale instrumentation harness' not in workflow: errors.append('Stale harness cleanup missing from CI')

# Keep emulator execution shell-free and deterministic.
# Multiline wrapper scripts can be executed by /usr/bin/sh and fail before Gradle starts.
emulator_block = workflow[workflow.find('name: Run connected instrumentation tests'):]
if 'script: ./gradlew connectedDebugAndroidTest --stacktrace' not in emulator_block:
    errors.append('Instrumentation runner must invoke Gradle directly')
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
