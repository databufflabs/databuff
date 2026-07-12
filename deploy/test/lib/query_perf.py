#!/usr/bin/env python3
"""查询链路性能基线 — 5 核心 API 压测

Usage:
  python3 query_perf.py                      # PERF_QUERY_WINDOW=10m
  PERF_QUERY_WINDOW=1h python3 query_perf.py

Environment:
  TEST_BASE_URL         default http://127.0.0.1:27403
  TEST_USERNAME         default admin
  TEST_PASSWORD         default Databuff@123
  PERF_QUERY_WINDOW     10m|1h|1d|2d  (default 10m)
  QUERY_CONCURRENCY     default 1,5,10  (comma separated)
  QUERY_TIMEOUT         default 30
"""
from __future__ import annotations

import json
import os
import time
import urllib.request
import urllib.error
from dataclasses import dataclass, field, asdict
from datetime import datetime
from typing import Any

BASE = os.environ.get("TEST_BASE_URL", "http://127.0.0.1:27403").rstrip("/")
USER = os.environ.get("TEST_USERNAME", "admin")
PASS = os.environ.get("TEST_PASSWORD", "Databuff@123")
TIMEOUT = int(os.environ.get("QUERY_TIMEOUT", "30"))
CONCURRENCY = [int(x) for x in os.environ.get("QUERY_CONCURRENCY", "1,5,10").split(",")]

WINDOW_RAW = os.environ.get("PERF_QUERY_WINDOW", "10m")
WINDOW_MULT = {"m": 60, "h": 3600, "d": 86400}
WINDOW_SEC = int(WINDOW_RAW[:-1]) * WINDOW_MULT[WINDOW_RAW[-1]] if WINDOW_RAW[-1] in WINDOW_MULT else 600
WINDOW_MS = WINDOW_SEC * 1000

API_DEFS = [
    ("服务列表", "POST", "/webapi/api/v1/apm/metric/services", {"service": "service-a", "serviceId": "service-a"}),
    ("全局拓扑", "POST", "/webapi/globalTopology/graph", {"service": "service-a"}),
    ("调用链列表", "POST", "/webapi/trace/list", {"service": "service-a", "serviceId": "service-a", "limit": 20}),
    ("HTTP 端点", "POST", "/webapi/api/v1/apm/metric/httpEndpoints", {"service": "service-a", "serviceId": "service-a", "limit": 20}),
    ("日志列表", "POST", "/webapi/api/v1/apm/log/search", {"service": "service-a", "size": 20}),
]


@dataclass
class QueryResult:
    api_name: str
    method: str
    path: str
    concurrency: int
    ok: bool
    status: int
    elapsed_ms: float
    detail: str = ""


def login() -> str:
    url = f"{BASE}/webapi/api/v1/auth/login"
    body = json.dumps({"username": USER, "password": PASS}).encode()
    req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
        data = json.loads(resp.read())
        token = data.get("token")
        if not token:
            raise RuntimeError(f"login missing token: {data}")
        return str(token)


def query_once(token: str, name: str, method: str, path: str, body: dict | None) -> QueryResult:
    now_ms = int(time.time() * 1000)
    to_ms = ((now_ms - 60000) // 60000) * 60000
    frm_ms = to_ms - WINDOW_MS

    payload = dict(body or {})
    payload.update({"from": frm_ms, "to": to_ms, "start": frm_ms // 1000, "end": to_ms // 1000})

    url = f"{BASE}{path}"
    headers = {"Content-Type": "application/json", "Authorization": f"Bearer {token}"}
    data = json.dumps(payload).encode() if payload else None

    started = time.time()
    try:
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            resp.read()
            elapsed = (time.time() - started) * 1000
            return QueryResult(name, method, path, 1, True, resp.status, round(elapsed, 1))
    except urllib.error.HTTPError as e:
        elapsed = (time.time() - started) * 1000
        return QueryResult(name, method, path, 1, False, e.code, round(elapsed, 1), str(e)[:100])
    except Exception as e:
        elapsed = (time.time() - started) * 1000
        return QueryResult(name, method, path, 1, False, -1, round(elapsed, 1), str(e)[:100])


def run_concurrent(token: str, name: str, method: str, path: str, body: dict | None, concurrency: int) -> list[QueryResult]:
    results = []
    for _ in range(concurrency):
        results.append(query_once(token, name, method, path, body))
    return results


def main():
    print(f"[query-perf] base={BASE} window={WINDOW_RAW} ({WINDOW_SEC}s)")
    print(f"[query-perf] concurrency: {CONCURRENCY}")
    print(f"[query-perf] APIs: {[d[0] for d in API_DEFS]}")
    print()

    token = login()
    print(f"[query-perf] login ok")

    all_results: list[QueryResult] = []
    for name, method, path, body in API_DEFS:
        print(f"\n--- {name} ---")
        for c in CONCURRENCY:
            results = run_concurrent(token, name, method, path, body, c)
            for r in results:
                r.concurrency = c
                all_results.append(r)

            latencies = [r.elapsed_ms for r in results]
            latencies.sort()
            p50 = latencies[len(latencies) // 2]
            p95 = latencies[int(len(latencies) * 0.95)]
            p99 = latencies[int(len(latencies) * 0.99)]
            ok_count = sum(1 for r in results if r.ok)
            print(f"  concurrency={c}: ok={ok_count}/{len(results)} p50={p50:.0f}ms p95={p95:.0f}ms p99={p99:.0f}ms")

    # Summary
    print(f"\n{'='*60}")
    print(f"QUERY PERF SUMMARY - window={WINDOW_RAW}")
    print(f"{'='*60}")
    print(f"{'API':<20} {'Concurrency':<12} {'p50(ms)':<10} {'p95(ms)':<10} {'p99(ms)':<10} {'ok':<8}")
    print(f"{'-'*60}")
    for name, method, path, body in API_DEFS:
        for c in CONCURRENCY:
            rs = [r for r in all_results if r.api_name == name and r.concurrency == c]
            if not rs:
                continue
            latencies = sorted([r.elapsed_ms for r in rs])
            p50 = latencies[len(latencies) // 2]
            p95 = latencies[int(len(latencies) * 0.95)]
            p99 = latencies[int(len(latencies) * 0.99)]
            ok_count = sum(1 for r in rs if r.ok)
            print(f"{name:<20} {c:<12} {p50:<10.0f} {p95:<10.0f} {p99:<10.0f} {ok_count}/{len(rs)}")

    # Write report
    report = {
        "base": BASE,
        "window": WINDOW_RAW,
        "window_seconds": WINDOW_SEC,
        "concurrency": CONCURRENCY,
        "timestamp": datetime.now().isoformat(),
        "results": [asdict(r) for r in all_results],
    }
    report_path = "/tmp/query-perf-result.json"
    with open(report_path, "w") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print(f"\n[query-perf] report: {report_path}")


if __name__ == "__main__":
    main()
