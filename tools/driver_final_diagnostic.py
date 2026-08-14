#!/usr/bin/env python3
from __future__ import annotations

import argparse
import concurrent.futures
import json
import math
import statistics
import sys
import time
import urllib.error
import urllib.request
from collections import Counter
from pathlib import Path


def pct(values, q):
    if not values:
        return 0.0
    s = sorted(values)
    i = max(0, min(len(s) - 1, math.ceil(q * len(s)) - 1))
    return float(s[i])


def get_json(body):
    try:
        value = json.loads(body)
        return value if isinstance(value, dict) else {}
    except Exception:
        return {}


def request_once(url, headers=None, method="GET", payload=None, timeout=20):
    body = None
    req_headers = dict(headers or {})
    if payload is not None:
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        req_headers.setdefault("Content-Type", "application/json")
    req = urllib.request.Request(url, data=body, headers=req_headers, method=method)
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode("utf-8", "replace")
            hdrs = {k.lower(): v for k, v in r.headers.items()}
            code = int(r.status)
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        hdrs = {k.lower(): v for k, v in e.headers.items()}
        code = int(e.code)
    except Exception as e:
        raw = str(e)
        hdrs = {}
        code = -1
    elapsed_ms = (time.perf_counter() - started) * 1000.0
    return code, elapsed_ms, raw, hdrs


def obtain_token(base, username, password, device):
    code, _, body, _ = request_once(
        base + "login.php",
        method="POST",
        headers={
            "Accept": "application/json",
            "X-App-Scope": "driver",
            "X-Transiva-Client": "GitHub-Driver-Final-Diagnostic/1.0",
        },
        payload={
            "username": username,
            "password": password,
            "installation_uuid": device,
            "device_name": "Transiva GitHub Final Diagnostic",
            "app_scope": "driver",
        },
        timeout=20,
    )
    if code != 200:
        raise RuntimeError(f"LOGIN_FAILED HTTP {code}: {body[:180]}")
    obj = get_json(body)
    candidates = [obj.get("token")]
    for key in ("user", "data"):
        value = obj.get(key)
        if isinstance(value, dict):
            candidates.append(value.get("token"))
            nested = value.get("user")
            if isinstance(nested, dict):
                candidates.append(nested.get("token"))
    for token in candidates:
        token = str(token or "").strip()
        if token:
            return token
    raise RuntimeError("LOGIN_FAILED: Bearer token tidak ditemukan")


def fnum(headers, name):
    try:
        return float(headers.get(name.lower(), "0") or 0)
    except Exception:
        return 0.0


def run_stage(name, url, headers, users, requests, timeout=20):
    # Preflight satu request untuk membedakan salah konfigurasi vs bottleneck.
    pre_code, pre_ms, pre_body, _ = request_once(url, headers=headers, timeout=timeout)
    if pre_code != 200:
        return {
            "name": name,
            "preflight_failed": True,
            "preflight_http": pre_code,
            "preflight_ms": round(pre_ms, 1),
            "preflight_body": pre_body[:220],
        }

    def one(_):
        code, elapsed, body, hdrs = request_once(url, headers=headers, timeout=timeout)
        parsed = get_json(body)
        return {
            "code": code,
            "elapsed_ms": elapsed,
            "php_ms": fnum(hdrs, "x-transiva-php-ms") or float(parsed.get("php_ms", 0) or 0),
            "connect_ms": fnum(hdrs, "x-transiva-db-connect-ms") or float(parsed.get("db_connect_ms", 0) or 0),
            "query_ms": fnum(hdrs, "x-transiva-db-query-ms") or float(parsed.get("db_query_ms", 0) or 0),
        }

    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=users) as pool:
        rows = list(pool.map(one, range(requests)))
    seconds = max(time.perf_counter() - started, 0.001)

    codes = Counter(str(r["code"]) for r in rows)
    elapsed = [r["elapsed_ms"] for r in rows]
    php = [r["php_ms"] for r in rows if r["php_ms"] > 0]
    connect = [r["connect_ms"] for r in rows if r["connect_ms"] > 0]
    query = [r["query_ms"] for r in rows if r["query_ms"] > 0]
    ok = codes.get("200", 0)

    return {
        "name": name,
        "preflight_failed": False,
        "requests": requests,
        "concurrency": users,
        "success_rate_pct": round(ok / requests * 100, 2),
        "http_codes": dict(codes),
        "rps": round(requests / seconds, 2),
        "avg_ms": round(statistics.mean(elapsed), 1),
        "p50_ms": round(pct(elapsed, 0.50), 1),
        "p95_ms": round(pct(elapsed, 0.95), 1),
        "p99_ms": round(pct(elapsed, 0.99), 1),
        "max_ms": round(max(elapsed), 1),
        "php_avg_ms": round(statistics.mean(php), 2) if php else 0.0,
        "php_p95_ms": round(pct(php, 0.95), 2) if php else 0.0,
        "db_connect_avg_ms": round(statistics.mean(connect), 2) if connect else 0.0,
        "db_connect_p95_ms": round(pct(connect, 0.95), 2) if connect else 0.0,
        "db_query_avg_ms": round(statistics.mean(query), 2) if query else 0.0,
        "db_query_p95_ms": round(pct(query, 0.95), 2) if query else 0.0,
        "seconds": round(seconds, 2),
    }


def ratio(a, b):
    if b <= 0:
        return 999.0 if a > 0 else 1.0
    return a / b


def classify(stages):
    by = {s["name"]: s for s in stages if not s.get("preflight_failed")}
    required = ["STATIC_WEBSERVER", "PHP_EMPTY", "DB_SELECT1", "DRIVER_DASHBOARD"]
    if any(name not in by for name in required):
        return "PREFLIGHT_OR_CONFIGURATION", "Satu atau lebih layer tidak berhasil diuji."

    st, ph, db, app = (by[n] for n in required)
    if any(s["success_rate_pct"] < 99 for s in (st, ph, db, app)):
        return "RELIABILITY_OR_LIMIT", "Ada layer dengan HTTP success di bawah 99%. Periksa HTTP codes terlebih dahulu."

    # Static lambat berarti baseline jaringan/CDN/webserver sudah lambat sebelum PHP.
    if st["p95_ms"] > 1500:
        return "NETWORK_CDN_OR_WEBSERVER", "Static file saja lambat; database bukan penyebab utama."

    # PHP empty jauh lebih lambat dari static => antre sebelum/di worker PHP (EP/LSAPI/PHP-FPM).
    if ph["p95_ms"] > 2500 and ratio(ph["p95_ms"], max(st["p95_ms"], 1)) >= 2.5:
        if ph["php_p95_ms"] > 0 and ph["php_p95_ms"] < 300:
            return "PHP_WORKER_ENTRY_PROCESS_QUEUE", "Waktu client tinggi tetapi waktu internal PHP rendah: request mengantre sebelum PHP dieksekusi."
        return "PHP_WORKER_OR_ENTRY_PROCESS", "PHP kosong sudah lambat dibanding static; fokus ke worker/Entry Processes/LSAPI, bukan query aplikasi."

    # SELECT 1 lambat sementara PHP kosong cepat => koneksi DB / DB concurrency.
    if db["p95_ms"] > 2500 and ratio(db["p95_ms"], max(ph["p95_ms"], 1)) >= 2.0:
        if db["db_connect_p95_ms"] > max(500, db["db_query_p95_ms"] * 4):
            return "DATABASE_CONNECTION_BOTTLENECK", "Mayoritas waktu DB ada di membuka koneksi MySQL."
        if db["db_query_p95_ms"] > 500:
            return "DATABASE_ENGINE_BOTTLENECK", "SELECT 1 sendiri lambat di dalam MySQL; cek limit koneksi/engine/provider."
        return "DATABASE_OR_DB_GATE", "Layer DB jauh lebih lambat dari PHP kosong."

    # DB baseline cepat tapi dashboard lambat => aplikasi/query dashboard.
    if app["p95_ms"] > 2500 and ratio(app["p95_ms"], max(db["p95_ms"], 1)) >= 2.0:
        return "DASHBOARD_APP_OR_QUERY", "Static, PHP kosong, dan SELECT 1 sehat; bottleneck spesifik di dashboard/auth/query bisnis."

    if app["p95_ms"] <= 2500 and app["p99_ms"] <= 5000:
        return "HEALTHY", "Semua layer berada dalam target pilot."

    return "MIXED_OR_HOSTING_LIMIT", "Tidak ada satu layer dominan; bandingkan tabel per-layer dan server timing."


def table_row(s):
    if s.get("preflight_failed"):
        return f"| {s['name']} | PREFLIGHT FAIL | HTTP {s['preflight_http']} | - | - | - |"
    return (
        f"| {s['name']} | {s['success_rate_pct']:.2f}% | {s['avg_ms']:.1f} | "
        f"{s['p95_ms']:.1f} | {s['p99_ms']:.1f} | {s['rps']:.2f} |"
    )


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--base", required=True)
    p.add_argument("--diag-key", required=True)
    p.add_argument("--username", required=True)
    p.add_argument("--password", required=True)
    p.add_argument("--device", required=True)
    p.add_argument("--users", type=int, default=100)
    p.add_argument("--requests", type=int, default=200)
    p.add_argument("--json-out", default="driver-final-diagnostic.json")
    p.add_argument("--summary-out", default="driver-final-diagnostic.md")
    a = p.parse_args()

    if not 1 <= a.users <= 200 or not 1 <= a.requests <= 400:
        raise SystemExit("users harus 1..200 dan requests 1..400")
    if len(a.diag_key.strip()) < 24:
        raise SystemExit("TRANSIVA_DIAG_KEY minimal 24 karakter")

    base = a.base.rstrip("/") + "/"
    try:
        token = obtain_token(base, a.username, a.password, a.device.strip())
    except Exception as e:
        print(str(e), file=sys.stderr)
        return 2

    diag_headers = {
        "X-Transiva-Diag-Key": a.diag_key.strip(),
        "Accept": "application/json",
        "Cache-Control": "no-cache",
        "User-Agent": "Transiva-Final-Diagnostic/1.0",
    }
    dash_headers = {
        "Authorization": "Bearer " + token,
        "X-Device-UUID": a.device.strip(),
        "X-App-Scope": "driver",
        "X-Transiva-Client": "Android-Native",
        "Accept": "application/json",
        "Cache-Control": "no-cache",
        "User-Agent": "Transiva-Final-Diagnostic/1.0",
    }

    stages = [
        run_stage("STATIC_WEBSERVER", base + "diag_static.txt?nocache=1", {"Cache-Control": "no-cache"}, a.users, a.requests),
        run_stage("PHP_EMPTY", base + "diag_php_empty.php", diag_headers, a.users, a.requests),
        run_stage("DB_SELECT1", base + "diag_db_select1.php", diag_headers, a.users, a.requests),
        run_stage("DRIVER_DASHBOARD", base + "driver_dashboard_native.php", dash_headers, a.users, a.requests),
    ]

    diagnosis, explanation = classify(stages)
    payload = {
        "diagnosis": diagnosis,
        "explanation": explanation,
        "concurrency": a.users,
        "requests_per_stage": a.requests,
        "stages": stages,
    }
    Path(a.json_out).write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n")

    details = []
    for s in stages:
        if s.get("preflight_failed"):
            continue
        if s["name"] in {"PHP_EMPTY", "DB_SELECT1"}:
            details.append(
                f"- **{s['name']} internal:** PHP P95 {s['php_p95_ms']} ms, "
                f"DB connect P95 {s['db_connect_p95_ms']} ms, DB query P95 {s['db_query_p95_ms']} ms."
            )

    md = f"""# Transiva Final Bottleneck Diagnostic

**Diagnosis utama: `{diagnosis}`**

{explanation}

| Layer | HTTP 200 | Avg ms | P95 ms | P99 ms | req/s |
|---|---:|---:|---:|---:|---:|
{chr(10).join(table_row(s) for s in stages)}

## Server-internal timing
{chr(10).join(details) if details else '- Tidak tersedia karena preflight gagal.'}

## Cara membaca
- `STATIC_WEBSERVER` lambat → jaringan/CDN/webserver sebelum PHP.
- `PHP_EMPTY` jauh lebih lambat dari static → worker PHP / Entry Processes / LSAPI queue.
- `DB_SELECT1` jauh lebih lambat dari PHP kosong → koneksi atau engine MySQL.
- Tiga layer awal cepat tetapi `DRIVER_DASHBOARD` lambat → auth/query/logika dashboard.

Target pilot yang dipakai: HTTP 200 ≥99%, P95 dashboard ≤2500 ms, P99 ≤5000 ms.
"""
    Path(a.summary_out).write_text(md)
    print(json.dumps(payload, indent=2, ensure_ascii=False))

    # Diagnosis workflow dianggap berhasil walau menemukan bottleneck; gagal hanya jika preflight/config gagal.
    return 2 if diagnosis == "PREFLIGHT_OR_CONFIGURATION" else 0


if __name__ == "__main__":
    sys.exit(main())
