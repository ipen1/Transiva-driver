#!/usr/bin/env python3
"""SDK download recovery only; Android tests and emulator-runner are not mocked as passing."""
from pathlib import Path
import contextlib,importlib.util,io,json,os,subprocess,sys,tempfile,unittest
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('prepare_emulator_sdk',Path(__file__).with_name('prepare_emulator_sdk.py'));module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
FAKE=r'''import json,os,sys
from pathlib import Path
state=Path(os.environ['SDK_FAKE_STATE']);count=int(state.read_text())+1 if state.exists() else 1;state.write_text(str(count))
root=Path(next(a.split('=',1)[1] for a in sys.argv if a.startswith('--sdk_root=')))
with Path(os.environ['SDK_FAKE_ROOTS']).open('a') as f:f.write(str(root)+'\n')
assert (root/'licenses/android-sdk-license').read_text()=='already-accepted-by-runner'
assert not (root/'emulator/old-partial').exists()
mode=os.environ.get('SDK_FAKE_MODE','success')
if mode=='license':print('License for package Android Emulator not accepted. Error on ZipFile unknown archive.');sys.exit(1)
if mode=='disk':print('No space left on device. Error on ZipFile unknown archive.');sys.exit(1)
if mode=='unknown':print('Unrecognized SDK manager configuration error');sys.exit(1)
if mode=='always_corrupt' or (mode=='corrupt_once' and count==1):
 (root/'emulator').mkdir();(root/'emulator/old-partial').write_text('bad zip');print('License accepted. Error on ZipFile unknown archive.');sys.exit(1)
if mode=='network_once' and count==1:print('Connection reset by peer');sys.exit(1)
package=root/'emulator';package.mkdir()
(package/'package.xml').write_text('<repository><localPackage path="emulator"><revision><major>37</major></revision></localPackage></repository>')
if mode=='metadata_once' and count==1:(package/'package.xml').write_text('<broken')
binary=package/'emulator';binary.write_text('#!/bin/sh\necho "Android emulator version 37.2.12.0"\n');binary.chmod(0o755)
if mode=='binary_once' and count==1:binary.write_text('#!/bin/sh\nexit 1\n')
if mode=='missing_pulse':binary.write_text('#!/bin/sh\necho "qemu-system-x86_64: error while loading shared libraries: libpulse.so.0: cannot open shared object file: No such file or directory" >&2\nexit 127\n')
if mode=='missing_binary_once' and count==1:binary.unlink()
print('Installed emulator package')
'''

class EmulatorPreparationTest(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory(prefix='sdk-recovery-test-');self.root=Path(self.temp.name);self.sdk=self.root/'sdk';self.sdk.mkdir();(self.sdk/'licenses').mkdir();(self.sdk/'licenses/android-sdk-license').write_text('already-accepted-by-runner')
  self.manager=self.root/'sdkmanager';self.manager.write_text('#!'+sys.executable+'\n'+FAKE);self.manager.chmod(0o755);self.reports=self.root/'reports'
  self.environment={'SDK_FAKE_STATE':str(self.root/'state'),'SDK_FAKE_ROOTS':str(self.root/'roots')}
 def tearDown(self):self.temp.cleanup()
 def run_mode(self,mode):
  with patch.dict(os.environ,{**self.environment,'SDK_FAKE_MODE':mode}),contextlib.redirect_stdout(io.StringIO()):return module.prepare(self.sdk,str(self.manager),self.reports,backoff=0)
 def count(self):return int((self.root/'state').read_text())
 def result(self):return json.loads((self.reports/'sdk-prepare-result.json').read_text())
 def test_success_promotes_verified_package(self):
  self.assertEqual(0,self.run_mode('success'));self.assertEqual(1,self.count());self.assertEqual('ready',self.result()['status']);self.assertTrue((self.sdk/'emulator/package.xml').is_file());self.assertIn('Android emulator version',subprocess.check_output([str(self.sdk/'emulator/emulator'),'-version'],text=True))
 def test_observed_corrupt_archive_uses_fresh_sdk_and_recovers(self):
  self.assertEqual(0,self.run_mode('corrupt_once'));self.assertEqual(2,self.count());roots=(self.root/'roots').read_text().splitlines();self.assertEqual(2,len(set(roots)));self.assertTrue(all(not Path(p).exists() for p in roots));self.assertFalse((self.sdk/'emulator/old-partial').exists())
 def test_persistent_corruption_is_failure_after_three_attempts(self):
  self.assertEqual(1,self.run_mode('always_corrupt'));self.assertEqual(3,self.count());self.assertEqual('failed',self.result()['status']);self.assertFalse(self.result()['tests_started'])
 def test_network_reset_recovers(self):self.assertEqual(0,self.run_mode('network_once'));self.assertEqual(2,self.count())
 def test_license_rejection_does_not_retry(self):self.assertEqual(1,self.run_mode('license'));self.assertEqual(1,self.count())
 def test_disk_failure_does_not_retry(self):self.assertEqual(1,self.run_mode('disk'));self.assertEqual(1,self.count())
 def test_unknown_error_is_not_retried_or_masked(self):self.assertEqual(1,self.run_mode('unknown'));self.assertEqual(1,self.count())
 def test_missing_binary_is_not_a_success(self):self.assertEqual(0,self.run_mode('missing_binary_once'));self.assertEqual(2,self.count())
 def test_broken_binary_is_replaced_before_admission(self):self.assertEqual(0,self.run_mode('binary_once'));self.assertEqual(2,self.count())
 def test_observed_missing_libpulse_stays_failure_and_preserves_existing_emulator(self):
  (self.sdk/'emulator').mkdir();(self.sdk/'emulator/keep').write_text('working')
  self.assertEqual(1,self.run_mode('missing_pulse'));self.assertEqual(1,self.count())
  self.assertEqual('failed',self.result()['status']);self.assertFalse(self.result()['tests_started'])
  self.assertIn('libpulse.so.0',self.result()['reason']);self.assertEqual('working',(self.sdk/'emulator/keep').read_text())
 def test_workflow_installs_and_loads_libpulse_before_executing_emulator(self):
  text=(Path(__file__).resolve().parents[2]/'.github/workflows/build-app.yml').read_text()
  start=text.index('      - name: Install emulator host dependency before SDK verification')
  end=text.index('      - name: Prepare verified emulator SDK with bounded recovery',start)
  block=text[start:end]
  self.assertIn('shell: bash',block);self.assertIn('set -euo pipefail',block)
  self.assertLess(block.index('sudo apt-get update'),block.index('sudo apt-get install --no-install-recommends -y libpulse0'))
  self.assertLess(block.index('sudo apt-get install --no-install-recommends -y libpulse0'),block.index('ctypes.CDLL("libpulse.so.0")'))
  self.assertLess(end,text.index('uses: reactivecircus/android-emulator-runner@'))
 def test_invalid_package_metadata_cannot_be_promoted(self):self.assertEqual(0,self.run_mode('metadata_once'));self.assertEqual(2,self.count())
 def test_failure_preserves_existing_runner_emulator(self):
  (self.sdk/'emulator').mkdir();(self.sdk/'emulator/keep').write_text('working');self.assertEqual(1,self.run_mode('always_corrupt'));self.assertEqual('working',(self.sdk/'emulator/keep').read_text())
 def test_success_preserves_other_sdk_packages(self):
  (self.sdk/'platform-tools').mkdir();(self.sdk/'platform-tools/keep').write_text('adb');self.assertEqual(0,self.run_mode('success'));self.assertEqual('adb',(self.sdk/'platform-tools/keep').read_text());self.assertEqual('already-accepted-by-runner',(self.sdk/'licenses/android-sdk-license').read_text())
 def test_missing_sdkmanager_fails_closed(self):
  with contextlib.redirect_stdout(io.StringIO()):self.assertEqual(1,module.prepare(self.sdk,None,self.reports,backoff=0))
  self.assertEqual('failed',self.result()['status'])
 def test_accepted_license_does_not_block_corrupt_archive_retry(self):self.assertTrue(module.retryable('License accepted. Error on ZipFile unknown archive.'))
 def test_no_generic_retry_of_failures(self):
  for text in ['AssertionError in instrumentation','Emulator did not boot','0 tests executed','permission denied','license not accepted','SDK component unknown']:
   with self.subTest(text=text):self.assertFalse(module.retryable(text))
 def test_verified_promotion_removes_only_owned_backup(self):
  (self.sdk/'emulator').mkdir();(self.sdk/'emulator/old').write_text('old');self.assertEqual(0,self.run_mode('success'));self.assertFalse((self.sdk/'emulator/old').exists());self.assertFalse(list(self.sdk.glob('.transiva-emulator-*')))
 def test_download_timeout_is_bounded_and_can_recover(self):
  original=module.subprocess.run;calls=[]
  def wrapped(command,*args,**kwargs):
   if '--install' in command:
    calls.append(1)
    if len(calls)<3:raise subprocess.TimeoutExpired(command,360)
   return original(command,*args,**kwargs)
  with patch.object(module.subprocess,'run',side_effect=wrapped):self.assertEqual(0,self.run_mode('success'))
  self.assertEqual(3,len(calls));self.assertEqual(3,self.result()['attempts'])
 def test_failed_promotion_rolls_back_the_previous_package(self):
  (self.sdk/'emulator').mkdir();(self.sdk/'emulator/keep').write_text('working');original=Path.rename
  def renamed(path,target):
   if path.name.startswith('.transiva-emulator-') and not path.name.endswith('-previous') and Path(target)==self.sdk/'emulator':raise OSError('simulated commit failure')
   return original(path,target)
  with patch.object(Path,'rename',renamed):self.assertEqual(1,self.run_mode('success'))
  self.assertEqual('working',(self.sdk/'emulator/keep').read_text());self.assertEqual('failed',self.result()['status']);self.assertFalse(list(self.sdk.glob('.transiva-emulator-*')))
 def test_workflow_keeps_all_runtime_apis_and_no_failure_bypass(self):
  source=Path(__file__).resolve().parents[2]/'.github/workflows/build-app.yml';text=source.read_text();self.assertIn('api-level: [24, 26, 29, 31, 34, 35, 36]',text);self.assertLess(text.index('run: python3 tools/ci/prepare_emulator_sdk.py'),text.index('uses: reactivecircus/android-emulator-runner@'));self.assertIn('script: bash tools/ci/run_android_runtime_tests.sh',text);self.assertNotIn('continue-on-error: true',text)

if __name__=='__main__':unittest.main(verbosity=2)
