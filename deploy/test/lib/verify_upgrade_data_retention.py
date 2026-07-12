#!/usr/bin/env python3
"""升级前后历史数据可查验证 — snapshot / verify 两阶段。

在线升级 PASS 硬门禁之一：升级前写入的 Trace/Log 在升级后仍可通过 Web API 查到。

Usage:
  python3 lib/verify_upgrade_data_retention.py snapshot
  python3 lib/verify_upgrade_data_retention.py verify

Environment:
  TEST_BASE_URL          default http://127.0.0.1:27403
  TEST_USERNAME          default admin
  TEST_PASSWORD          default Databuff@123
  SNAPSHOT_FILE          default /tmp/upgrade-data-snapshot.json
  SEED_WARMUP_SECONDS    snapshot 前等待 demo 造数（default 300）
  MIN_TRACE_IDS          至少快照 TraceID 数（default 3）
  MIN_SPANS_PER_TRACE    verify 时每条 Trace 至少 span 数（default 1）
  QUERY_WINDOW_MS        快照查询窗宽（default 600000 = 10m）
  TEST_TIMEOUT           HTTP 超时秒（default 60）
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path
from typing import Any

# 同目录 run_tests
sys.path.insert(0, str(Path(__file__).resolve().parent))
from cases.common import (  # noqa: E402
    DEMO_CHECKOUT_RESOURCE,
    DEMO_SERVICE_A,
    DEMO_SERVICE_A_ID,
    log_search_body,
)
from run_tests import http_json, login  # noqa: E402


def env_int(name: str, default: int) -> int:
    raw = os.environ.get(name)
    if raw is None or raw == "":
        return default
    return int(raw)


def env_str(name: str, default: str) -> str:
    return os.environ.get(name, default)


def trace_list_body(frm_ms: int, to_ms: int, limit: int = 20) -> dict[str, Any]:
    return {
        "service": DEMO_SERVICE_A,
        "serviceId": DEMO_SERVICE_A_ID,
        "from": frm_ms,
        "to": to_ms,
        "start": frm_ms,
        "end": to_ms,
        "limit": limit,
    }


def fetch_trace_list(base: str, token: str, frm_ms: int, to_ms: int, timeout: float) -> tuple[list[dict[str, Any]], int]:
    code, _, payload = http_json(
        "POST",
        f"{base.rstrip('/')}/webapi/trace/list",
        body=trace_list_body(frm_ms, to_ms),
        token=token,
        timeout=timeout,
    )
    if code != 200:
        raise RuntimeError(f"trace/list HTTP {code}: {payload}")
    data = payload.get("data") if isinstance(payload, dict) else None
    if not isinstance(data, dict):
        raise RuntimeError(f"trace/list unexpected payload: {payload}")
    items = data.get("list")
    if not isinstance(items, list):
        items = []
    total = int(data.get("total") or len(items))
    return items, total


def fetch_spans(base: str, token: str, trace_id: str, timeout: float) -> int:
    code, _, payload = http_json(
        "POST",
        f"{base.rstrip('/')}/webapi/trace/spans",
        body={"traceId": trace_id},
        token=token,
        timeout=timeout,
    )
    if code != 200:
        raise RuntimeError(f"trace/spans HTTP {code} traceId={trace_id}: {payload}")
    data = payload.get("data")
    if isinstance(data, dict):
        items = data.get("list") or []
    elif isinstance(data, list):
        items = data
    else:
        items = []
    return len(items) if isinstance(items, list) else 0


def fetch_log_hits(base: str, token: str, frm_ms: int, to_ms: int, timeout: float) -> int:
    body = log_search_body(frm_ms, to_ms, serviceIds=[DEMO_SERVICE_A_ID])
    code, _, payload = http_json(
        "POST",
        f"{base.rstrip('/')}/webapi/log/search",
        body=body,
        token=token,
        timeout=timeout,
    )
    if code != 200:
        raise RuntimeError(f"log/search HTTP {code}: {payload}")
    data = payload.get("data")
    if isinstance(data, dict):
        items = data.get("list") or data.get("logs") or []
    elif isinstance(data, list):
        items = data
    else:
        items = []
    if isinstance(items, list):
        return len(items)
    total = data.get("total") if isinstance(data, dict) else None
    return int(total) if total is not None else 0


def pick_trace_ids(items: list[dict[str, Any]], min_count: int) -> list[str]:
    ids: list[str] = []
    seen: set[str] = set()
    for item in items:
        if not isinstance(item, dict):
            continue
        tid = item.get("trace_id") or item.get("traceId")
        if not tid or tid in seen:
            continue
        seen.add(str(tid))
        ids.append(str(tid))
    checkout = [
        str(item.get("trace_id") or item.get("traceId"))
        for item in items
        if isinstance(item, dict)
        and item.get("resource") == DEMO_CHECKOUT_RESOURCE
        and (item.get("trace_id") or item.get("traceId"))
    ]
    ordered: list[str] = []
    for tid in checkout + ids:
        if tid and tid not in ordered:
            ordered.append(tid)
    if len(ordered) < min_count:
        raise RuntimeError(
            f"snapshot needs >={min_count} trace_ids, got {len(ordered)} in window; "
            f"extend SEED_WARMUP_SECONDS or check demo → ingest"
        )
    return ordered[: max(min_count, 5)]


def wait_for_seed(base: str, token: str, service: str, warmup_sec: int, timeout: float) -> None:
    deadline = time.time() + warmup_sec
    while time.time() < deadline:
        to_ms = int(time.time() * 1000)
        frm_ms = to_ms - 120_000
        body = {
            "service": service,
            "serviceId": service,
            "from": frm_ms,
            "to": to_ms,
            "start": frm_ms,
            "end": to_ms,
            "limit": 20,
        }
        code, _, payload = http_json(
            "POST",
            f"{base.rstrip('/')}/webapi/trace/list",
            body=body,
            token=token,
            timeout=timeout,
        )
        if code == 200:
            data = payload.get("data") if isinstance(payload, dict) else None
            items = data.get("list") if isinstance(data, dict) else None
            if isinstance(items, list) and len(items) >= 3:
                print(f"[data-retention] demo ready ({len(items)} traces in last 120s)")
                return
        time.sleep(10)
    raise RuntimeError(
        f"demo not ready after {warmup_sec}s; need >=3 traces in 120s window (service={service})"
    )


def cmd_snapshot(args: argparse.Namespace) -> int:
    base = env_str("TEST_BASE_URL", "http://127.0.0.1:27403")
    username = env_str("TEST_USERNAME", "admin")
    password = env_str("TEST_PASSWORD", "Databuff@123")
    snapshot_file = Path(env_str("SNAPSHOT_FILE", "/tmp/upgrade-data-snapshot.json"))
    warmup = env_int("SEED_WARMUP_SECONDS", 300)
    min_ids = env_int("MIN_TRACE_IDS", 3)
    window_ms = env_int("QUERY_WINDOW_MS", 600_000)
    timeout = float(env_str("TEST_TIMEOUT", "60"))

    print(f"[data-retention] waiting up to {warmup}s for demo seed ...")
    token = login(base, username, password, timeout)
    wait_for_seed(base, token, DEMO_SERVICE_A, warmup, timeout)

    to_ms = int(time.time() * 1000)
    frm_ms = to_ms - window_ms
    items, total = fetch_trace_list(base, token, frm_ms, to_ms, timeout)
    trace_ids = pick_trace_ids(items, min_ids)
    log_hits = fetch_log_hits(base, token, frm_ms, to_ms, timeout)

    snapshot = {
        "captured_at_ms": to_ms,
        "query_from_ms": frm_ms,
        "query_to_ms": to_ms,
        "trace_ids": trace_ids,
        "trace_list_total": total,
        "log_hit_count": log_hits,
        "service": DEMO_SERVICE_A,
        "service_id": DEMO_SERVICE_A_ID,
    }
    snapshot_file.write_text(json.dumps(snapshot, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"[data-retention] snapshot OK → {snapshot_file}")
    print(
        f"[data-retention] window [{frm_ms}, {to_ms}] "
        f"trace_ids={len(trace_ids)} total={total} logs={log_hits}"
    )
    return 0


def cmd_verify(args: argparse.Namespace) -> int:
    base = env_str("TEST_BASE_URL", "http://127.0.0.1:27403")
    username = env_str("TEST_USERNAME", "admin")
    password = env_str("TEST_PASSWORD", "Databuff@123")
    snapshot_file = Path(env_str("SNAPSHOT_FILE", "/tmp/upgrade-data-snapshot.json"))
    min_spans = env_int("MIN_SPANS_PER_TRACE", 1)
    timeout = float(env_str("TEST_TIMEOUT", "60"))

    if not snapshot_file.is_file():
        raise RuntimeError(f"snapshot missing: {snapshot_file} (run snapshot before upgrade)")

    snapshot = json.loads(snapshot_file.read_text(encoding="utf-8"))
    frm_ms = int(snapshot["query_from_ms"])
    to_ms = int(snapshot["query_to_ms"])
    trace_ids: list[str] = list(snapshot.get("trace_ids") or [])
    expected_total = int(snapshot.get("trace_list_total") or 0)
    expected_logs = int(snapshot.get("log_hit_count") or 0)

    if len(trace_ids) < env_int("MIN_TRACE_IDS", 3):
        raise RuntimeError(f"snapshot has insufficient trace_ids: {trace_ids}")

    token = login(base, username, password, timeout)
    failed: list[str] = []

    for tid in trace_ids:
        span_count = fetch_spans(base, token, tid, timeout)
        if span_count < min_spans:
            failed.append(f"trace {tid}: spans={span_count} (need >={min_spans})")
        else:
            print(f"[data-retention] OK trace {tid} spans={span_count}")

    _, total_after = fetch_trace_list(base, token, frm_ms, to_ms, timeout)
    if total_after < len(trace_ids):
        failed.append(f"trace/list total={total_after} < snapshot trace_ids={len(trace_ids)}")
    else:
        print(f"[data-retention] OK trace/list total={total_after} (snapshot had {expected_total})")

    logs_after = fetch_log_hits(base, token, frm_ms, to_ms, timeout)
    if expected_logs > 0 and logs_after <= 0:
        failed.append(f"log/search hits=0 (snapshot had {expected_logs})")
    else:
        print(f"[data-retention] OK log/search hits={logs_after} (snapshot had {expected_logs})")

    if failed:
        for line in failed:
            print(f"[data-retention] FAIL {line}", file=sys.stderr)
        return 1

    print("[data-retention] verify OK — pre-upgrade data still queryable")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Upgrade data retention snapshot/verify")
    parser.add_argument("mode", choices=["snapshot", "verify"])
    args = parser.parse_args()
    try:
        if args.mode == "snapshot":
            return cmd_snapshot(args)
        return cmd_verify(args)
    except RuntimeError as error:
        print(f"[data-retention] ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
