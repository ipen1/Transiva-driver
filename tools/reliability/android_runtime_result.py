#!/usr/bin/env python3
"""Classify infrastructure failures without converting unexecuted tests into a pass."""
from pathlib import Path
import argparse
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
        if not cases:
            # UTP can put install/device failures in suite output instead of testcase failures.
            for node in root.iter():
                if node.tag in ('system-err','system-out','failure','error'):
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


def main():
    parser=argparse.ArgumentParser();parser.add_argument('mode',choices=['report','retryable','require-tests'])
    parser.add_argument('--results',default='app/build/outputs/androidTest-results/connected')
    parser.add_argument('--log');args=parser.parse_args()
    if args.mode=='retryable':return 0 if args.log and retryable_install_timeout(args.results,args.log) else 1
    r=results(args.results)
    for text in r['unreadable']+r['details']:print(text)
    print(f"XML files: {r['files']}; tests reported: {r['tests']}; failures/errors: {r['failures']}")
    if r['tests']==0:print('NO TESTS EXECUTED: this is not a successful runtime test. Inspect installation/device/runner output.')
    if args.mode=='require-tests':return 0 if r['tests']>0 and r['failures']==0 and not r['unreadable'] else 1
    return 0


if __name__=='__main__':raise SystemExit(main())
