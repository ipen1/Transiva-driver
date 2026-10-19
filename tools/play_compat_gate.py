#!/usr/bin/env python3
"""Guard release optimization and modern window policy; --artifacts checks real R8 output."""
from pathlib import Path
import argparse
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]

def check(artifacts=False):
    failures = []
    def require(ok, message):
        if not ok: failures.append(message)
    build = (ROOT/'app/build.gradle').read_text()
    require('minifyEnabled true' in build and 'shrinkResources true' in build, 'Release must enable R8 and resource shrinking')
    require('transivaEnableR8' not in build, 'Release optimization must not depend on an opt-in flag')
    ns = '{http://schemas.android.com/apk/res/android}'
    manifest = ET.parse(ROOT/'app/src/main/AndroidManifest.xml')
    for a in manifest.findall('.//activity'):
        require(a.get(ns+'screenOrientation') is None, f'Orientation lock: {a.get(ns+"name")}')
        require(a.get(ns+'resizeableActivity') != 'false', 'Activity must allow resizing')
        require({'orientation','screenSize','smallestScreenSize'}.issubset(set(a.get(ns+'configChanges','').split('|'))), 'Activity must handle window-size changes')
    for p in (ROOT/'app/src/main/java').rglob('*.java'):
        s = p.read_text()
        require(not re.search(r'getWindow\(\)\.(?:setStatusBarColor|setNavigationBarColor)\(',s), f'Deprecated Window color API: {p.name}')
        require('setSystemUiVisibility(' not in s, f'Legacy system UI flags: {p.name}')
    if artifacts:
        mapping = ROOT/'app/build/outputs/mapping/release/mapping.txt'
        require(mapping.is_file(), 'R8 mapping.txt missing')
        if mapping.is_file():
            text = mapping.read_text()
            renamed = re.findall(r'^(com\.transiva\.[^ ]+) -> ([^:]+):$',text,re.M)
            require(any(a != b for a,b in renamed), 'No renamed application classes found in R8 mapping')
        require(any((ROOT/'app/build/outputs/bundle/release').glob('*.aab')), 'Release AAB missing')
    if failures:
        raise SystemExit('\n'.join(['PLAY COMPAT: FAIL']+failures))
    print('PLAY COMPAT: PASS'+(' (R8 mapping and AAB verified)' if artifacts else ' (source policy)'))

if __name__ == '__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--artifacts',action='store_true')
    check(parser.parse_args().artifacts)
