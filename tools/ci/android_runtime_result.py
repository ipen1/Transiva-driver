#!/usr/bin/env python3
"""Classify infrastructure failures without converting unexecuted tests into a pass."""
from pathlib import Path
import argparse
import re
import xml.etree.ElementTree as ET


def results(directory):
    files=sorted(Path(directory).rglob('*.xml'))
    tests=failures=0;unreadable=[];details=[]
    for path in files:
        try:root=ET.parse(path).getroot()
        except (ET.ParseError,OSError) as error:
            unreadable.append(f'{path}: {error}');continue
        cases=list(root.iter('testcase'))
        # Some runners report counts even when testcase elements are missing.
        reported=sum(int(s.get('tests','0')) for s in root.iter('testsuite'))
        tests+=max(len(cases),reported)
        for case in cases:
            for node in case:
                if node.tag in ('failure','error'):
                    failures+=1;details.append(f'{path}\n{case.get("classname","")}.{case.get("name","")}\n{node.get("message","")}\n'+''.join(node.itertext()))
        for suite in root.iter('testsuite'):
            failures=max(failures,int(suite.get('failures','0'))+int(suite.get('errors','0')))
        # UTP can report a device crash in suite stderr even after some tests ran.
        for node in root.iter():
            if node.tag == 'system-err' or (not cases and node.tag in ('system-out','failure','error')):
                body=''.join(node.itertext()).strip()
                if body:details.append(f'{path}\n{node.tag}: {body}')
    return dict(files=len(files),tests=tests,failures=failures,unreadable=unreadable,details=details)


def retryable_install_timeout(directory,log):
    report=results(directory)
    if report['tests'] or report['failures'] or report['unreadable']:return False
    text=Path(log).read_text(encoding='utf-8',errors='replace')
    # The observed API-24 failure: timeout writing the application APK, before runner start.
    return ('Failed to install-write all apks' in text
            and 'ShellCommandUnresponsiveException' in text
            and ('Finished 0 tests' in text or 'Total tests 0' in text))


def retryable_display_crash(directory, logcat):
    files = sorted(Path(directory).rglob('*.xml'))
    if not files:
        return False
    crashed = False
    try:
        for path in files:
            root = ET.parse(path).getroot()
            for node in root.iter():
                text = ''.join(node.itertext()).strip() if node.tag in ('failure', 'error', 'system-err') else ''
                if node.tag in ('failure', 'error') and (
                    node.get('message', '').strip() or node.get('type', '').strip() or text
                ):
                    return False
                if node.tag == 'system-err':
                    ending = re.search(r'Test run failed to complete\. Expected (\d+) tests, received (\d+)\. System has crashed\.', text)
                    if ending and int(ending[2]) < int(ending[1]):
                        crashed = True
        if not crashed:
            return False
        text = Path(logcat).read_text(encoding='utf-8', errors='replace')
    except (ET.ParseError, OSError):
        return False
    # Match the fatal signal and Android tombstone to the same system_server PID.
    signals = set(re.findall(r'^\S+\s+\S+\s+(\d+)\s+\d+\s+F libc\s*:\s*Fatal signal 11 \(SIGSEGV\).*\(android\.display\)', text, re.M))
    tombstones = set(re.findall(r'pid:\s*(\d+),\s*tid:\s*\d+,\s*name:\s*android\.display\s+>>> system_server <<<', text))
    return bool(signals & tombstones)


def main():
    parser=argparse.ArgumentParser();parser.add_argument('mode',choices=['report','retryable','retryable-display-crash','require-tests'])
    parser.add_argument('--results',default='app/build/outputs/androidTest-results/connected')
    parser.add_argument('--log')
    parser.add_argument('--logcat')
    args=parser.parse_args()
    if args.mode=='retryable-display-crash':
        return 0 if args.logcat and retryable_display_crash(args.results,args.logcat) else 1
    if args.mode=='retryable':return 0 if args.log and retryable_install_timeout(args.results,args.log) else 1
    r=results(args.results)
    for text in r['unreadable']+r['details']:print(text)
    print(f"XML files: {r['files']}; tests reported: {r['tests']}; failures/errors: {r['failures']}")
    if r['tests']==0:print('NO TESTS EXECUTED: this is not a successful runtime test. Inspect installation/device/runner output.')
    if args.mode=='require-tests':return 0 if r['tests']>0 and r['failures']==0 and not r['unreadable'] else 1
    return 0


if __name__=='__main__':raise SystemExit(main())
