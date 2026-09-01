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

firebase = read(ROOT / 'app/src/main/java/com/transiva/app/TransivaFirebaseService.java')
# Full-screen intent is allowed only for the genuine WebRTC incoming-call path.
if 'android.permission.USE_FULL_SCREEN_INTENT' not in text:
    errors += fail('incoming-call permission USE_FULL_SCREEN_INTENT missing')
if firebase.count('setFullScreenIntent(') != 1:
    errors += fail('expected exactly one call-scoped setFullScreenIntent() usage')
if 'incomingCallNotification' not in firebase or 'canUseFullScreenCallIntent()' not in firebase:
    errors += fail('full-screen intent is not guarded by incoming-call + Android 14 special-access checks')
if '"incoming_call".equalsIgnoreCase' not in firebase:
    errors += fail('incoming-call event guard missing')
if 'transiva_call_channel_v4' not in firebase: errors += fail('call channel was not migrated to audible v4')
for rel in ['app/src/main/java/com/transiva/app/DriverChatRoomActivity.java', 'app/src/main/java/com/transiva/app/DriverTopUpActivity.java']:
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
