"""接口分析 /appMonitor/serviceAnalysis（含接口详情下钻页）"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from ...common import (
    DEMO_ENDPOINT,
    DEMO_SERVICE_A,
    DEMO_SERVICE_A_ID,
    DEMO_SERVICE_B,
    DEMO_SERVICE_B_ID,
    ApiCase,
    resource_body,
    service_body,
    time_window,
)


CASE_DIR = Path(__file__).resolve().parent

# Drill-down fixtures aligned with demo endpoints list (serviceAnalysis → resourceDetail).
DEMO_RPC_RESOURCE = "Dubbo DemoOrderService.findInventory"
DEMO_MQ_RESOURCE = "order-events publish"
DEMO_SQL_RESOURCE = "SELECT id, amount, status FROM demo_order WHERE id = ?"
DEMO_SQL_ERROR_RESOURCE = "SELECT sku, available FROM demo_inventory WHERE sku = ?"
DEMO_REDIS_RESOURCE = "GET cart"
DEMO_MYSQL_ID = "c72cc83a8831e407"
DEMO_REDIS_ID = "94d612e1c2166206"
DEMO_KAFKA_ID = "c1fbe6792ea65d41"


def interface_detail_body(
    frm_ms: int,
    to_ms: int,
    *,
    component_type: str,
    service_id: str,
    service: str,
    endpoint: str,
    **extra: Any,
) -> dict[str, Any]:
    """Match resourceDetail query: HTTP uses url path; others use resource."""
    body = {
        **time_window(frm_ms, to_ms),
        "fromTime": frm_ms,
        "toTime": to_ms,
        "serviceId": service_id,
        "service": service,
        "componentType": component_type,
        "size": 40,
        "offset": 0,
        "interval": 60,
        **extra,
    }
    if component_type == "service.http":
        body["url"] = endpoint
    else:
        body["resource"] = endpoint
    # Middleware resource detail keeps serviceId as the target (same as UI dbTarget=1).
    if component_type in ("service.db", "service.redis", "service.mq"):
        body.setdefault("dbTarget", 1)
    return body


def baseinfo_graph_body(detail: dict[str, Any]) -> dict[str, Any]:
    """resourceDetail tab-baseinfo → POST /service/graph_stats."""
    return {
        **detail,
        "isIn": 1,
        "graphStats": ["callCnts", "errorCnts", "avgLatencys"],
    }


def build_cases(frm_ms: int, to_ms: int) -> list[ApiCase]:
    page = "接口分析"
    sb = service_body(frm_ms, to_ms)
    # portal spanList 读 size（不是 limit）；与 expected 条数对齐
    sb_limit = service_body(frm_ms, to_ms, size=40)
    rb = resource_body(frm_ms, to_ms)

    http = interface_detail_body(
        frm_ms, to_ms,
        component_type="service.http",
        service_id=DEMO_SERVICE_A_ID,
        service=DEMO_SERVICE_A,
        endpoint=DEMO_ENDPOINT,
    )
    rpc = interface_detail_body(
        frm_ms, to_ms,
        component_type="service.rpc",
        service_id=DEMO_SERVICE_B_ID,
        service=DEMO_SERVICE_B,
        endpoint=DEMO_RPC_RESOURCE,
    )
    mq = interface_detail_body(
        frm_ms, to_ms,
        component_type="service.mq",
        service_id=DEMO_KAFKA_ID,
        service="[kafka]order-events",
        endpoint=DEMO_MQ_RESOURCE,
    )
    sql = interface_detail_body(
        frm_ms, to_ms,
        component_type="service.db",
        service_id=DEMO_MYSQL_ID,
        service="[mysql]demo_apm",
        endpoint=DEMO_SQL_RESOURCE,
    )
    sql_error = interface_detail_body(
        frm_ms, to_ms,
        component_type="service.db",
        service_id=DEMO_MYSQL_ID,
        service="[mysql]demo_apm",
        endpoint=DEMO_SQL_ERROR_RESOURCE,
        isIn=1,
    )
    redis = interface_detail_body(
        frm_ms, to_ms,
        component_type="service.redis",
        service_id=DEMO_REDIS_ID,
        service="[redis]redis:6379",
        endpoint=DEMO_REDIS_RESOURCE,
    )

    # Mirror resourceDetail tab-slow / tab-error: isIn=1 + endpoint filter.
    http_tab = {**http, "isIn": 1}
    rpc_tab = {**rpc, "isIn": 1}
    mq_tab = {**mq, "isIn": 1}
    sql_tab = {**sql, "isIn": 1}
    redis_tab = {**redis, "isIn": 1}

    http_graph = baseinfo_graph_body(http)
    rpc_graph = baseinfo_graph_body(rpc)
    mq_graph = baseinfo_graph_body(mq)
    sql_graph = baseinfo_graph_body(sql)
    redis_graph = baseinfo_graph_body(redis)

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
        # Drill-down from serviceAnalysis tabs: trend + call history must return data.
        ApiCase(page, "HTTP接口趋势", "POST", "/webapi/trace/allCnt", http, CASE_DIR),
        ApiCase(page, "HTTP接口span列表", "POST", "/webapi/trace/spanList", http, CASE_DIR),
        ApiCase(page, "RPC接口趋势", "POST", "/webapi/trace/allCnt", rpc, CASE_DIR),
        ApiCase(page, "RPC接口span列表", "POST", "/webapi/trace/spanList", rpc, CASE_DIR),
        ApiCase(page, "MQ消费趋势", "POST", "/webapi/trace/allCnt", mq, CASE_DIR),
        ApiCase(page, "MQ消费span列表", "POST", "/webapi/trace/spanList", mq, CASE_DIR),
        ApiCase(page, "SQL调用趋势", "POST", "/webapi/trace/allCnt", sql, CASE_DIR),
        ApiCase(page, "SQL调用span列表", "POST", "/webapi/trace/spanList", sql, CASE_DIR),
        ApiCase(page, "Redis调用趋势", "POST", "/webapi/trace/allCnt", redis, CASE_DIR),
        ApiCase(page, "Redis调用span列表", "POST", "/webapi/trace/spanList", redis, CASE_DIR),
        # resourceDetail baseinfo: resourceInfo + graph_stats (call/error/latency).
        ApiCase(page, "HTTP接口详情", "POST", "/webapi/service/resourceInfo", http, CASE_DIR),
        ApiCase(page, "HTTP接口基础指标", "POST", "/webapi/service/graph_stats", http_graph, CASE_DIR),
        ApiCase(page, "RPC接口详情", "POST", "/webapi/service/resourceInfo", rpc, CASE_DIR),
        ApiCase(page, "RPC接口基础指标", "POST", "/webapi/service/graph_stats", rpc_graph, CASE_DIR),
        ApiCase(page, "MQ消费详情", "POST", "/webapi/service/resourceInfo", mq, CASE_DIR),
        ApiCase(page, "MQ消费基础指标", "POST", "/webapi/service/graph_stats", mq_graph, CASE_DIR),
        ApiCase(page, "SQL调用详情", "POST", "/webapi/service/resourceInfo", sql, CASE_DIR),
        ApiCase(page, "SQL调用基础指标", "POST", "/webapi/service/graph_stats", sql_graph, CASE_DIR),
        ApiCase(page, "Redis调用详情", "POST", "/webapi/service/resourceInfo", redis, CASE_DIR),
        ApiCase(page, "Redis调用基础指标", "POST", "/webapi/service/graph_stats", redis_graph, CASE_DIR),
        # Slow / error tabs (resourceDetail tab-slow / tab-error).
        ApiCase(page, "HTTP慢调用趋势", "POST", "/webapi/trace/slowCnt", http_tab, CASE_DIR),
        ApiCase(page, "HTTP慢调用span列表", "POST", "/webapi/trace/slowSpanList", http_tab, CASE_DIR),
        ApiCase(page, "HTTP错误调用趋势", "POST", "/webapi/trace/errorCnt", http_tab, CASE_DIR),
        ApiCase(page, "HTTP错误调用span列表", "POST", "/webapi/trace/errorSpanList", http_tab, CASE_DIR),
        ApiCase(page, "RPC慢调用趋势", "POST", "/webapi/trace/slowCnt", rpc_tab, CASE_DIR),
        ApiCase(page, "RPC慢调用span列表", "POST", "/webapi/trace/slowSpanList", rpc_tab, CASE_DIR),
        ApiCase(page, "RPC错误调用趋势", "POST", "/webapi/trace/errorCnt", rpc_tab, CASE_DIR),
        ApiCase(page, "RPC错误调用span列表", "POST", "/webapi/trace/errorSpanList", rpc_tab, CASE_DIR),
        ApiCase(page, "MQ慢调用趋势", "POST", "/webapi/trace/slowCnt", mq_tab, CASE_DIR),
        ApiCase(page, "MQ慢调用span列表", "POST", "/webapi/trace/slowSpanList", mq_tab, CASE_DIR),
        ApiCase(page, "MQ错误调用趋势", "POST", "/webapi/trace/errorCnt", mq_tab, CASE_DIR),
        ApiCase(page, "MQ错误调用span列表", "POST", "/webapi/trace/errorSpanList", mq_tab, CASE_DIR),
        ApiCase(page, "SQL慢调用趋势", "POST", "/webapi/trace/slowCnt", sql_tab, CASE_DIR),
        ApiCase(page, "SQL慢调用span列表", "POST", "/webapi/trace/slowSpanList", sql_tab, CASE_DIR),
        ApiCase(page, "SQL错误调用趋势", "POST", "/webapi/trace/errorCnt", sql_error, CASE_DIR),
        ApiCase(page, "SQL错误调用span列表", "POST", "/webapi/trace/errorSpanList", sql_error, CASE_DIR),
        ApiCase(page, "Redis慢调用趋势", "POST", "/webapi/trace/slowCnt", redis_tab, CASE_DIR),
        ApiCase(page, "Redis慢调用span列表", "POST", "/webapi/trace/slowSpanList", redis_tab, CASE_DIR),
        ApiCase(page, "Redis错误调用趋势", "POST", "/webapi/trace/errorCnt", redis_tab, CASE_DIR),
        ApiCase(page, "Redis错误调用span列表", "POST", "/webapi/trace/errorSpanList", redis_tab, CASE_DIR),
    ]
