#!/usr/bin/env python3
from pathlib import Path
import re, sys

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java"
failures = []

# Network centralization: DriverHttpTransport is the only class allowed to create sockets.
for p in JAVA.rglob("*.java"):
    text = p.read_text(errors="ignore")
    if "openConnection()" in text and p.name != "DriverHttpTransport.java":
        failures.append(f"direct URL.openConnection remains: {p.relative_to(ROOT)}")

transport = JAVA / "com/transiva/app/DriverHttpTransport.java"
if not transport.exists(): failures.append("DriverHttpTransport.java missing")
else:
    t = transport.read_text(errors="ignore")
    for token in ["Authorization", "X-Device-UUID", "X-App-Scope", "DEFAULT_CONNECT_TIMEOUT_MS", "isTrustedTransivaHost"]:
        if token not in t: failures.append(f"transport invariant missing: {token}")

# Activity decomposition invariants.
expected = {
    "DriverDashboardActivity.java": "DashboardRefreshController",
    "DriverTripActivity.java": "DriverOrderStateMachine",
    "DriverNavigationActivity.java": "NavigationRoutePolicy",
}
base = JAVA / "com/transiva/app"
for activity, delegate in expected.items():
    p = base / activity
    if not p.exists(): failures.append(f"{activity} missing")
    elif delegate not in p.read_text(errors="ignore"):
        failures.append(f"{activity} is not delegating to {delegate}")
    if not (base / f"{delegate}.java").exists():
        failures.append(f"{delegate}.java missing")

# Core order vocabulary must remain untouched.
trip = (base / "DriverTripActivity.java").read_text(errors="ignore")
for status in ["arrived_pickup", "on_delivery", "arrived_delivery", "finished"]:
    if status not in trip: failures.append(f"Trip core status missing: {status}")
state = (base / "DriverOrderStateMachine.java").read_text(errors="ignore")
if '"driver_accepted"' not in state: failures.append("driver_accepted normalization missing")

# Regression suite expansion.
tests = list((ROOT / "app/src/test/java").rglob("*Test.java"))
if len(tests) < 5: failures.append(f"expected >=5 unit-test classes, found {len(tests)}")
for required in ["DriverOrderStateMachineTest.java", "NavigationRoutePolicyTest.java"]:
    if not any(p.name == required for p in tests): failures.append(f"missing regression test: {required}")

if failures:
    print("STABILITY 4.0 GATE: FAIL")
    for f in failures: print(" -", f)
    sys.exit(1)
print("STABILITY 4.0 GATE: PASS")
print(f" - production URL.openConnection owners: 1 ({transport.name})")
print(f" - unit-test classes: {len(tests)}")
print(" - Dashboard/Trip/Navigation delegates: PASS")
print(" - core order vocabulary: PASS")
