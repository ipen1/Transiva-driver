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

def family(name):
    base=ROOT/'app/src/main/java/com/transiva/app'
    stem=name[:-5] if name.endswith('.java') else name
    return '\n'.join(p.read_text(encoding='utf-8', errors='ignore') for p in sorted(base.glob(stem+'*.java')))

fcm=family('TransivaFirebaseService.java')

must('android:name=".LocationService"' in manifest, 'LocationService missing from Manifest')
for legacy in ('TransivaDriverForegroundService','BackgroundSyncService'):
    block=re.search(r'<service\s+android:name="\.%s"(?P<body>.*?)/>'%legacy, manifest, re.S)
    must(block is not None, f'{legacy} entry missing')
    if block: must('android:enabled="false"' in block.group(0), f'{legacy} must remain disabled')
must('new Intent(app, LocationService.class)' in controller, 'Controller must start LocationService')
must('ACTIVE_INTERVAL = 5000L' in location, 'Active location interval changed from 5s')
def location_constant(name):
    match = re.search(r'\b' + re.escape(name) + r'\s*=\s*([0-9_]+)[Lf]\s*;', location)
    return int(match.group(1).replace('_', '')) if match else 0

idle_heartbeat = location_constant('IDLE_HEARTBEAT')
idle_probe_interval = location_constant('IDLE_PROBE_INTERVAL')
idle_gps_window = location_constant('IDLE_GPS_WINDOW')
must(0 < idle_heartbeat <= idle_probe_interval < 300_000,
     'Standby heartbeat and fallback probe must stay within the 5-minute availability window')
must(0 < idle_gps_window < idle_heartbeat and idle_gps_window < idle_probe_interval,
     'Standby GPS probe must be shorter than the heartbeat and probe intervals')
must(location_constant('IDLE_MOVE_METERS') == 75, 'Standby movement threshold changed from 75m')
must('listen(LocationManager.GPS_PROVIDER, ACTIVE_INTERVAL' in location,
     'Active trips must still request GPS at the active interval')
must('listen(LocationManager.PASSIVE_PROVIDER' in location
     and 'guardianHandler.postDelayed(endProbe, IDLE_GPS_WINDOW)' in location
     and re.search(r'endProbe\s*=\s*\(\)\s*->\s*\{\s*if\s*\(!activeTrip\)\s*stopGps\(\)', location),
     'Standby must use passive location and stop its temporary GPS listener')
must('body.put("standby_heartbeat", true)' in location,
     'Standby freshness check must be identified as a heartbeat')
must('transiva_order_channel' in fcm, 'FCM order channel missing')
must('IMPORTANCE_HIGH' in fcm, 'FCM high priority channel missing')
must(not (ROOT/'app/src/main/java/com/transiva/app/DriverLeafletNavigationActivity.java').exists(), 'Legacy Leaflet navigation source still present')

if errors:
    print('PRELAUNCH CHECK: FAIL')
    for e in errors: print(' -', e)
    sys.exit(1)
print('PRELAUNCH CHECK: PASS')
print('Single service owner, 5s active tracking, bounded standby GPS, and HIGH order FCM checks passed.')
