"""接口分析 /appMonitor/serviceAnalysis（含接口详情下钻页）"""

from __future__ import annotations

from pathlib import Path

from ...common import ApiCase, resource_body, service_body


CASE_DIR = Path(__file__).resolve().parent


def build_cases(frm_ms: int, to_ms: int) -> list[ApiCase]:
    page = "接口分析"
    sb = service_body(frm_ms, to_ms)
    # portal spanList 读 size（不是 limit）；与 expected 条数对齐
    sb_limit = service_body(frm_ms, to_ms, size=40)
    rb = resource_body(frm_ms, to_ms)
    return [
        ApiCase(page, "服务端点列表", "POST", "/webapi/service/endpoints", sb, CASE_DIR),
        ApiCase(page, "接口上下游", "POST", "/webapi/slowInterface/getResourceRelations", rb, CASE_DIR),
        ApiCase(page, "接口关系", "POST", "/webapi/service/resourceRelation", rb, CASE_DIR),
        ApiCase(page, "接口请求趋势", "POST", "/webapi/trace/allCnt", rb, CASE_DIR),
        ApiCase(page, "慢接口趋势", "POST", "/webapi/trace/slowCnt", rb, CASE_DIR),
        ApiCase(page, "错误接口趋势", "POST", "/webapi/trace/errorCnt", rb, CASE_DIR),
        ApiCase(page, "接口别名更新", "POST", "/webapi/slowInterface/updateResourceAlias", {**rb, "alias": "checkout-demo"}, CASE_DIR),
        ApiCase(page, "接口详情", "POST", "/webapi/service/resourceInfo", rb, CASE_DIR),
        ApiCase(page, "接口指标统计", "POST", "/webapi/service/metric_stats", rb, CASE_DIR),
        ApiCase(page, "接口耗时分解", "POST", "/webapi/service/resource_stats", rb, CASE_DIR),
        ApiCase(page, "span 列表", "POST", "/webapi/trace/spanList", sb_limit, CASE_DIR),
        ApiCase(page, "慢 span", "POST", "/webapi/trace/slowSpanList", sb_limit, CASE_DIR),
        ApiCase(page, "错误 span", "POST", "/webapi/trace/errorSpanList", sb_limit, CASE_DIR),
        ApiCase(page, "接口分位", "POST", "/webapi/trace/resourcePercent", rb, CASE_DIR),
    ]
