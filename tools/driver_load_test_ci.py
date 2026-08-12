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

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--base",required=True);p.add_argument("--token",required=True);p.add_argument("--device",required=True)
    p.add_argument("--users",type=int,required=True);p.add_argument("--requests",type=int,required=True)
    p.add_argument("--json-out",default="driver-stress-result.json");p.add_argument("--summary-out",default="driver-stress-summary.md")
    a=p.parse_args()
    if not 1<=a.users<=200 or not 1<=a.requests<=200:raise SystemExit("users/requests harus 1..200")

    url=a.base.rstrip("/")+"/driver_dashboard_native.php"
    headers={
        "Authorization":"Bearer "+a.token.strip(),
        "X-Device-UUID":a.device.strip(),
        "X-App-Scope":"driver",
        "X-Transiva-Client":"Android-Native",
        "Accept":"application/json",
        "Cache-Control":"no-cache",
        "User-Agent":"Transiva-Driver-Burst/1.1"
    }

    pre=urllib.request.Request(url,headers=headers,method="GET")
    try:
        with urllib.request.urlopen(pre,timeout=15) as r:
            pre_code=int(r.status);pre_body=read_body(r)
    except urllib.error.HTTPError as e:
        pre_code=int(e.code);pre_body=read_body(e)
    except Exception as e:
        pre_code=-1;pre_body=str(e)

    if pre_code!=200:
        code,msg=json_code_message(pre_body)
        payload={"status":"GAGAL","phase":"preflight","http":pre_code,
                 "code":code or "PREFLIGHT_FAILED","message":msg or pre_body[:300]}
        Path(a.json_out).write_text(json.dumps(payload,indent=2,ensure_ascii=False)+"\n")
        md=f"""# ❌ Transiva Driver Burst Test — GAGAL PREFLIGHT

| Parameter | Hasil |
|---|---:|
| HTTP | **{pre_code}** |
| Code | `{payload['code']}` |
| Message | {payload['message']} |

Stress test dibatalkan sebelum burst agar server tidak menerima ratusan request auth yang salah.
"""
        Path(a.summary_out).write_text(md)
        print(json.dumps(payload,indent=2,ensure_ascii=False))
        return 2

    def once(i):
        req=urllib.request.Request(url,headers=headers,method="GET");t=time.perf_counter();code=-1;body="";err=""
        try:
            with urllib.request.urlopen(req,timeout=15) as r:code=int(r.status);body=read_body(r)
        except urllib.error.HTTPError as e:code=int(e.code);body=read_body(e)
        except Exception as e:err=type(e).__name__;body=str(e)
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

    payload={"status":status,"concurrency":a.users,"requests":a.requests,
             "success_rate_pct":round(rate,2),"http_codes":dict(codes),
             "rps":round(a.requests/sec,2),"avg_ms":round(avg,1),
             "p95_ms":round(p95,1),"p99_ms":round(p99,1),
             "cache_modes":dict(caches),"plans":dict(plans),"seconds":round(sec,2)}
    Path(a.json_out).write_text(json.dumps(payload,indent=2,ensure_ascii=False)+"\n")

    icon={"AMAN":"✅","PERLU PERBAIKAN":"⚠️","GAGAL":"❌"}[status]
    md=f"""# {icon} Transiva Driver Burst Test — {status}

| Parameter | Hasil |
|---|---:|
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
