#!/usr/bin/env python3
"""Regression tests for real runner exit codes, install-only retry and fresh XML results."""
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

TOOLS=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('runtime_result',TOOLS/'android_runtime_result.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
TIMEOUT='ShellCommandUnresponsiveException\nFailed to install-write all apks\nFinished 0 tests on emulator\n'
PASS='<testsuite tests="1" failures="0" errors="0"><testcase classname="Host" name="runtime"/></testsuite>'
ZERO='<testsuite tests="0" failures="0" errors="0"><system-err>Failed to install-write all apks</system-err></testsuite>'
FAIL='<testsuite tests="1" failures="1"><testcase name="broken"><failure>real assertion</failure></testcase></testsuite>'


class RuntimeResultsTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
        self.reports=self.root/'reports';self.reports.mkdir();self.log=self.root/'run.log';self.log.write_text(TIMEOUT)
    def tearDown(self):self.temp.cleanup()
    def xml(self,text): (self.reports/'TEST.xml').write_text(text)
    def test_known_zero_test_install_timeout_is_retryable(self):
        self.xml(ZERO);self.assertTrue(module.retryable_install_timeout(self.reports,self.log))
    def test_real_test_failure_is_not_retried(self):
        self.xml(FAIL);self.assertFalse(module.retryable_install_timeout(self.reports,self.log))
    def test_any_executed_test_disables_install_retry(self):
        self.xml(PASS);self.assertFalse(module.retryable_install_timeout(self.reports,self.log))
    def test_missing_zero_test_evidence_disables_retry(self):
        self.log.write_text('ShellCommandUnresponsiveException\nFailed to install-write all apks');self.assertFalse(module.retryable_install_timeout(self.reports,self.log))
    def test_other_install_errors_are_not_retried(self):
        self.xml(ZERO);self.log.write_text('INSTALL_FAILED_VERSION_DOWNGRADE\nFinished 0 tests');self.assertFalse(module.retryable_install_timeout(self.reports,self.log))
    def test_corrupt_xml_is_not_treated_as_zero_success(self):
        self.xml('<broken');self.assertTrue(module.results(self.reports)['unreadable']);self.assertFalse(module.retryable_install_timeout(self.reports,self.log))
    def test_suite_output_contains_install_failure_detail(self):
        self.xml(ZERO);r=module.results(self.reports);self.assertEqual(0,r['tests']);self.assertIn('install-write',''.join(r['details']))
    def test_suite_level_error_prevents_retry(self):
        self.xml('<testsuite tests="0" errors="1"/>');self.assertFalse(module.retryable_install_timeout(self.reports,self.log))


class RuntimeShellTest(unittest.TestCase):
    def run_scenario(self,scenario,health='ok',stale=False):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);ci=root/'tools/ci';ci.mkdir(parents=True)
            for name in ['run_android_runtime_tests.sh','android_runtime_result.py','print_android_test_failures.py']:shutil.copy2(TOOLS/name,ci/name)
            bin=root/'bin';bin.mkdir()
            adb=bin/'adb';adb.write_text('''#!/bin/sh
case "$*" in
 "shell getprop ro.product.cpu.abi") echo x86_64;;
 "shell pm path android") if [ "$FAKE_HEALTH" = ok ]; then echo package:/system/framework/framework-res.apk; else exit 1; fi;;
esac
exit 0
''');adb.chmod(0o755)
            gradle=root/'gradlew';gradle.write_text('''#!/usr/bin/env python3
import os
from pathlib import Path
p=Path('attempt');n=int(p.read_text())+1 if p.exists() else 1;p.write_text(str(n))
reports=Path('app/build/outputs/androidTest-results/connected');reports.mkdir(parents=True,exist_ok=True)
mode=os.environ['FAKE_SCENARIO']
if mode=='fail' or mode=='timeout-then-fail' and n==2:
 (reports/'TEST.xml').write_text(os.environ['FAKE_FAIL']);print('real assertion failure');raise SystemExit(1)
if mode=='timeout' or mode.startswith('timeout-') and n==1:
 (reports/'TEST.xml').write_text(os.environ['FAKE_ZERO']);print(os.environ['FAKE_TIMEOUT']);raise SystemExit(1)
if mode=='zero':
 (reports/'TEST.xml').write_text(os.environ['FAKE_ZERO']);raise SystemExit(0)
if mode=='missing':raise SystemExit(0)
(reports/'TEST.xml').write_text(os.environ['FAKE_PASS']);raise SystemExit(0)
''');gradle.chmod(0o755)
            if stale:
                results=root/'app/build/outputs/androidTest-results/connected';results.mkdir(parents=True);(results/'OLD.xml').write_text(PASS)
            env=dict(os.environ,PATH=str(bin)+os.pathsep+os.environ['PATH'],FAKE_SCENARIO=scenario,FAKE_HEALTH=health,FAKE_FAIL=FAIL,FAKE_ZERO=ZERO,FAKE_TIMEOUT=TIMEOUT,FAKE_PASS=PASS)
            result=subprocess.run(['bash','tools/ci/run_android_runtime_tests.sh'],cwd=root,env=env,capture_output=True,text=True,timeout=20)
            attempts=int((root/'attempt').read_text())
            return result.returncode,attempts,result.stdout+result.stderr
    def test_normal_success_runs_once(self):
        code,n,_=self.run_scenario('pass');self.assertEqual((0,1),(code,n))
    def test_install_timeout_retries_once_then_runs_real_tests(self):
        code,n,_=self.run_scenario('timeout-then-pass');self.assertEqual((0,2),(code,n))
    def test_persistent_install_timeout_remains_failure(self):
        code,n,_=self.run_scenario('timeout');self.assertNotEqual(0,code);self.assertEqual(2,n)
    def test_failing_tests_never_get_retry(self):
        code,n,_=self.run_scenario('fail');self.assertNotEqual(0,code);self.assertEqual(1,n)
    def test_actual_failure_after_retry_remains_failure(self):
        code,n,_=self.run_scenario('timeout-then-fail');self.assertNotEqual(0,code);self.assertEqual(2,n)
    def test_unresponsive_package_manager_does_not_retry(self):
        code,n,_=self.run_scenario('timeout',health='bad');self.assertNotEqual(0,code);self.assertEqual(1,n)
    def test_zero_test_gradle_success_is_rejected(self):
        code,n,_=self.run_scenario('zero');self.assertNotEqual(0,code);self.assertEqual(1,n)
    def test_stale_passing_xml_cannot_mask_missing_results(self):
        code,n,_=self.run_scenario('missing',stale=True);self.assertNotEqual(0,code);self.assertEqual(1,n)


if __name__=='__main__':unittest.main(verbosity=2)
