"""链路追踪 /appMonitor/trace（含调用链详情下钻页）"""

from __future__ import annotations

from pathlib import Path

from ...common import ApiCase, service_body, trace_spans_body


CASE_DIR = Path(__file__).resolve().parent


def build_cases(frm_ms: int, to_ms: int) -> list[ApiCase]:
    page = "链路追踪"
    sb = service_body(frm_ms, to_ms)
    # v1 SpanListRequest 用 limit；portal 列表用 size。此处 v1 span 列表 expected 为 20 条
    sb_limit = service_body(frm_ms, to_ms, size=20, limit=20)
    return [
        ApiCase(page, "筛选参数", "POST", "/webapi/trace/query_params", sb, CASE_DIR),
        ApiCase(page, "调用链列表", "POST", "/webapi/trace/list", sb_limit, CASE_DIR),
        ApiCase(page, "请求数量图", "POST", "/webapi/trace/cnt_graph_stats", sb, CASE_DIR),
        ApiCase(page, "错误数量图", "POST", "/webapi/trace/error_cnt_graph_stats", sb, CASE_DIR),
        ApiCase(page, "响应时间图", "POST", "/webapi/trace/graph_stats", sb, CASE_DIR),
        ApiCase(page, "v1 span 列表", "POST", "/webapi/api/v1/apm/trace/spanList", sb_limit, CASE_DIR),
        ApiCase(
            page,
            "调用链 span 类型",
            "POST",
            "/webapi/trace/spans",
            trace_spans_body(),
            CASE_DIR,
            needs_checkout_trace_id=True,
        ),
        ApiCase(
            page,
            "v1 调用链详情",
            "POST",
            "/webapi/api/v1/apm/trace/detail",
            {
                "service": sb["service"],
                "serviceId": sb["serviceId"],
                "from": sb["from"],
                "to": sb["to"],
                "traceId": "",
                "limit": 20,
            },
            CASE_DIR,
        ),
    ]
