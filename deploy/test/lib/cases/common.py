"""应用性能测试用例公共类型与请求体构造."""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Optional

DEMO_ENDPOINT = "/demo/checkout"
DEMO_SERVICE_A = "service-a"
DEMO_SERVICE_B = "service-b"
DEMO_SERVICE_A_ID = "9bf61532d56eb7b5"
DEMO_SERVICE_B_ID = "5457a0119281bb98"
DEMO_ENTRY_PATH_ID = "-4605470139737123236"
DEMO_CHECKOUT_RESOURCE = "GET /demo/checkout"
DEMO_MYSQL_DEMO_APM_ID = "c72cc83a8831e407"
DEMO_EXCEPTION_INSUFFICIENT_STOCK = "InsufficientStockException"
DEMO_HOST_A = "demo-host-a"
DEMO_HOST_B = "demo-host-b"
DEMO_INSTANCE_A = "service-a-1"
DEMO_INSTANCE_B = "service-b-1"


@dataclass
class ApiCase:
    group: str
    name: str
    method: str
    path: str
    body: Optional[dict[str, Any]]
    case_dir: Path
    module: str = "应用性能"
    expect_status: int = 200
    needs_checkout_trace_id: bool = False

    @property
    def expected_path(self) -> Path:
        return self.case_dir / "expected" / f"{self.name}.json"

    @property
    def expected_json(self) -> dict[str, Any] | list[Any]:
        path = self.expected_path
        if not path.exists():
            raise FileNotFoundError(f"expected json not found: {path}")
        return json.loads(path.read_text(encoding="utf-8"))


MODULE_APP_MONITOR = "应用性能"


def time_window(frm_ms: int, to_ms: int) -> dict[str, Any]:
    return {"from": frm_ms, "to": to_ms, "start": frm_ms, "end": to_ms}


def service_body(frm_ms: int, to_ms: int, service: str = DEMO_SERVICE_A, **extra: Any) -> dict[str, Any]:
    body = time_window(frm_ms, to_ms)
    body["service"] = service
    body["serviceId"] = service
    body.update(extra)
    return body


def service_level_flow_body(frm_ms: int, to_ms: int, **extra: Any) -> dict[str, Any]:
    """POST /webapi/trace/serviceFlow — aggregated service-level tree."""
    return service_body(frm_ms, to_ms, service=DEMO_SERVICE_A, **extra)


def interface_level_flow_body(frm_ms: int, to_ms: int, **extra: Any) -> dict[str, Any]:
    """POST /webapi/trace/multipleServiceFlow — interface-level tree for checkout entry."""
    return service_body(
        frm_ms,
        to_ms,
        service=DEMO_SERVICE_A,
        entrypointPathId=DEMO_ENTRY_PATH_ID,
        resource=DEMO_CHECKOUT_RESOURCE,
        **extra,
    )


def trace_spans_body(**extra: Any) -> dict[str, Any]:
    """POST /webapi/trace/spans — trace detail flame tree (requires traceId)."""
    body: dict[str, Any] = {"size": 100}
    body.update(extra)
    return body


def error_span_list_body(frm_ms: int, to_ms: int, **extra: Any) -> dict[str, Any]:
    """Error detail drill-down: web service filter should include virtual DB caller spans."""
    body = service_body(
        frm_ms,
        to_ms,
        service=DEMO_SERVICE_B,
        serviceId=DEMO_SERVICE_B_ID,
        limit=50,
    )
    body["exception"] = DEMO_EXCEPTION_INSUFFICIENT_STOCK
    body["offset"] = 0
    body["size"] = 50
    body["sortField"] = "start"
    body["sortOrder"] = "desc"
    body.update(extra)
    return body


def service_filters(service: str = DEMO_SERVICE_A) -> list[dict[str, Any]]:
    return [{"left": "serviceId", "operator": "=", "right": service, "connector": "AND"}]


def service_id_filters(service_id: str = DEMO_SERVICE_A_ID) -> list[dict[str, Any]]:
    return [{"left": "serviceId", "operator": "=", "right": service_id, "connector": "AND"}]


def jvm_metric_chart_body(
    frm_ms: int,
    to_ms: int,
    metric: str,
    *,
    service_id: str = DEMO_SERVICE_A_ID,
    aggs: str = "mean",
    interval: int = 60,
) -> dict[str, Any]:
    return {
        "metric": metric,
        "aggs": aggs,
        "from": service_id_filters(service_id),
        "by": [],
        "types": [],
        "start": frm_ms // 1000,
        "end": to_ms // 1000,
        "interval": interval,
    }


def call_pair_body(frm_ms: int, to_ms: int, **extra: Any) -> dict[str, Any]:
    body = time_window(frm_ms, to_ms)
    body["service"] = DEMO_SERVICE_B
    body["serviceId"] = DEMO_SERVICE_B
    body["srcService"] = DEMO_SERVICE_A
    body["srcServiceId"] = DEMO_SERVICE_A
    body["dstService"] = DEMO_SERVICE_B
    body.update(extra)
    return body


def resource_body(frm_ms: int, to_ms: int, **extra: Any) -> dict[str, Any]:
    """HTTP 接口详情请求体，对齐前端 resourceDetail：service.http 用 url（路径，无 GET/host）。"""
    body = {
        **service_body(frm_ms, to_ms),
        "componentType": "service.http",
        "url": DEMO_ENDPOINT,
        **extra,
    }
    return body


def metric_chart_body(
    frm_ms: int,
    to_ms: int,
    metric: str,
    *,
    service: str = DEMO_SERVICE_A,
    tag_filters: Optional[list[dict[str, Any]]] = None,
) -> dict[str, Any]:
    filters = service_filters(service)
    if tag_filters:
        filters.extend(tag_filters)
    return {
        "metric": metric,
        "from": filters,
        "by": [],
        "types": [],
        "aggs": "",
        "start": frm_ms // 1000,
        "end": to_ms // 1000,
    }


def log_body(frm_ms: int, to_ms: int, **extra: Any) -> dict[str, Any]:
    """POST /webapi/log/* — matches logs/index.vue fromTimeNs/toTimeNs."""
    body = {
        "fromTimeNs": str(frm_ms * 1_000_000),
        "toTimeNs": str(to_ms * 1_000_000),
    }
    body.update(extra)
    return body


def log_trend_body(frm_ms: int, to_ms: int, interval: int = 60, **extra: Any) -> dict[str, Any]:
    return log_body(frm_ms, to_ms, interval=interval, **extra)


def log_search_body(frm_ms: int, to_ms: int, **extra: Any) -> dict[str, Any]:
    return log_body(frm_ms, to_ms, size=1000, offset=0, **extra)
