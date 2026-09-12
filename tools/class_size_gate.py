#!/usr/bin/env python3
from pathlib import Path
import sys
ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/'app/src/main/java'
LIMIT=700
bad=[]
for p in JAVA.rglob('*.java'):
    lines=p.read_text(encoding='utf-8',errors='ignore').count('\n')+1
    if lines>LIMIT: bad.append((lines,p.relative_to(ROOT)))
if bad:
    print('CLASS SIZE GATE: FAIL')
    for lines,path in sorted(bad,reverse=True): print(f' - {lines} LOC: {path}')
    sys.exit(1)
print('CLASS SIZE GATE: PASS')
print(f'No production Java class exceeds {LIMIT} LOC.')
