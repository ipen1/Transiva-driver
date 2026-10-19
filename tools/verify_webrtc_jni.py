#!/usr/bin/env python3
"""Fail a release if hard-coded native lookup names no longer exist in the R8 mapping."""
from pathlib import Path
import re
import sys

mapping = Path(sys.argv[1] if len(sys.argv) > 1 else 'app/build/outputs/mapping/release/mapping.txt')
if not mapping.is_file():
    raise SystemExit('FAIL: release mapping missing: '+str(mapping))
required = {
    'org.jni_zero.JniInit': {'init', 'crashIfMultiplexingMisaligned'},
    'org.jni_zero.JniUtil': {'mapToArray', 'arrayToMap'},
    'org.webrtc.PeerConnectionFactory': set(),
    'org.webrtc.audio.WebRtcAudioRecord': set(),
    'org.webrtc.audio.WebRtcAudioTrack': set(),
}
found = set()
methods = {name: set() for name in required}
current = None
for line in mapping.open(encoding='utf-8'):
    if not line.startswith((' ', '#')) and ' -> ' in line:
        original, renamed = line.strip().rstrip(':').split(' -> ', 1)
        current = original if original in required else None
        if current:
            if original != renamed:
                raise SystemExit('FAIL: native lookup class renamed: '+original+' -> '+renamed)
            found.add(original)
    elif current and ' -> ' in line and '(' in line:
        before, after = line.strip().rsplit(' -> ', 1)
        match = re.search(r'\b([\w$]+)\(', before)
        if match and match.group(1) in required[current]:
            if match.group(1) != after:
                raise SystemExit('FAIL: native lookup method renamed: '+current+'.'+match.group(1))
            methods[current].add(after)
missing = set(required)-found
if missing:
    raise SystemExit('FAIL: native lookup classes removed: '+', '.join(sorted(missing)))
for name, expected in required.items():
    if expected-methods[name]:
        raise SystemExit('FAIL: native lookup methods removed: '+name+' '+str(expected-methods[name]))
print('PASS: JNI Zero and WebRTC class/method names survive R8: '+str(mapping))
