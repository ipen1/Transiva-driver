#!/usr/bin/env python3
"""Regression checks: reject unsafe mutations of the real incoming-call sources."""
import contextlib
import io
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
import playstore_release_gate as gate

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'app/src/main/java/com/transiva/app'


class ReleaseGateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest = (ROOT / 'app/src/main/AndroidManifest.xml').read_text()
        cls.sources = [
            '\n'.join(p.read_text() for p in sorted(BASE.glob('TransivaFirebaseService*.java'))),
            (BASE / 'IncomingCallNotifier.java').read_text(),
            (BASE / 'IncomingCallRingingService.java').read_text(),
        ]
        cls.other = '\n'.join(p.read_text() for p in sorted((ROOT / 'app/src/main/java').rglob('*.java'))
                              if p.name not in {'IncomingCallNotifier.java', 'IncomingCallRingingService.java'}
                              and not p.name.startswith('TransivaFirebaseService'))

    def call_issues(self, sources=None, extra=''):
        sources = self.sources if sources is None else sources
        with contextlib.redirect_stdout(io.StringIO()):
            return gate.validate_call_sources(*sources, '\n'.join(sources) + self.other + extra)

    def manifest_issues(self, source):
        with contextlib.redirect_stdout(io.StringIO()):
            return gate.validate_foreground_services(source, 'test source/merged manifest')

    def test_current_sources_pass(self):
        self.assertEqual(0, self.call_issues())
        self.assertEqual(0, self.manifest_issues(self.manifest))

    def test_fully_qualified_merged_manifest_passes(self):
        root = ET.fromstring(self.manifest)
        for service in root.findall('./application/service'):
            key = gate.android_ns + 'name'
            if service.get(key, '').startswith('.'):
                service.set(key, 'com.transiva.app' + service.get(key))
        self.assertEqual(0, self.manifest_issues(ET.tostring(root, encoding='unicode')))

    def test_service_mutations_are_rejected(self):
        mutations = [
            ('shortService', 'microphone|camera'),
            ('shortService', 'shortService|microphone'),
            ('shortService', 'shortService|shortService'),
            ('shortService', 'shortService|'),
            ('shortService', ''),
            ('microphone|camera', 'microphone'),
            ('microphone|camera', 'camera'),
            ('.LocationService', '.UnexpectedLocationService'),
            ('.IncomingCallRingingService', '.UnexpectedRingingService'),
            ('android.permission.FOREGROUND_SERVICE_MICROPHONE', 'removed.permission.MIC'),
            ('android.permission.RECORD_AUDIO', 'removed.permission.AUDIO'),
            ('android.permission.FOREGROUND_SERVICE_CAMERA', 'removed.permission.CAM'),
            ('android.permission.CAMERA', 'removed.permission.CAMERA'),
        ]
        for old, new in mutations:
            with self.subTest(old=old, new=new):
                self.assertIn(old, self.manifest)
                self.assertGreater(self.manifest_issues(self.manifest.replace(old, new)), 0)

    def test_private_call_services_cannot_be_exported_or_stop_with_task(self):
        for name in ['.WebRtcCallForegroundService', '.IncomingCallRingingService']:
            for attr, value in [('exported', 'true'), ('stopWithTask', 'true')]:
                with self.subTest(service=name, attr=attr):
                    root = ET.fromstring(self.manifest)
                    service = next(s for s in root.findall('./application/service')
                                   if s.get(gate.android_ns + 'name') == name)
                    service.set(gate.android_ns + attr, value)
                    self.assertGreater(self.manifest_issues(ET.tostring(root, encoding='unicode')), 0)

    def test_extra_or_duplicate_foreground_owner_is_rejected(self):
        for name in ['.AnotherService', '.IncomingCallRingingService']:
            with self.subTest(name=name):
                root = ET.fromstring(self.manifest)
                ET.SubElement(root.find('application'), 'service', {
                    gate.android_ns + 'name': name,
                    gate.android_ns + 'foregroundServiceType': 'shortService',
                })
                self.assertGreater(self.manifest_issues(ET.tostring(root, encoding='unicode')), 0)

    def test_invalid_manifest_fails_closed(self):
        self.assertGreater(self.manifest_issues('<invalid'), 0)

    def test_safety_mutations_are_rejected(self):
        mutations = [
            (0, '"webrtc_call".equals(type)', 'true'),
            (0, '"incoming_call".equalsIgnoreCase', '"order_offer".equalsIgnoreCase'),
            (1, 'boolean fullscreen=Build.VERSION.SDK_INT<34;', 'boolean fullscreen=true;'),
            (1, 'if(Build.VERSION.SDK_INT>=34)try{', 'if(false)try{'),
            (1, 'fullscreen=n.canUseFullScreenIntent();', 'fullscreen=true;'),
            (1, 'fullscreen=false;', 'fullscreen=true;'),
            (1, 'if(fullscreen)b.setFullScreenIntent', 'b.setFullScreenIntent'),
            (1, 'PendingIntent.getActivity(c,number,', 'PendingIntent.getActivity(c,0,'),
            (1, 'WebRtcCallActivity.class', 'DriverDashboardActivity.class'),
            (1, 'PendingIntent.FLAG_IMMUTABLE', 'PendingIntent.FLAG_MUTABLE'),
            (1, 'putExtra("auto_accept",true)', 'putExtra("auto_accept",false)'),
            (1, 'IncomingCallActionReceiver.ACTION_REJECT', '"OTHER_ACTION"'),
            (1, 'PendingIntent.getBroadcast(c,number+2,reject,flags)', 'PendingIntent.getBroadcast(c,0,reject,flags)'),
            (1, 'NotificationCompat.CallStyle.forIncomingCall', 'OtherStyle.forIncomingCall'),
            (1, 'IncomingCallRingingService.class', 'WebRtcCallForegroundService.class'),
            (1, 'ContextCompat.startForegroundService(c,ring)', 'c.startService(ring)'),
            (1, '!post(c,open,false)', 'false'),
            (1, '!post(c,open,!IncomingCallAlertManager.isRinging(call))', 'false'),
            (1, 'PushExpiryPolicy.remainingMillis(data,System.currentTimeMillis(),true)', '45000L'),
            (1, 'if(!validOwner(c,open)){CallDeliveryTrace.record(c,open,"TARGET_REJECTED","SESSION_CHANGED");return;}', ''),
            (1, 'Math.min(45000L,i.getLongExtra(DEADLINE,0)-SystemClock.elapsedRealtime())', '45000L'),
            (1, '.setTimeoutAfter(Math.max(1,remaining(source)))', ''),
            (1, 'Manifest.permission.POST_NOTIFICATIONS', '"removed.permission"'),
            (1, 'NotificationManager.IMPORTANCE_NONE', 'NotificationManager.IMPORTANCE_LOW'),
            (2, 'ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE', 'ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE'),
            (2, 'IncomingCallAlertManager.start(this,call,remaining)', 'IncomingCallAlertManager.start(this,call,45000)'),
            (2, 'IncomingCallNotifier.remaining(intent)<=0', 'false'),
            (2, '!IncomingCallNotifier.validOwner(this,intent)', 'false'),
            (2, 'main.postDelayed(this::expire,remaining)', 'main.postDelayed(this::expire,180000)'),
            (2, 'return START_NOT_STICKY;', 'return START_STICKY;'),
            (2, 'onTimeout(int startId){expire();}', 'onTimeout(int startId){}'),
            (2, 'onTimeout(int startId,int fgsType){expire();}', 'onTimeout(int startId,int fgsType){}'),
            (2, 'stopForeground(true)', 'stopForeground(false)'),
            (2, 'IncomingCallAlertManager.stop(call)', 'unrelatedMethod(call)'),
        ]
        for index, old, new in mutations:
            with self.subTest(index=index, removed=old):
                self.assertIn(old, self.sources[index])
                sources = list(self.sources)
                sources[index] = sources[index].replace(old, new)
                self.assertGreater(self.call_issues(sources), 0)

    def test_owner_guard_requires_early_return(self):
        traced = 'if(!validOwner(c,open)){CallDeliveryTrace.record(c,open,"TARGET_REJECTED","SESSION_CHANGED");return;}'
        self.assertIn(traced, self.sources[1])
        for replacement in [
            traced.replace('return;', ''),
            traced.replace('!validOwner(c,open)', 'validOwner(c,open)'),
            traced.replace('!validOwner(c,open)', 'false'),
            '/* ' + traced + ' */',
        ]:
            with self.subTest(replacement=replacement):
                sources = list(self.sources)
                sources[1] = sources[1].replace(traced, replacement)
                self.assertGreater(self.call_issues(sources), 0)

    def test_legacy_and_formatted_traced_owner_guard_pass(self):
        traced = 'if(!validOwner(c,open)){CallDeliveryTrace.record(c,open,"TARGET_REJECTED","SESSION_CHANGED");return;}'
        self.assertIn(traced, self.sources[1])
        for replacement in [
            'if(!validOwner(c,open))return;',
            traced.replace('if(', 'if /* owner */ (').replace('return;', '\n return ;\n'),
        ]:
            with self.subTest(replacement=replacement):
                sources = list(self.sources)
                sources[1] = sources[1].replace(traced, replacement)
                self.assertEqual(0, self.call_issues(sources))

    def test_comment_does_not_replace_real_guard(self):
        sources = list(self.sources)
        old = 'if(fullscreen)b.setFullScreenIntent(view,true);'
        sources[1] = sources[1].replace(old, '// ' + old)
        self.assertGreater(self.call_issues(sources), 0)

    def test_unrelated_fullscreen_call_is_rejected(self):
        self.assertGreater(self.call_issues(extra='\nbuilder.setFullScreenIntent(orderIntent, true);'), 0)

    def test_comments_do_not_add_fullscreen_calls(self):
        self.assertEqual(0, self.call_issues(extra='\n// builder.setFullScreenIntent(other, true);\n/* .setFullScreenIntent(other, true); */'))

    def test_whitespace_and_comment_formatting_is_supported(self):
        sources = [source.replace('setFullScreenIntent(', 'setFullScreenIntent /* call */ (') for source in self.sources]
        self.assertEqual(0, self.call_issues(sources))

    def test_ringing_cannot_start_media(self):
        for token in ['FOREGROUND_SERVICE_TYPE_CAMERA', 'Camera.open(', 'getUserMedia(']:
            with self.subTest(token=token):
                sources = list(self.sources)
                sources[2] += '\n' + token
                self.assertGreater(self.call_issues(sources), 0)


if __name__ == '__main__':
    unittest.main(verbosity=2)
