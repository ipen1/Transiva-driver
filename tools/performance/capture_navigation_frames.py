#!/usr/bin/env python3
"""Capture actual SurfaceFlinger frame timeline (Android 12+) on a connected phone.
Open Driver Trip or navigation before running; this does not simulate a journey or claim an FPS.
"""
import argparse,subprocess
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--serial');p.add_argument('--output',default='transiva-navigation.perfetto-trace');a=p.parse_args()
adb=['adb']+(['-s',a.serial] if a.serial else [])
config=Path(__file__).with_name('navigation_frames.pbtxt')
remote='/data/local/tmp/transiva-navigation-frames.pbtxt';trace='/data/misc/perfetto-traces/transiva-navigation.perfetto-trace'
subprocess.run(adb+['get-state'],check=True)
subprocess.run(adb+['push',str(config),remote],check=True)
try:
 print('Capturing 30 seconds. Keep the requested map screen visible.',flush=True)
 subprocess.run(adb+['shell','perfetto','--txt','-c',remote,'-o',trace],check=True,timeout=50)
 subprocess.run(adb+['pull',trace,str(Path(a.output).resolve())],check=True)
 print('Saved:',Path(a.output).resolve())
finally:
 subprocess.run(adb+['shell','rm','-f',remote,trace],check=False)
