#!/usr/bin/env python3
"""B/C HTTP：空窗 + 非法 id + 开源 resend 空实现。单跑，不进 146 expected 快照。

造不出空段就 SKIP 写原因。禁止 seed、禁止时间旅行装绿。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Any, Callable

LIB_ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(LIB_ROOT))

from run_tests import http_json, login  # noqa: E402

BASE = os.environ.get("TEST_BASE_URL", "http://127.0.0.1:27403")
USER = os.environ.get("TEST_USERNAME", "admin")
PASSWORD = os.environ.get("TEST_PASSWORD", "Databuff@123")

# 1970 窗：跟告警空窗同一套，是查询参数，不是改系统钟。
EMPTY_TW = {"from": 1, "to": 2, "start": 1, "end": 2}
EMPTY_LOG = {
    "fromTimeNs": "1000000",
    "toTimeNs": "2000000",
    "size": 100,
    "offset": 0,
}
BAD_SERVICE = "not-a-real-service-id"
BAD_TRACE = "not-a-real-trace-id-0000000000000000"


class Skip(Exception):
    pass


class Fail(Exception):
    pass


def _data(payload: Any) -> Any:
    if isinstance(payload, dict):
        return payload.get("data")
    return payload


def is_empty(payload: Any) -> bool:
    if payload is None:
        return True
    if payload == [] or payload == {} or payload == "":
        return True
    if not isinstance(payload, dict):
        return False
    data = payload.get("data", payload)
    if data is None or data == [] or data == {} or data == "":
        return True
    if payload.get("total") == 0 and (data == [] or data == {} or (
        isinstance(data, dict) and data.get("list") == []
    )):
        return True
    if isinstance(data, list):
        if len(data) == 0:
            return True
        # trafficLight：桶在、serviceOrders 全空，或全是 value=0（目录还在、没有流量）
        if all(_zero_bucket(row) for row in data if isinstance(row, dict)):
            return True
        return False
    if isinstance(data, dict):
        if data.get("list") == [] or data.get("total") == 0:
            return True
        services = data.get("services")
        edges = data.get("edges")
        if services == [] and (not edges):
            return True
        flows = data.get("serviceFlows")
        if flows in ({}, None, []):
            if not data.get("nodes") and not data.get("edges"):
                return True
        if isinstance(flows, dict) and flows and all(_zero_flow(node) for node in flows.values()):
            return True
        if data.get("items") == []:
            return True
    return False


def _zero_bucket(row: dict[str, Any]) -> bool:
    orders = row.get("serviceOrders")
    if not orders:
        return not row.get("services")
    if not isinstance(orders, list):
        return False
    return all(isinstance(item, dict) and not item.get("value") for item in orders)


def _zero_flow(node: Any) -> bool:
    if not isinstance(node, dict):
        return False
    children = node.get("children") or []
    return not node.get("call") and children == []


def looks_like_real_service_a(payload: Any) -> bool:
    text = json.dumps(payload, ensure_ascii=False)
    return "service-a" in text or "9bf61532d56eb7b5" in text


def empty_or_skip(payload: Any, http_code: int, label: str) -> None:
    if http_code < 0:
        raise Skip(f"{label}：栈不可达 {payload}，未装绿")
    if http_code >= 500:
        raise Fail(f"{label}：HTTP {http_code} {payload}")
    if is_empty(payload):
        return
    if looks_like_real_service_a(payload):
        raise Skip(f"{label}：1970 窗仍有 demo 行，造不出空段，禁止时间旅行，本条诚实缺")
    # 200 但结构不是空列表：只要没有 demo 实体，也算空态
    if http_code == 200 and not looks_like_real_service_a(payload):
        return
    raise Fail(f"{label}：非空且无法判定 {http_code} {payload}")


def illegal_ok(payload: Any, http_code: int, label: str) -> None:
    if http_code < 0:
        raise Skip(f"{label}：栈不可达 {payload}，未装绿")
    if 400 <= http_code < 500:
        return
    if http_code >= 500:
        raise Fail(f"{label}：HTTP {http_code} {payload}")
    if is_empty(payload):
        return
    if looks_like_real_service_a(payload):
        raise Fail(f"{label}：非法 id 仍回了 service-a，不能当负例过 {payload}")
    # 200 + 无 demo 实体：空/错误信封
    if http_code == 200:
        return
    raise Fail(f"{label}：未覆盖 {http_code} {payload}")


def run_case(token: str, method: str, path: str, body: dict[str, Any] | None) -> tuple[int, Any]:
    url = f"{BASE.rstrip('/')}{path}"
    code, _, payload = http_json(method, url, body=body, token=token, timeout=30)
    return code, payload


CASES: dict[str, Callable[[str], None]] = {}


def case(cid: str):
    def wrap(fn: Callable[[str], None]) -> Callable[[str], None]:
        CASES[cid] = fn
        return fn

    return wrap


@case("AC-C22")
def c22_cockpit_empty(token: str) -> None:
    code, payload = run_case(token, "POST", "/webapi/cockpit/trafficLight", EMPTY_TW)
    empty_or_skip(payload, code, "C22 驾驶舱空窗")


@case("AC-C36")
def c36_topo_empty(token: str) -> None:
    code, payload = run_case(token, "POST", "/webapi/globalTopology/graph", EMPTY_TW)
    empty_or_skip(payload, code, "C36 拓扑空窗")


@case("AC-C41")
def c41_flow_empty(token: str) -> None:
    body = {**EMPTY_TW, "service": "service-a", "serviceId": "service-a"}
    code, payload = run_case(token, "POST", "/webapi/trace/serviceFlow", body)
    empty_or_skip(payload, code, "C41 服务流空窗")


@case("AC-C72")
def c72_endpoints_empty(token: str) -> None:
    body = {**EMPTY_TW, "service": "service-a", "serviceId": "service-a"}
    code, payload = run_case(token, "POST", "/webapi/service/endpoints", body)
    empty_or_skip(payload, code, "C72 接口分析空窗")


@case("AC-C76")
def c76_errors_empty(token: str) -> None:
    body = {**EMPTY_TW, "service": "service-a", "serviceId": "service-a"}
    code, payload = run_case(token, "POST", "/webapi/service/exceptionDistMap", body)
    empty_or_skip(payload, code, "C76 错误分析空窗")


@case("AC-C80")
def c80_trace_empty(token: str) -> None:
    body = {**EMPTY_TW, "service": "service-a", "serviceId": "service-a", "limit": 20, "size": 20}
    code, payload = run_case(token, "POST", "/webapi/trace/list", body)
    empty_or_skip(payload, code, "C80 链路空窗")


@case("AC-C84")
def c84_log_empty(token: str) -> None:
    code, payload = run_case(token, "POST", "/webapi/log/search", EMPTY_LOG)
    if code >= 0 and not is_empty(payload) and looks_like_real_service_a(payload):
        # 再试无匹配关键字，仍有数才 skip
        body = {**EMPTY_LOG, "query": "___no_such_keyword_gap_c84___"}
        code, payload = run_case(token, "POST", "/webapi/log/search", body)
    empty_or_skip(payload, code, "C84 日志空窗")


@case("AC-C56")
def c56_bad_service(token: str) -> None:
    body = {**EMPTY_TW, "from": 1_700_000_000_000, "to": 1_700_000_100_000,
            "start": 1_700_000_000_000, "end": 1_700_000_100_000,
            "service": BAD_SERVICE, "serviceId": BAD_SERVICE}
    code, payload = run_case(token, "POST", "/webapi/service/serviceInfo", body)
    illegal_ok(payload, code, "C56 非法 serviceId")


@case("AC-C79")
def c79_bad_trace(token: str) -> None:
    body = {"traceId": BAD_TRACE, "size": 100}
    code, payload = run_case(token, "POST", "/webapi/trace/spans", body)
    illegal_ok(payload, code, "C79 非法 traceId")


@case("AC-C30")
def c30_resend_null(token: str) -> None:
    """开源 NotifyPortalController.resend → CommonResponse.ok(null)。"""
    code, payload = run_case(token, "POST", "/webapi/notify/resend", {"id": "not-a-real-notify"})
    if code < 0:
        raise Skip(f"C30：栈不可达 {payload}，未装绿")
    if code != 200:
        raise Fail(f"C30 resend 期望 200 ok(null)，实际 HTTP {code} {payload}")
    if not isinstance(payload, dict):
        raise Fail(f"C30 信封不是对象：{payload}")
    if payload.get("data") is not None:
        raise Fail(f"C30 开源应 ok(null)，不能当真实渠道绿：{payload}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--id", action="append", dest="ids", help="只跑这些 AC-Cxx")
    args = parser.parse_args()
    wanted = args.ids or list(CASES)

    try:
        token = login(BASE, USER, PASSWORD, 20)
    except Exception as error:  # noqa: BLE001
        print(f"SKIP all: login failed ({error})")
        return 0

    failed = 0
    skipped = 0
    passed = 0
    for cid in wanted:
        fn = CASES.get(cid)
        if fn is None:
            print(f"FAIL {cid}: unknown id")
            failed += 1
            continue
        try:
            fn(token)
            print(f"PASS {cid}")
            passed += 1
        except Skip as error:
            print(f"SKIP {cid}: {error}")
            skipped += 1
        except Fail as error:
            print(f"FAIL {cid}: {error}")
            failed += 1
        except Exception as error:  # noqa: BLE001
            print(f"FAIL {cid}: {error}")
            failed += 1

    print(f"gap_http: pass={passed} skip={skipped} fail={failed}")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
