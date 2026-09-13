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
if 'retrying once after emulator/app reset' not in workflow: errors.append('Instrumentation retry guard missing')

# reactivecircus/android-emulator-runner invokes `script` through /usr/bin/sh.
# Keep that block POSIX compatible; bash-only `pipefail` exits with status 2 on Ubuntu dash.
emulator_block = workflow[workflow.find('name: Run connected instrumentation tests'):]
if 'set -euo pipefail' in emulator_block:
    errors.append('Emulator script must be POSIX sh compatible (no pipefail)')
for api in ('26','29','31','34','35'):
    if api not in workflow: errors.append('API '+api+' missing from emulator matrix')
if not (root/'app/src/androidTest/java/com/transiva/app/RichNotificationInstrumentedTest.java').exists(): errors.append('Rich instrumentation missing')
if not (root/'app/src/debug/java/com/transiva/app/TestHarnessActivity.java').exists(): errors.append('Debug test harness missing')
if (root/'app/src/androidTest/java/com/transiva/app/TestHarnessActivity.java').exists(): errors.append('Test harness must live in debug target APK, not androidTest APK')
if errors:
    print('STABILITY 4.3 GATE: FAIL'); [print(' - '+e) for e in errors]; sys.exit(1)
print('STABILITY 4.3 GATE: PASS')
