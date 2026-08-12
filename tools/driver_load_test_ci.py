#!/usr/bin/env python3
from __future__ import annotations
import argparse,concurrent.futures,json,math,statistics,sys,time,urllib.request,urllib.error
from collections import Counter
from pathlib import Path


def pct(v,q):
    if not v:return 0.0
    s=sorted(v);return float(s[max(0,min(len(s)-1,math.ceil(q*len(s))-1))])


def read_body(resp):
    try:return resp.read().decode("utf-8","replace")
    except Exception:return ""


def json_code_message(body):
    try:
        o=json.loads(body)
        if isinstance(o,dict):
            return str(o.get("code","")),str(o.get("message",""))
    except Exception:pass
    return "",""


def http_json(url, method="GET", headers=None, payload=None, timeout=15):
    body=None
    req_headers=dict(headers or {})
    if payload is not None:
        body=json.dumps(payload,separators=(",",":"),ensure_ascii=False).encode("utf-8")
        req_headers.setdefault("Content-Type","application/json")
    req=urllib.request.Request(url,data=body,headers=req_headers,method=method)
    try:
        with urllib.request.urlopen(req,timeout=timeout) as r:
            return int(r.status),read_body(r)
    except urllib.error.HTTPError as e:
        return int(e.code),read_body(e)
    except Exception as e:
        return -1,str(e)


def obtain_token(base, username, password, device):
    url=base.rstrip("/")+"/login_native.php"
    status,body=http_json(url,"POST",{
        "Accept":"application/json",
        "X-App-Scope":"driver",
        "X-Transiva-Client":"GitHub-Driver-Burst/2.0"
    },{
        "username":username,
        "password":password,
        "installation_uuid":device,
        "device_name":"Transiva GitHub Load Test",
        "app_scope":"driver"
    },20)
    if status != 200:
        code,msg=json_code_message(body)
        raise RuntimeError(f"LOGIN_FAILED HTTP {status} {code or ''} {msg or body[:180]}")
    try:
        obj=json.loads(body)
        data=obj.get("data",{}) if isinstance(obj,dict) else {}
        user=data.get("user",{}) if isinstance(data,dict) else {}
        token=str(user.get("token","")).strip()
    except Exception:
        token=""
    if not token:
        raise RuntimeError("LOGIN_FAILED: server tidak mengembalikan Bearer token")
    return token


def write_preflight_failure(json_out, summary_out, http, code, message):
    payload={"status":"GAGAL","phase":"preflight","http":http,
             "code":code or "PREFLIGHT_FAILED","message":message}
    Path(json_out).write_text(json.dumps(payload,indent=2,ensure_ascii=False)+"\n")
    md=f"""# ❌ Transiva Driver Burst Test — GAGAL PREFLIGHT

| Parameter | Hasil |
|---|---:|
| HTTP | **{http}** |
| Code | `{payload['code']}` |
| Message | {payload['message']} |

Stress test dibatalkan sebelum burst agar server tidak menerima ratusan request auth yang salah.
"""
    Path(summary_out).write_text(md)
    print(json.dumps(payload,indent=2,ensure_ascii=False))


def main():
    p=argparse.ArgumentParser()
    p.add_argument("--base",required=True)
    p.add_argument("--device",required=True)
    auth=p.add_mutually_exclusive_group(required=True)
    auth.add_argument("--token")
    auth.add_argument("--username")
    p.add_argument("--password")
    p.add_argument("--users",type=int,required=True);p.add_argument("--requests",type=int,required=True)
    p.add_argument("--json-out",default="driver-stress-result.json");p.add_argument("--summary-out",default="driver-stress-summary.md")
    a=p.parse_args()
    if not 1<=a.users<=200 or not 1<=a.requests<=200:raise SystemExit("users/requests harus 1..200")

    base=a.base.rstrip("/")+"/"
    if a.username:
        if not a.password:
            raise SystemExit("--password wajib bila memakai --username")
        try:
            token=obtain_token(base,a.username,a.password,a.device.strip())
            print("Auth load-test: token baru berhasil dibuat.")
        except Exception as e:
            msg=str(e)
            write_preflight_failure(a.json_out,a.summary_out,401,"LOAD_TEST_LOGIN_FAILED",msg)
            return 2
    else:
        token=(a.token or "").strip()

    url=base+"driver_dashboard_native.php"
    headers={
        "Authorization":"Bearer "+token,
        "X-Device-UUID":a.device.strip(),
        "X-App-Scope":"driver",
        "X-Transiva-Client":"Android-Native",
        "Accept":"application/json",
        "Cache-Control":"no-cache",
        "User-Agent":"Transiva-Driver-Burst/2.0"
    }

    pre_code,pre_body=http_json(url,"GET",headers,None,15)
    if pre_code!=200:
        code,msg=json_code_message(pre_body)
        write_preflight_failure(a.json_out,a.summary_out,pre_code,
                                code or "PREFLIGHT_FAILED",msg or pre_body[:300])
        return 2

    def once(i):
        t=time.perf_counter();code,body=http_json(url,"GET",headers,None,15);err=""
        if code == -1: err="NetworkError"
        ms=(time.perf_counter()-t)*1000
        cache=plan=""
        try:
            o=json.loads(body);d=o.get("_transiva_diag") if isinstance(o,dict) else None
            if isinstance(d,dict):cache=str(d.get("cache",""));plan=str(d.get("plan",""))
        except Exception:pass
        return code,ms,cache or "none",plan or "none",err,body[:500]

    started=time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=a.users) as ex:
        res=list(ex.map(once,range(a.requests)))
    sec=max(time.perf_counter()-started,.001)
    codes=Counter(str(x[0]) for x in res);times=[x[1] for x in res]
    caches=Counter(x[2] for x in res);plans=Counter(x[3] for x in res)
    ok=codes.get("200",0);rate=ok/a.requests*100
    avg=statistics.mean(times);p95=pct(times,.95);p99=pct(times,.99)
    server_errors=sum(v for k,v in codes.items() if k in {"500","502","503","504","-1"})
    if server_errors or rate<95 or avg>3000 or p95>5000 or p99>10000:status="GAGAL"
    elif rate<99 or avg>1500 or p95>2500 or p99>5000:status="PERLU PERBAIKAN"
    else:status="AMAN"

    payload={"status":status,"auth_mode":"fresh_login","concurrency":a.users,"requests":a.requests,
             "success_rate_pct":round(rate,2),"http_codes":dict(codes),
             "rps":round(a.requests/sec,2),"avg_ms":round(avg,1),
             "p95_ms":round(p95,1),"p99_ms":round(p99,1),
             "cache_modes":dict(caches),"plans":dict(plans),"seconds":round(sec,2)}
    Path(a.json_out).write_text(json.dumps(payload,indent=2,ensure_ascii=False)+"\n")

    icon={"AMAN":"✅","PERLU PERBAIKAN":"⚠️","GAGAL":"❌"}[status]
    md=f"""# {icon} Transiva Driver Burst Test — {status}

| Parameter | Hasil |
|---|---:|
| Authentication | **Fresh login khusus test** |
| Concurrent users | **{a.users}** |
| Total requests | **{a.requests}** |
| HTTP 200 success | **{rate:.2f}%** |
| Throughput | **{payload['rps']} req/s** |
| Average | **{payload['avg_ms']} ms** |
| P95 | **{payload['p95_ms']} ms** |
| P99 | **{payload['p99_ms']} ms** |
| HTTP codes | `{json.dumps(payload['http_codes'])}` |
| Cache modes | `{json.dumps(payload['cache_modes'])}` |
| Server plan | `{json.dumps(payload['plans'])}` |
| Durasi | **{payload['seconds']} s** |

Target AMAN: HTTP 200 ≥99%, 0 server/network error, Average ≤1500 ms, P95 ≤2500 ms, P99 ≤5000 ms.
Endpoint read-only: `driver_dashboard_native.php`.
"""
    Path(a.summary_out).write_text(md)
    print(json.dumps(payload,indent=2,ensure_ascii=False))
    return 2 if status=="GAGAL" else 0

if __name__=="__main__":
    sys.exit(main())
