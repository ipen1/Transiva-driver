#!/usr/bin/env python3
"""Print complete failure bodies, not just XML attribute lines."""
from pathlib import Path
import xml.etree.ElementTree as ET


def main():
    files = sorted(Path('app/build/outputs/androidTest-results/connected').rglob('*.xml'))
    failures = 0
    for path in files:
        try:
            root = ET.parse(path).getroot()
        except (ET.ParseError, OSError) as error:
            print(f'Unable to read {path}: {error}')
            continue
        for case in root.iter('testcase'):
            for node in list(case):
                if node.tag not in ('failure', 'error'):
                    continue
                failures += 1
                print(f'\n{path}\n{case.get("classname", "")}.{case.get("name", "")}')
                print(node.get('message', ''))
                print(''.join(node.itertext()))
    print(f'\nXML files: {len(files)}; test failures/errors: {failures}')
    if not files:
        print('No test XML produced. Inspect gradle-test.log and logcat for installation, runner, emulator or process startup failures.')


if __name__ == '__main__':
    main()
