"""服务流 /appMonitor/serviceFlow"""

from __future__ import annotations

from pathlib import Path

from ...common import (
    ApiCase,
    interface_level_flow_body,
    service_body,
    service_level_flow_body,
    time_window,
)


CASE_DIR = Path(__file__).resolve().parent


def build_cases(frm_ms: int, to_ms: int) -> list[ApiCase]:
    page = "服务流"
    tw = time_window(frm_ms, to_ms)
    return [
        ApiCase(page, "服务流入口", "POST", "/webapi/trace/serviceFlowEndpoint", service_body(frm_ms, to_ms), CASE_DIR),
        ApiCase(page, "服务级别服务流", "POST", "/webapi/trace/serviceFlow", service_level_flow_body(frm_ms, to_ms), CASE_DIR),
        ApiCase(
            page,
            "服务接口级别服务流",
            "POST",
            "/webapi/trace/multipleServiceFlow",
            interface_level_flow_body(frm_ms, to_ms),
            CASE_DIR,
        ),
        ApiCase(page, "服务流边 v1", "POST", "/webapi/api/v1/apm/serviceFlow/edges", tw, CASE_DIR),
    ]
