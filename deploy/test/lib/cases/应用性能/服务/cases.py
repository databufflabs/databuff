"""服务 /appMonitor/service（含服务详情、实例、调用分析、连接池等下钻页）"""

from __future__ import annotations

from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

from ...common import (
    ApiCase,
    DEMO_SERVICE_A,
    DEMO_SERVICE_A_ID,
    call_pair_body,
    metric_chart_body,
    service_body,
    service_filters,
    service_id_filters,
    time_window,
)


CASE_DIR = Path(__file__).resolve().parent
SHANGHAI = ZoneInfo("Asia/Shanghai")
HOUR_MS = 3_600_000


def _fmt_portal_time(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000, tz=SHANGHAI).strftime("%Y-%m-%d %H:%M:%S")


def instance_top5_trend_body(to_ms: int, *, chart_type: str, metric: str) -> dict:
    """页顶最近 1 小时：对齐已批缺陷方案 T1–T4，不要沿用无 type 的服务趋势图 body。"""
    start_ms = to_ms - HOUR_MS
    return {
        "serviceId": DEMO_SERVICE_A_ID,
        "startTime": _fmt_portal_time(start_ms),
        "endTime": _fmt_portal_time(to_ms),
        "interval": 60,
        "type": chart_type,
        "metric": metric,
    }


def build_cases(frm_ms: int, to_ms: int) -> list[ApiCase]:
    page = "服务"
    tw = time_window(frm_ms, to_ms)
    sb = service_body(frm_ms, to_ms)
    cp = call_pair_body(frm_ms, to_ms)
    filters = service_filters()
    sec_frm = frm_ms // 1000
    sec_to = to_ms // 1000
    qs = f"start={frm_ms}&end={to_ms}&serviceId={DEMO_SERVICE_A}"

    return [
        ApiCase(page, "服务列表", "POST", "/webapi/service/list", tw, CASE_DIR),
        ApiCase(page, "服务列表趋势", "POST", "/webapi/service/serviceListTrendChart", tw, CASE_DIR),
        ApiCase(page, "基础服务列表", "POST", "/webapi/service/basicServices", tw, CASE_DIR),
        ApiCase(page, "全量服务列表", "POST", "/webapi/service/basicAllServices", tw, CASE_DIR),
        ApiCase(page, "K8s 命名空间", "POST", "/webapi/service/k8sNamespaceList", tw, CASE_DIR),
        ApiCase(page, "服务红绿灯", "POST", "/webapi/cockpit/trafficLight", tw, CASE_DIR),
        ApiCase(page, "健康配置", "GET", "/webapi/cockpit/getConfig", None, CASE_DIR),
        ApiCase(page, "实体健康数据", "POST", "/webapi/cockpit/entityData", tw, CASE_DIR),
        ApiCase(page, "告警趋势", "POST", "/webapi/cockpit/countServiceAlarms", tw, CASE_DIR),
        ApiCase(page, "告警总量", "POST", "/webapi/cockpit/countServiceAlarmsTotal", tw, CASE_DIR),
        ApiCase(page, "服务详情", "POST", "/webapi/service/serviceInfo", sb, CASE_DIR),
        ApiCase(page, "服务趋势图", "POST", "/webapi/service/serviceDetailTrendChart", sb, CASE_DIR),
        ApiCase(
            page,
            "按实例排行Top5平均耗时",
            "POST",
            "/webapi/service/serviceDetailTrendChart",
            instance_top5_trend_body(to_ms, chart_type="top", metric="avgTime"),
            CASE_DIR,
        ),
        ApiCase(
            page,
            "按实例排行Top5请求数",
            "POST",
            "/webapi/service/serviceDetailTrendChart",
            instance_top5_trend_body(to_ms, chart_type="top", metric="reqCount"),
            CASE_DIR,
        ),
        ApiCase(
            page,
            "按实例排行Top5错误率",
            "POST",
            "/webapi/service/serviceDetailTrendChart",
            instance_top5_trend_body(to_ms, chart_type="top", metric="errRate"),
            CASE_DIR,
        ),
        ApiCase(
            page,
            "服务均值趋势无实例标签",
            "POST",
            "/webapi/service/serviceDetailTrendChart",
            instance_top5_trend_body(to_ms, chart_type="service", metric="avgTime"),
            CASE_DIR,
        ),
        ApiCase(page, "服务图表", "POST", "/webapi/service/graph_stats", sb, CASE_DIR),
        ApiCase(page, "响应时间分布", "POST", "/webapi/service/distribution_stats", sb, CASE_DIR),
        ApiCase(page, "请求 Top", "POST", "/webapi/service/reqTop", sb, CASE_DIR),
        ApiCase(page, "资源来源服务", "POST", "/webapi/service/reqContributorService", sb, CASE_DIR),
        ApiCase(page, "按组件类型资源", "POST", "/webapi/service/resources", sb, CASE_DIR),
        ApiCase(page, "来源服务分组", "POST", "/webapi/service/resourcesGroupBy", sb, CASE_DIR),
        ApiCase(page, "中间件列表", "POST", "/webapi/service/middlewareList", tw, CASE_DIR),
        ApiCase(page, "基础服务实例", "GET", f"/webapi/service/getBasicServiceInstance?serviceId={DEMO_SERVICE_A}", None, CASE_DIR),
        ApiCase(page, "服务实例列表", "GET", f"/webapi/service/getServiceInstance?{qs}", None, CASE_DIR),
        ApiCase(page, "服务实例关联", "GET", f"/webapi/service/getServiceInstanceRelations?{qs}", None, CASE_DIR),
        ApiCase(page, "实例数量", "POST", "/webapi/trace/serviceInstanceCounts", sb, CASE_DIR),
        ApiCase(page, "v1 服务实例", "POST", "/webapi/api/v1/apm/trace/serviceInstances", {**sb, "limit": 50}, CASE_DIR),
        ApiCase(page, "v1 K8s 命名空间", "POST", "/webapi/api/v1/apm/trace/k8sNamespaces", sb, CASE_DIR),
        ApiCase(page, "v1 服务 K8s 映射", "POST", "/webapi/api/v1/apm/trace/serviceK8sNamespaces", sb, CASE_DIR),
        ApiCase(page, "v1 实例数量", "POST", "/webapi/api/v1/apm/trace/serviceInstanceCounts", sb, CASE_DIR),
        ApiCase(page, "portal 服务 K8s", "POST", "/webapi/trace/serviceK8sNamespaces", sb, CASE_DIR),
        ApiCase(page, "调用基本信息", "POST", "/webapi/service/call_info", cp, CASE_DIR),
        ApiCase(page, "调用关系图", "POST", "/webapi/service/call_graph_stats", cp, CASE_DIR),
        ApiCase(page, "调用端点", "POST", "/webapi/service/call_endpoints", cp, CASE_DIR),
        ApiCase(page, "连接池名称", "POST", "/webapi/service/pool_get_names", sb, CASE_DIR),
        ApiCase(page, "调用 span", "POST", "/webapi/trace/call_spans", {**cp, "limit": 20}, CASE_DIR),
        ApiCase(page, "portal 指标序列", "POST", "/webapi/metric/serviceSeries", tw, CASE_DIR),
        ApiCase(page, "portal HTTP 端点", "POST", "/webapi/metric/httpEndpoints", {**sb, "limit": 50}, CASE_DIR),
        ApiCase(page, "portal HTTP 延迟", "POST", "/webapi/metric/httpLatency", sb, CASE_DIR),
        ApiCase(page, "portal 指标图表", "POST", "/webapi/metric/chart", sb, CASE_DIR),
        ApiCase(page, "portal lastTags", "POST", "/webapi/metric/lastTags", {
            "start": frm_ms, "end": to_ms, "metrics": ["jvm.thread_count"], "by": ["serviceInstance"], "from": service_id_filters(),
        }, CASE_DIR),
        ApiCase(page, "portal metric series", "POST", "/webapi/metric/series", {
            "metric": "jvm.thread_count", "start": sec_frm, "end": sec_to, "from": service_id_filters(),
        }, CASE_DIR),
        ApiCase(page, "v1 服务指标序列", "POST", "/webapi/api/v1/apm/metric/serviceSeries", tw, CASE_DIR),
        ApiCase(page, "v1 HTTP 端点", "POST", "/webapi/api/v1/apm/metric/httpEndpoints", {**sb, "limit": 50}, CASE_DIR),
        ApiCase(page, "v1 HTTP 延迟", "POST", "/webapi/api/v1/apm/metric/httpLatency", sb, CASE_DIR),
        ApiCase(page, "v1 lastTags", "POST", "/webapi/api/v1/apm/metric/lastTags", {
            "start": frm_ms, "end": to_ms, "metrics": ["jvm.thread_count"], "by": ["serviceInstance"], "from": service_id_filters(),
        }, CASE_DIR),
        ApiCase(page, "v1 metric series", "POST", "/webapi/api/v1/apm/metric/series", {
            "metric": "jvm.thread_count", "start": sec_frm, "end": sec_to, "from": service_id_filters(),
        }, CASE_DIR),
        ApiCase(page, "v1 指标图表", "POST", "/webapi/api/v1/apm/metric/chart", sb, CASE_DIR),
        ApiCase(page, "线程池活跃线程", "POST", "/webapi/metrics/exploreMetricByGroupGraph",
                metric_chart_body(frm_ms, to_ms, "threadpool.active_count"), CASE_DIR),
        ApiCase(page, "线程池队列长度", "POST", "/webapi/metrics/exploreMetricByGroupGraph",
                metric_chart_body(frm_ms, to_ms, "threadpool.queue_size"), CASE_DIR),
        ApiCase(page, "对象池借用数", "POST", "/webapi/metrics/exploreMetricByGroupGraph",
                metric_chart_body(frm_ms, to_ms, "objectpool.borrowed_count"), CASE_DIR),
        ApiCase(page, "HTTP 连接池活跃连接", "POST", "/webapi/metrics/exploreMetricByGroupGraph",
                metric_chart_body(frm_ms, to_ms, "http.client.pool.active"), CASE_DIR),
        ApiCase(page, "DB 连接池活跃连接", "POST", "/webapi/metrics/exploreMetricByGroupGraph",
                metric_chart_body(frm_ms, to_ms, "db.client.connections.active"), CASE_DIR),
    ]
