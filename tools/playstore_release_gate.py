#!/usr/bin/env python3
from pathlib import Path
import sys, zipfile, glob

ROOT = Path(__file__).resolve().parents[1]
FORBIDDEN = [
    'android.permission.READ_MEDIA_IMAGES',
    'android.permission.READ_MEDIA_VIDEO',
    'android.permission.READ_EXTERNAL_STORAGE',
    'android.permission.WRITE_EXTERNAL_STORAGE',
]
REQUIRED = [
    'android.permission.ACCESS_FINE_LOCATION',
    'android.permission.ACCESS_BACKGROUND_LOCATION',
    'android.permission.FOREGROUND_SERVICE_LOCATION',
]

def fail(msg):
    print('FAIL:', msg)
    return 1

def read(p):
    return p.read_text(encoding='utf-8', errors='replace')

errors = 0
manifest = ROOT / 'app/src/main/AndroidManifest.xml'
text = read(manifest)
for p in FORBIDDEN:
    if p in text: errors += fail(f'forbidden permission remains in source manifest: {p}')
for p in REQUIRED:
    if p not in text: errors += fail(f'required driver location permission missing: {p}')
if 'android.permission.FOREGROUND_SERVICE_DATA_SYNC' in text:
    errors += fail('unused FOREGROUND_SERVICE_DATA_SYNC remains; Play release should declare only active location FGS')
# Validate service types as token sets: Video Call uses microphone + camera.
# Keep the location service and reject unexpected/duplicate FGS declarations.
import xml.etree.ElementTree as ET
android_ns = '{http://schemas.android.com/apk/res/android}'

def validate_foreground_services(manifest_text, label):
    issues = 0
    try:
        root = ET.fromstring(manifest_text)
    except ET.ParseError as exc:
        return fail(f'{label}: invalid AndroidManifest.xml: {exc}')
    permissions = {p.get(android_ns + 'name', '') for p in root.findall('uses-permission')}
    fgs = []
    for service in root.findall('./application/service'):
        kind = service.get(android_ns + 'foregroundServiceType', '')
        if not kind:
            continue
        tokens = [part.strip() for part in kind.split('|')]
        if any(not token for token in tokens) or len(tokens) != len(set(tokens)):
            issues += fail(f'{label}: malformed or duplicate foreground service type: {kind}')
        fgs.append((service.get(android_ns + 'name', ''), set(tokens), service))
    location = [entry for entry in fgs if entry[1] == {'location'}]
    calls = [entry for entry in fgs if entry[0] in {
        '.WebRtcCallForegroundService', 'com.transiva.app.WebRtcCallForegroundService'
    }]
    if len(location) != 1 or len(calls) != 1 or len(fgs) != 2 or calls[0][1] != {'microphone', 'camera'}:
        issues += fail(f'{label}: expected one location FGS and WebRtcCallForegroundService with microphone|camera only')
    if len(calls) == 1:
        service = calls[0][2]
        if service.get(android_ns + 'exported') != 'false' or service.get(android_ns + 'stopWithTask') != 'false':
            issues += fail(f'{label}: call service must be private and survive task dismissal')
    for permission in [
        'android.permission.FOREGROUND_SERVICE',
        'android.permission.FOREGROUND_SERVICE_LOCATION',
        'android.permission.FOREGROUND_SERVICE_MICROPHONE',
        'android.permission.RECORD_AUDIO',
        'android.permission.FOREGROUND_SERVICE_CAMERA',
        'android.permission.CAMERA',
    ]:
        if permission not in permissions:
            issues += fail(f'{label}: required foreground-call/location permission missing: {permission}')
    return issues

errors += validate_foreground_services(text, 'source manifest')

def java_family(stem):
    base=ROOT/'app/src/main/java/com/transiva/app'
    return '\n'.join(read(p) for p in sorted(base.glob(stem+'*.java')))

dashboard = java_family('DriverDashboardActivity')
for phrase in ['data lokasi presisi', 'latar belakang', 'aplikasi ditutup atau tidak sedang digunakan', 'tidak digunakan untuk iklan']:
    if phrase not in dashboard:
        errors += fail(f'background-location prominent disclosure missing required concept: {phrase}')
for rel in ['playstore/PLAY_CONSOLE_DECLARATIONS_FINAL.md','playstore/BACKGROUND_LOCATION_VIDEO_SCRIPT.md','playstore/DATA_SAFETY_FORM.md','playstore/APP_ACCESS_REVIEWER.md','playstore/privacy.html']:
    if not (ROOT/rel).exists(): errors += fail(f'missing Play submission artifact: {rel}')

firebase = java_family('TransivaFirebaseService')
# Full-screen intent is allowed only for the genuine WebRTC incoming-call path.
if 'android.permission.USE_FULL_SCREEN_INTENT' not in text:
    errors += fail('incoming-call permission USE_FULL_SCREEN_INTENT missing')
if firebase.count('setFullScreenIntent(') != 1:
    errors += fail('expected exactly one call-scoped setFullScreenIntent() usage')
if 'incomingCallNotification' not in firebase or 'canUseFullScreenCallIntent()' not in firebase:
    errors += fail('full-screen intent is not guarded by incoming-call + Android 14 special-access checks')
if '"incoming_call".equalsIgnoreCase' not in firebase:
    errors += fail('incoming-call event guard missing')
if 'transiva_call_channel_v6' not in firebase: errors += fail('call channel was not migrated to managed-ringing v6')
if 'IncomingCallAlertManager.start(this, callNotificationId)' not in firebase: errors += fail('incoming call does not start immediate ringtone/vibration manager')
if 'IncomingCallActionReceiver.ACTION_REJECT' not in firebase: errors += fail('incoming call notification reject action missing')
if 'NotificationCompat.CallStyle.forIncomingCall' not in firebase: errors += fail('incoming call does not use platform CallStyle')
if 'fullScreenPendingIntent' not in firebase: errors += fail('dedicated full-screen PendingIntent missing')
if 'acceptIntent.putExtra("auto_accept", true)' not in firebase: errors += fail('incoming call notification accept action missing')
if 'ACTION_OPEN_DOCUMENT' not in java_family('DriverChatRoomActivity'): errors += fail('DriverChatRoomActivity family is not using the system document picker')
rel='app/src/main/java/com/transiva/app/DriverTopUpActivity.java'
if 'ACTION_OPEN_DOCUMENT' not in read(ROOT / rel): errors += fail(f'{rel} is not using the system document picker')

for rel in ['gradlew','gradlew.bat','gradle/wrapper/gradle-wrapper.jar','gradle/wrapper/gradle-wrapper.properties']:
    if not (ROOT/rel).exists(): errors += fail(f'missing Gradle wrapper component: {rel}')
props = read(ROOT/'gradle/wrapper/gradle-wrapper.properties')
if 'distributionSha256Sum=' not in props: errors += fail('Gradle distribution checksum pin missing')

tests = list((ROOT/'app/src/test/java').rglob('*Test.java')) if (ROOT/'app/src/test/java').exists() else []
if len(tests) < 3: errors += fail('expected at least 3 unit-test classes')

# If a release build has already been produced, validate the built merged manifest too.
merged = list(ROOT.glob('app/build/intermediates/**/merged_manifests/**/AndroidManifest.xml'))
for m in merged:
    mt = read(m)
    if 'release' in str(m).lower():
        errors += validate_foreground_services(mt, f'merged manifest {m.relative_to(ROOT)}')
        for p in FORBIDDEN:
            if p in mt: errors += fail(f'forbidden permission merged back into release manifest: {p}')

# If AAB exists, verify it is structurally readable and contains the expected bundle entries.
aabs = list(ROOT.glob('app/build/outputs/bundle/release/*.aab'))
for aab in aabs:
    try:
        with zipfile.ZipFile(aab) as z:
            bad = z.testzip()
            if bad: errors += fail(f'AAB zip corruption at {bad}')
            names=set(z.namelist())
            for required in ['BundleConfig.pb','base/manifest/AndroidManifest.xml']:
                if required not in names: errors += fail(f'AAB missing {required}')
            if not any(n.startswith('base/dex/classes') and n.endswith('.dex') for n in names):
                errors += fail('AAB contains no base dex classes')
    except Exception as e:
        errors += fail(f'cannot validate AAB {aab}: {e}')

if errors:
    print(f'PLAYSTORE RELEASE GATE: FAIL ({errors} issue(s))')
    sys.exit(1)
print('PLAYSTORE RELEASE GATE: PASS')
print(f'Unit-test classes detected: {len(tests)}')
print(f'Release AABs validated: {len(aabs)} (0 means run bundleRelease first)')
