"""安装部署只读：部署状态平台指标。数据接入 /api6972 不在本仓，不写假绿。"""

from __future__ import annotations

from pathlib import Path

from ..common import ApiCase, time_window


CASE_DIR = Path(__file__).resolve().parent
MODULE = "安装部署"
PAGE = "安装部署"


def build_cases(frm_ms: int, to_ms: int) -> list[ApiCase]:
    tw = time_window(frm_ms, to_ms)
    summary_body = {
        **tw,
        "metricPrefixes": ["ingest.otel.", "ingest.sw.", "web.query."],
    }
    query_body = {
        **tw,
        "metricPrefixes": ["ingest.otel."],
        "stepSeconds": 60,
    }
    return [
        ApiCase(PAGE, "部署状态总览", "POST", "/webapi/platform/metrics/summary", summary_body, CASE_DIR, module=MODULE),
        ApiCase(PAGE, "部署状态时序", "POST", "/webapi/platform/metrics/query", query_body, CASE_DIR, module=MODULE),
    ]
