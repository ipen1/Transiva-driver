#!/usr/bin/env python3
from pathlib import Path
import sys, zipfile, re
import xml.etree.ElementTree as ET

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

# Source contract for the shared background incoming-call implementation. Scan
# production call sites too, so an unrelated notification cannot acquire FSI.
android_ns = '{http://schemas.android.com/apk/res/android}'

def compact_java(source):
    # Preserve quoted text; comments must never satisfy a safety requirement.
    token = r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*.*?\*/'
    source = re.sub(token, lambda m: '' if m.group().startswith(('//', '/*')) else m.group(), source, flags=re.S)
    return re.sub(r'\s+', '', source)

def validate_call_sources(firebase, notifier, ringing, production):
    issues = 0
    firebase, notifier, ringing, production = map(compact_java, (firebase, notifier, ringing, production))
    def need(source, token, label):
        nonlocal issues
        if token not in source:
            issues += fail(label)
    if production.count('.setFullScreenIntent(') != 1 or notifier.count('.setFullScreenIntent(') != 1:
        issues += fail('expected exactly one FSI call site, owned by IncomingCallNotifier')
    for token in ['booleanfullscreen=Build.VERSION.SDK_INT<34;', 'if(Build.VERSION.SDK_INT>=34)try{',
                  'fullscreen=n.canUseFullScreenIntent();', 'catch(RuntimeExceptiondenied){fullscreen=false;}',
                  'if(fullscreen)b.setFullScreenIntent(view,true);']:
        need(notifier, token, 'incoming-call FSI must fail closed behind Android 14 special access')
    need(firebase, 'if("webrtc_call".equals(type)&&"incoming_call".equalsIgnoreCase(first(data.get("event"),""))){IncomingCallNotifier.receive(this,data);return;}',
         'FCM must delegate only genuine incoming WebRTC calls to IncomingCallNotifier')
    for token, label in [
        ('PendingIntentview=PendingIntent.getActivity(c,number,callIntent(c,source),flags);', 'call-specific full-screen/content PendingIntent missing'),
        ('newIntent(c,WebRtcCallActivity.class)', 'explicit call Activity target missing'),
        ('PendingIntent.FLAG_IMMUTABLE', 'immutable call PendingIntents missing'),
        ('putExtra("auto_accept",true)', 'incoming-call accept action missing'),
        ('IncomingCallActionReceiver.ACTION_REJECT', 'incoming-call reject action missing'),
        ('PendingIntentdecline=PendingIntent.getBroadcast(c,number+2,reject,flags);', 'explicit reject broadcast missing'),
        ('NotificationCompat.CallStyle.forIncomingCall(newPerson.Builder().setName(name).setImportant(true).build(),decline,accept)', 'platform incoming CallStyle/actions missing'),
        ('newIntent(c,IncomingCallRingingService.class).putExtras(open)', 'incoming notification must hand off to the ringing service'),
        ('ContextCompat.startForegroundService(c,ring)', 'incoming ringing foreground-service admission missing'),
        ('!post(c,open,false)', 'visible notification before service admission missing'),
        ('!post(c,open,!IncomingCallAlertManager.isRinging(call))', 'audible fallback after service admission failure missing'),
        ('PushExpiryPolicy.remainingMillis(data,System.currentTimeMillis(),true)', 'incoming push expiry validation missing'),
        ('Math.min(45000L,i.getLongExtra(DEADLINE,0)-SystemClock.elapsedRealtime())', 'incoming ring duration must remain bounded by its original deadline'),
        ('setTimeoutAfter(Math.max(1,remaining(source)))', 'incoming notification expiry missing'),
        ('Manifest.permission.POST_NOTIFICATIONS', 'notification runtime permission check missing'),
        ('NotificationManager.IMPORTANCE_NONE', 'blocked notification channel check missing'),
    ]:
        need(notifier, token, label)
    # Both forms terminate before notification/service admission. The traced form
    # adds observability only; matching the predicate alone would accept an unsafe
    # branch that logs rejection but continues to post a call notification.
    owner_guards = (
        'if(!validOwner(c,open))return;',
        'if(!validOwner(c,open)){CallDeliveryTrace.record(c,open,"TARGET_REJECTED","SESSION_CHANGED");return;}',
    )
    if not any(guard in notifier for guard in owner_guards):
        issues += fail('incoming session ownership validation missing or does not return')
    for token, label in [
        ('ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE', 'incoming ringing must use shortService, not media FGS'),
        ('IncomingCallAlertManager.start(this,call,remaining)', 'immediate bounded ringtone/vibration missing'),
        ('IncomingCallNotifier.remaining(intent)<=0', 'ringing service must reject expired calls'),
        ('!IncomingCallNotifier.validOwner(this,intent)', 'ringing service must reject a stale session'),
        ('main.postDelayed(this::expire,remaining)', 'ringing service deadline cleanup missing'),
        ('returnSTART_NOT_STICKY;', 'ringing service must not restart expired calls'),
        ('onTimeout(intstartId){expire();}', 'Android 14 short-service timeout cleanup missing'),
        ('onTimeout(intstartId,intfgsType){expire();}', 'Android 15 short-service timeout cleanup missing'),
        ('stopForeground(true)', 'ringing foreground notification cleanup missing'),
        ('IncomingCallAlertManager.stop(call)', 'ringtone cleanup missing'),
    ]:
        need(ringing, token, label)
    for token in ['FOREGROUND_SERVICE_TYPE_MICROPHONE', 'FOREGROUND_SERVICE_TYPE_CAMERA', 'getUserMedia(', 'Camera.open(']:
        if token in ringing:
            issues += fail('incoming ringing service must not acquire camera/microphone before acceptance')
    return issues

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
    expected = {
        'com.transiva.app.LocationService': {'location'},
        'com.transiva.app.WebRtcCallForegroundService': {'microphone', 'camera'},
        'com.transiva.app.IncomingCallRingingService': {'shortService'},
    }
    def qualified(name):
        return 'com.transiva.app' + name if name.startswith('.') else name
    actual = [(qualified(name), kinds, service) for name, kinds, service in fgs]
    if len(actual) != len(expected) or {name for name, _, _ in actual} != set(expected):
        issues += fail(f'{label}: expected exactly the location, active-call and incoming-ringing FGS owners')
    for name, kinds, service in actual:
        if name not in expected or kinds != expected[name]:
            issues += fail(f'{label}: unexpected foreground service/type: {name}: {sorted(kinds)}')
        if name in {'com.transiva.app.WebRtcCallForegroundService', 'com.transiva.app.IncomingCallRingingService'}:
            if service.get(android_ns + 'exported') != 'false' or service.get(android_ns + 'stopWithTask') != 'false':
                issues += fail(f'{label}: {name} must be private and survive task dismissal')
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


def main():
    errors = 0
    manifest = ROOT / 'app/src/main/AndroidManifest.xml'
    text = read(manifest)
    for p in FORBIDDEN:
        if p in text: errors += fail(f'forbidden permission remains in source manifest: {p}')
    for p in REQUIRED:
        if p not in text: errors += fail(f'required driver location permission missing: {p}')
    if 'android.permission.FOREGROUND_SERVICE_DATA_SYNC' in text:
        errors += fail('unused FOREGROUND_SERVICE_DATA_SYNC remains; Play release must use only the declared location, active-call and incoming-ringing owners')
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
    if 'android.permission.USE_FULL_SCREEN_INTENT' not in text:
        errors += fail('incoming-call permission USE_FULL_SCREEN_INTENT missing')
    base = ROOT / 'app/src/main/java/com/transiva/app'
    notifier = read(base / 'IncomingCallNotifier.java') if (base / 'IncomingCallNotifier.java').exists() else ''
    ringing = read(base / 'IncomingCallRingingService.java') if (base / 'IncomingCallRingingService.java').exists() else ''
    production = '\n'.join(read(p) for p in sorted((ROOT / 'app/src/main/java').rglob('*.java')))
    errors += validate_call_sources(firebase, notifier, ringing, production)
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
        return 1
    print('PLAYSTORE RELEASE GATE: PASS')
    print(f'Unit-test classes detected: {len(tests)}')
    print(f'Release AABs validated: {len(aabs)} (0 means run bundleRelease first)')

    return 0


if __name__ == "__main__":
    sys.exit(main())
