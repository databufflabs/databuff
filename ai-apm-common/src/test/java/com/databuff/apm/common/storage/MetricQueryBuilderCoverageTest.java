package com.databuff.apm.common.storage;

import com.databuff.apm.common.util.PortalServiceIdResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MetricQueryBuilderCoverageTest {

    private static final String DB = "databuff";
    private static final long FROM = 0L;
    private static final long TO = 3_600_000L;
    private static final String SERVICE_ID = PortalServiceIdResolver.normalize("checkout");

    @Test
    void buildsCallSpanCountSqlVariants() {
        String simple = MetricQueryBuilder.callSpanCountSql(
                DB, FROM, TO, SERVICE_ID, null, null, null, null, null, null, null, null, null, null);
        assertThat(simple).contains("COUNT(*)").contains("trace_dc_span");

        String withWallClock = MetricQueryBuilder.callSpanCountSql(
                DB, FROM, TO, "2026-06-01 00:00:00", "2026-06-01 01:00:00",
                SERVICE_ID, "inst-1", null, null, null, null, "/api", "GET", null, true, "http");
        assertThat(withWallClock).contains("FLOOR(`end` / 1000000 / 60000)").contains("inst-1");
        assertThat(withWallClock).doesNotContain("`startTime` >=");
    }

    @Test
    void buildsComponentDistinctAndUpstreamSql() {
        assertThat(MetricQueryBuilder.componentDistinctServicesSql(
                DB, "metric_service_http", FROM, TO, "service"))
                .contains("metric_service_http").contains("DISTINCT");
        assertThat(MetricQueryBuilder.componentWebUpstreamSummarySql(
                DB, "metric_service_http", Set.of("checkout"), FROM, TO, 20))
                .contains("metric_service_http").contains("GROUP BY");
    }

    @Test
    void buildsDbDownstreamAndMetricDistinctSql() {
        assertThat(MetricQueryBuilder.dbDownstreamSummarySql(DB, List.of("checkout"), FROM, TO, 10))
                .contains("metric_service_db");
        assertThat(MetricQueryBuilder.dbMetricDistinctSql(DB, "dbType", FROM, TO, 5))
                .contains("metric_service_db");
        assertThat(MetricQueryBuilder.httpMetricDistinctSql(DB, "httpCode", FROM, TO, 5))
                .contains("metric_service_http");
        assertThat(MetricQueryBuilder.metricDistinctSql(DB, "metric_service", "service", FROM, TO, 5))
                .contains("service").contains("metric_service");
    }

    @Test
    void buildsDistinctResourceAndScalarSql() {
        assertThat(MetricQueryBuilder.distinctResourceValuesSql(
                DB, "metric_service_http", "url", FROM, TO, "`service_id` = 'x'", 10))
                .contains("url").contains("LIMIT 10");
        assertThat(MetricQueryBuilder.metricAggregateScalarSql(
                DB, "metric_service", "cnt", FROM, TO, "`service_id` = 'x'"))
                .contains("SUM(`cnt`)");
        assertThat(MetricQueryBuilder.metricAggregateScalarSql(
                DB, "metric_service", "cnt", FROM, TO, "`service_id` = 'x'", "avg"))
                .contains("AVG(`cnt`)");
        assertThat(MetricQueryBuilder.metricErrorPctScalarSql(DB, "metric_service", FROM, TO, ""))
                .contains("error").contains("cnt");
        assertThat(MetricQueryBuilder.metricAvgDurationScalarSql(DB, "metric_service", FROM, TO, ""))
                .contains("sumDuration");
    }

    @Test
    void buildsHttpAndRpcSummarySql() {
        assertThat(MetricQueryBuilder.httpCallStatsSummarySql(
                DB, List.of("checkout"), List.of("gateway"), FROM, TO, "/api", 1, 0))
                .contains("metric_service_http");
        assertThat(MetricQueryBuilder.rpcEndpointSummarySql(
                DB, List.of("checkout"), FROM, TO, 20, "rpc", 1, 0, List.of("gateway")))
                .contains("metric_service_rpc");
    }

    @Test
    void buildsServiceFlowSqlFamily() {
        assertThat(MetricQueryBuilder.serviceFlowEntryPathIdsSql(DB, FROM, TO, ""))
                .contains("entryPathId");
        assertThat(MetricQueryBuilder.serviceFlowEntryInterfacePathIdsSql(
                DB, FROM, TO, "path-1", "GET /api"))
                .contains("path-1");
        assertThat(MetricQueryBuilder.multipleServiceFlowSql(
                DB, FROM, TO, "path-1", List.of("iface-1", "iface-2")))
                .contains("metric_service_flow");
        assertThat(MetricQueryBuilder.serviceFlowSrcServicesSql(DB, "checkout", FROM, TO, 10))
                .contains("parentService").contains("checkout");
        String filter = MetricQueryBuilder.serviceFlowEntryServiceFilter(SERVICE_ID, "checkout", "GET /");
        assertThat(filter).contains(SERVICE_ID).contains("checkout");
    }

    @Test
    void buildsExceptionSqlFamily() {
        assertThat(MetricQueryBuilder.exceptionListCountSql(
                DB, FROM, TO, SERVICE_ID, "inst", "/api", "NPE", "/root"))
                .contains("COUNT").contains("metric_service_exception");
        assertThat(MetricQueryBuilder.exceptionDistFromMetricSql(
                DB, FROM, TO, SERVICE_ID, "inst", "Timeout"))
                .contains("exceptionName");
        assertThat(MetricQueryBuilder.exceptionDistFromMetricResourceSql(
                DB, FROM, TO, SERVICE_ID, "inst", "/pay"))
                .contains("resource");
        assertThat(MetricQueryBuilder.httpErrorResourceDistSql(DB, FROM, TO, SERVICE_ID, "/api"))
                .contains("metric_service_http");
    }
}
