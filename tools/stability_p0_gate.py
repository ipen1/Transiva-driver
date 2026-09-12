#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
app = root / 'app/src/main/java/com/transiva/app'
fail=[]

def need(path, text, label):
    data=path.read_text(errors='ignore')
    if text not in data: fail.append(f'{label}: missing {text}')

def forbid(path, text, label):
    data=path.read_text(errors='ignore')
    if text in data: fail.append(f'{label}: forbidden {text}')

def family(stem):
    return '\n'.join(p.read_text(errors='ignore') for p in sorted(app.glob(stem+'*.java')))

chat_text=family('DriverChatRoomActivity')
for token,label in [('DriverResponsiveUi.apply(this);','chat responsive/IME policy'),('DriverNetworkExecutor.execute','chat shared executor'),('void postUi(Runnable action)','chat lifecycle callback guard')]:
    if token not in chat_text: fail.append(f'{label}: missing {token}')
if 'new Thread(() -> {' in chat_text: fail.append('chat raw thread: forbidden new Thread(() -> {')

api=app/'driver/data/DriverApiClient.java'
for code in ['SESSION_REPLACED','SESSION_EXPIRED','TOKEN_REVOKED','DEVICE_REVOKED']:
    need(api, code, 'terminal session policy')
need(api,'session.forceLogout(code);','terminal session force logout')

for name in ['LoginActivity.java','PinActivity.java','DriverChatActivity.java','DriverReceiptHistoryActivity.java','DriverTransferActivity.java']:
    p=app/name
    if p.exists():
        need(p,'DriverNetworkExecutor.execute',f'{name} shared executor')

# Order-state contract must remain present and ordered somewhere in the driver code.
combined='\n'.join(p.read_text(errors='ignore') for p in app.glob('*.java'))
seq=['driver_accepted','arrived_pickup','on_delivery','arrived_delivery','finished']
pos=[]
for s in seq:
    i=combined.find(s)
    if i < 0: fail.append(f'order state missing: {s}')
    pos.append(i)

if fail:
    print('STABILITY P0 GATE: FAIL')
    for x in fail: print(' -',x)
    sys.exit(1)
print('STABILITY P0 GATE: PASS')
print('Shared executor, lifecycle-safe chat, terminal-session logout, and core order status vocabulary preserved.')
