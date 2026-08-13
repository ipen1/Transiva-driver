\
#!/usr/bin/env python3
from pathlib import Path
import re, sys

ROOT = Path(__file__).resolve().parents[1]
errors=[]

def must(cond,msg):
    if not cond: errors.append(msg)

manifest=(ROOT/'app/src/main/AndroidManifest.xml').read_text(encoding='utf-8')
controller=(ROOT/'app/src/main/java/com/transiva/app/DriverServiceController.java').read_text(encoding='utf-8')
location=(ROOT/'app/src/main/java/com/transiva/app/LocationService.java').read_text(encoding='utf-8')
fcm=(ROOT/'app/src/main/java/com/transiva/app/TransivaFirebaseService.java').read_text(encoding='utf-8')

must('android:name=".LocationService"' in manifest, 'LocationService missing from Manifest')
for legacy in ('TransivaDriverForegroundService','BackgroundSyncService'):
    block=re.search(r'<service\s+android:name="\.%s"(?P<body>.*?)/>'%legacy, manifest, re.S)
    must(block is not None, f'{legacy} entry missing')
    if block: must('android:enabled="false"' in block.group(0), f'{legacy} must remain disabled')
must('new Intent(app, LocationService.class)' in controller, 'Controller must start LocationService')
must('ACTIVE_INTERVAL = 5000L' in location, 'Active location interval changed from 5s')
must('IDLE_INTERVAL   = 12000L' in location or 'IDLE_INTERVAL = 12000L' in location, 'Idle location interval changed from 12s')
must('transiva_order_channel' in fcm, 'FCM order channel missing')
must('IMPORTANCE_HIGH' in fcm, 'FCM high priority channel missing')
must(not (ROOT/'app/src/main/java/com/transiva/app/DriverLeafletNavigationActivity.java').exists(), 'Legacy Leaflet navigation source still present')

if errors:
    print('PRELAUNCH CHECK: FAIL')
    for e in errors: print(' -', e)
    sys.exit(1)
print('PRELAUNCH CHECK: PASS')
print('Single service owner, 5s/12s location cadence, and HIGH order FCM invariants preserved.')
