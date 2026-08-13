\
#!/usr/bin/env python3
from pathlib import Path
import sys
server=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else None
if not server or not server.is_dir():
    print('Usage: python tools/server_prelaunch_check.py /path/to/server'); sys.exit(2)
a=(server/'native_auth.php').read_text(encoding='utf-8')
l=(server/'login.php').read_text(encoding='utf-8')
f=(server/'send_fcm.php').read_text(encoding='utf-8')
errors=[]
if 'define("NATIVE_TOKEN_LIFETIME", 60 * 60 * 24 * 30)' not in a: errors.append('global token lifetime missing')
if 'const NATIVE_TOKEN_LIFETIME' in l: errors.append('login.php still defines local token lifetime')
if 'time() + NATIVE_TOKEN_LIFETIME' not in a: errors.append('native token issue path is not using global lifetime')
if 'curl_close($ch)' in f: errors.append('send_fcm.php still contains PHP 8.5 deprecated curl_close')
if errors:
    print('SERVER CHECK: FAIL'); [print(' -',e) for e in errors]; sys.exit(1)
print('SERVER CHECK: PASS')
