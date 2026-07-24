package com.databuff.apm.common.storage;

import com.databuff.apm.common.time.ApmTimeZones;
import com.databuff.apm.common.util.PortalServiceIdResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MetricQueryBuilderTest {

    @Test
    void buildsTrafficLightSql() {
        String sql = MetricQueryBuilder.trafficLightSql("databuff", 0L, 3_600_000L);
        assertThat(sql).contains("databuff.`metric_service`");
        assertThat(sql).contains("SUM(`error`)");
        assertThat(sql).contains("`ts` >= 0 AND `ts` < 3600000");
    }

    @Test
    void buildsSpanListSql() {
        String serviceId = PortalServiceIdResolver.normalize("checkout");
        String sql = MetricQueryBuilder.spanListSql("databuff", "checkout", 0L, 3_600_000L, 20);
        assertThat(sql).contains("databuff.`trace_dc_span`");
        assertThat(sql).contains("`serviceId` = '" + serviceId + "'");
        assertThat(sql).doesNotContain(" OR `service` = ");
        assertThat(sql).contains("LIMIT 20");
    }

    @Test
    void buildsEntrySpanListSqlForServiceIdsAndParentZero() {
        String sql = MetricQueryBuilder.spanListSql(
                "databuff",
                List.of("9bf61532d56eb7b5"),
                0L,
                3_600_000L,
                50,
                0,
                "2026-06-05 22:40:00",
                "2026-06-05 22:41:00",
                1,
                null,
                "start",
                "desc");
        assertThat(sql).contains("`is_parent` = 1");
        assertThat(sql).contains("`serviceId` = '9bf61532d56eb7b5'");
        assertThat(sql).contains("(FLOOR(`end` / 1000000 / 60000) * 60000) >= ");
        assertThat(sql).contains("(FLOOR(`end` / 1000000 / 60000) * 60000) < ");
        assertThat(sql).doesNotContain("`startTime` >=");
        assertThat(sql).contains("ORDER BY `startTime` DESC");
        assertThat(sql).contains("LIMIT 50 OFFSET 0");

        String countSql = MetricQueryBuilder.spanListCountSql(
                "databuff",
                List.of("9bf61532d56eb7b5"),
                0L,
                3_600_000L,
                "2026-06-05 22:40:00",
                "2026-06-05 22:41:00",
                1,
                null);
        assertThat(countSql).contains("COUNT(*) AS total_cnt");
        assertThat(countSql).contains("`is_parent` = 1");
        assertThat(countSql).contains("(FLOOR(`end` / 1000000 / 60000) * 60000)");
    }

    @Test
    void buildsEntrySpanListSqlWithErrorFilterOnEndMinuteBucket() {
        long fromMs = ApmTimeZones.wallClockToEpochMilli("2026-06-20 07:01:00");
        long toMs = ApmTimeZones.wallClockToEpochMilli("2026-06-20 07:02:00");
        String sql = MetricQueryBuilder.spanListSql(
                "databuff",
                List.of(),
                0L,
                0L,
                50,
                0,
                "2026-06-20 07:01:00",
                "2026-06-20 07:02:00",
                1,
                null,
                "start",
                "desc",
                null,
                null,
                1);
        assertThat(sql).contains(" AND `error` = 1 ");
        assertThat(sql).contains(">= " + fromMs);
        assertThat(sql).contains("< " + toMs);
    }

    @Test
    void buildsSpanListSqlWithExtendedFields() {
        String serviceId = PortalServiceIdResolver.normalize("checkout");
        String sql = MetricQueryBuilder.spanListSql("databuff", "checkout", 0L, 3_600_000L, 20);
        assertThat(sql).contains("serviceInstance");
        assertThat(sql).contains("meta.error.type");
        assertThat(sql).contains("`serviceId` = '" + serviceId + "'");
    }

    @Test
    void callSpanListSqlUsesLiteralPortalDatetimeText() {
        String sql = MetricQueryBuilder.callSpanListSql(
                "databuff",
                0L,
                0L,
                "2026-06-05 22:10:00",
                "2026-06-05 22:11:00",
                null,
                null,
                "9bf61532d56eb7b5",
                null,
                "dad537de7e10e098",
                null,
                "INSERT INTO demo_order_audit(order_id, channel) VALUES (?, ?)",
                null,
                null,
                false,
                "service.db",
                "start",
                "desc",
                50,
                0);
        assertThat(sql).contains("(FLOOR(`end` / 1000000 / 60000) * 60000) >= ");
        assertThat(sql).contains("(FLOOR(`end` / 1000000 / 60000) * 60000) < ");
        assertThat(sql).doesNotContain("`startTime` >=");
        assertThat(sql).contains("$.\"" + "db.statement" + "\"");
        assertThat(sql).contains("$.\"" + "db.system" + "\"");
    }

    @Test
    void callSpanListSqlBucketsBySpanEndMinuteForLongLivedSpans() {
        // Long-lived streaming RPC (e.g. flagd EventStream) starts outside the clicked
        // metric bucket but ends inside it; metric_service_rpc.ts is the span end-minute
        // bucket, so the drill-down must filter by end minute too, not startTime.
        long from = ApmTimeZones.wallClockToEpochMilli("2026-07-17 21:12:00");
        long to = ApmTimeZones.wallClockToEpochMilli("2026-07-17 21:13:00");
        String sql = MetricQueryBuilder.callSpanListSql(
                "databuff",
                from,
                to,
                "2026-07-17 21:12:00",
                "2026-07-17 21:13:00",
                null, null,
                "d7ebbef5e400c2c5", null,
                "26971b8852fe3d63", null,
                "flagd.evaluation.v1.Service/EventStream", null, null,
                false, "service.rpc",
                "start", "desc", 50, 0);
        assertThat(sql).contains("(FLOOR(`end` / 1000000 / 60000) * 60000) >= " + from);
        assertThat(sql).contains("(FLOOR(`end` / 1000000 / 60000) * 60000) < " + to);
        assertThat(sql).doesNotContain("`startTime` >=");
    }

    @Test
    void metaJsonStringQuotesDottedOtelKeys() {
        assertThat(MetricQueryBuilder.metaJsonString("db.system"))
                .isEqualTo("get_json_string(`meta`, '$.\"db.system\"')");
    }

    @Test
    void spanListSqlUsesLiteralPortalDatetimeText() {
        String sql = MetricQueryBuilder.spanListSql(
                "databuff",
                null,
                0L,
                0L,
                50,
                "2026-06-05 14:00:00",
                "2026-06-05 14:01:00");
        assertThat(sql).contains("(FLOOR(`end` / 1000000 / 60000) * 60000) >= ");
        assertThat(sql).contains("(FLOOR(`end` / 1000000 / 60000) * 60000) < ");
        assertThat(sql).doesNotContain("`startTime` >=");
    }

    @Test
    void spanListSqlFiltersByResourceDurationAndError() {
        String sql = MetricQueryBuilder.spanListSql(
                "databuff",
                List.of("9bf61532d56eb7b5"),
                0L,
                3_600_000L,
                50,
                0,
                "2026-06-06 07:17:00",
                "2026-06-06 08:17:00",
                null,
                null,
                "startTime",
                "desc",
                "/demo/checkout",
                1_000_000_000L,
                1);
        assertThat(sql).contains("COALESCE(`meta.http.url`, '') = '/demo/checkout'");
        assertThat(sql).contains("COALESCE(`meta.http.url`, '') LIKE '%/demo/checkout'");
        assertThat(sql).contains("`duration` >= 1000000000");
        assertThat(sql).contains("`error` = 1");

        String countSql = MetricQueryBuilder.spanListCountSql(
                "databuff",
                List.of("9bf61532d56eb7b5"),
                0L,
                3_600_000L,
                "2026-06-06 07:17:00",
                "2026-06-06 08:17:00",
                null,
                null,
                "/demo/checkout",
                1_000_000_000L,
                1);
        assertThat(countSql).contains("COALESCE(`meta.http.url`, '') = '/demo/checkout'");
        assertThat(countSql).contains("COALESCE(`meta.http.url`, '') LIKE '%/demo/checkout'");
        assertThat(countSql).contains("`duration` >= 1000000000");
        assertThat(countSql).contains("`error` = 1");
    }

    @Test
    void errorSpanListSqlMatchesOwnedOrCallerServiceForWebApps() {
        String sql = MetricQueryBuilder.errorSpanListSql(
                "databuff",
                List.of("5457a0119281bb98"),
                false,
                0L,
                3_600_000L,
                50,
                0,
                "2026-06-18 09:57:00",
                "2026-06-18 10:57:00",
                "start",
                "desc",
                null,
                "InsufficientStockException");
        assertThat(sql).contains("`serviceId` = '5457a0119281bb98'");
        assertThat(sql).contains("`srcServiceId` = '5457a0119281bb98'");
        assertThat(sql).contains("`error` = 1");
        assertThat(sql).contains("InsufficientStockException");

        String countSql = MetricQueryBuilder.errorSpanListCountSql(
                "databuff",
                List.of("5457a0119281bb98"),
                false,
                0L,
                3_600_000L,
                "2026-06-18 09:57:00",
                "2026-06-18 10:57:00",
                null,
                "InsufficientStockException");
        assertThat(countSql).contains("`srcServiceId` = '5457a0119281bb98'");
    }

    @Test
    void errorSpanListSqlUsesServiceIdOnlyForVirtualServices() {
        String sql = MetricQueryBuilder.errorSpanListSql(
                "databuff",
                List.of("c72cc83a8831e407"),
                true,
                0L,
                3_600_000L,
                50,
                0,
                "2026-06-18 09:57:00",
                "2026-06-18 10:57:00",
                "start",
                "desc",
                null,
                null);
        assertThat(sql).contains("`serviceId` = 'c72cc83a8831e407'");
        assertThat(sql).doesNotContain("srcServiceId");
    }

    @Test
    void buildsServiceInstanceDistinctSql() {
        String sql = MetricQueryBuilder.serviceInstanceDistinctSql("databuff", "demo-order", 0L, 3_600_000L, 50);
        assertThat(sql).contains("group_value");
        assertThat(sql).contains("metric_service_instance");
        assertThat(sql).contains("`service_id` = '464a0a08964a061e'");
        assertThat(sql).doesNotContain("`service` = 'demo-order'");
    }

    @Test
    void buildsServiceInstanceSummarySql() {
        String sql = MetricQueryBuilder.serviceInstanceSummarySql(
                "databuff", "9bf61532d56eb7b5", 1_780_652_100_000L, 1_780_655_700_000L, "inst-a", 50);
        assertThat(sql).contains("metric_service_instance");
        assertThat(sql).contains("metric_service");
        assertThat(sql).contains("9bf61532d56eb7b5");
        assertThat(sql).contains("inst-a");
        assertThat(sql).contains("inst.`service_instance` = 'inst-a'");
        assertThat(sql).contains("call_cnt");
        assertThat(sql).contains("k8sPodName");
    }

    @Test
    void buildsK8sNamespaceDistinctSql() {
        String sql = MetricQueryBuilder.k8sNamespaceDistinctSql("databuff", 0L, 3_600_000L, 50);
        assertThat(sql).contains("metric_service_instance");
        assertThat(sql).contains("k8sNamespace");
        assertThat(sql).doesNotContain("trace_dc_span");
        assertThat(sql).doesNotContain("get_json_string");
    }

    @Test
    void buildsServiceK8sNamespaceMapSql() {
        String sql = MetricQueryBuilder.serviceK8sNamespaceMapSql("databuff", 0L, 3_600_000L, 50);
        assertThat(sql).contains("map_key");
        assertThat(sql).contains("map_value");
        assertThat(sql).contains("metric_service_instance");
        assertThat(sql).contains("k8sNamespace");
        assertThat(sql).doesNotContain("trace_dc_span");
    }

    @Test
    void buildsServiceInstanceCountMapSql() {
        String sql = MetricQueryBuilder.serviceInstanceCountMapSql("databuff", 0L, 3_600_000L, 50);
        assertThat(sql).contains("COUNT(DISTINCT");
        assertThat(sql).contains("service_instance");
        assertThat(sql).contains("GROUP BY map_key");
        assertThat(sql).contains("metric_service_instance");
        assertThat(sql).doesNotContain("trace_dc_span");
    }

    @Test
    void buildsTraceDetailSql() {
        String sql = MetricQueryBuilder.traceDetailSql("databuff", "abc123");
        assertThat(sql).contains("trace_id` = 'abc123'");
        assertThat(sql).contains("parent_id");
        assertThat(sql).contains("`start`");
    }

    @Test
    void buildsServiceSeriesSql() {
        String sql = MetricQueryBuilder.serviceSeriesSql("databuff", "demo-order", 0L, 3_600_000L);
        assertThat(sql).contains("databuff.`metric_service`");
        assertThat(sql).contains("`service_id` = '" + PortalServiceIdResolver.normalize("demo-order") + "'");
        assertThat(sql).contains("request_cnt");
        assertThat(sql).contains("/ 1000000 AS avg_duration");
    }

    @Test
    void buildsServiceTrendBucketsSql() {
        String sql = MetricQueryBuilder.serviceTrendBucketsSql("databuff", 0L, 3_600_000L, 60, List.of("demo-order"));
        assertThat(sql).contains("bucket_epoch_sec");
        assertThat(sql).contains("`service_id` = '" + PortalServiceIdResolver.normalize("demo-order") + "'");
        assertThat(sql).doesNotContain(" AND `service` IN ");
        assertThat(sql).contains("sum_duration_ns");
    }

  @Test
  void buildsHttpTrendBucketsSql() {
    String sql = MetricQueryBuilder.httpTrendBucketsSql(
        "databuff", 0L, 3_600_000L, 60, "demo-order", "inst-1", "/orders");
    assertThat(sql).contains("service_http");
    assertThat(sql).contains("`service_id` = '" + PortalServiceIdResolver.normalize("demo-order") + "'");
    assertThat(sql).contains("service_instance");
    assertThat(sql).contains("/orders");
  }

  @Test
  void httpTrendBucketsSqlUnionsLegacyInboundWhenIsIn() {
    String sql = MetricQueryBuilder.httpTrendBucketsSql(
            "databuff",
            0L,
            3_600_000L,
            60,
            java.util.List.of("demo-order"),
            null,
            null,
            1,
            null,
            null);
    assertThat(sql).contains("UNION ALL");
    assertThat(sql).contains("`isIn` = '1'");
    assertThat(sql).contains("`isOut` = '0' AND `isIn` = '0'");
    assertThat(sql).doesNotContain("`isIn` = '1' OR");
  }

  @Test
  void httpTrendBucketsSqlUsesStrictIsInWhenIsOutPresent() {
    String sql = MetricQueryBuilder.httpTrendBucketsSql(
            "databuff",
            0L,
            3_600_000L,
            60,
            java.util.List.of("demo-order"),
            null,
            null,
            1,
            1,
            null);
    assertThat(sql).doesNotContain("UNION ALL");
    assertThat(sql).contains("`isIn` = '1'");
    assertThat(sql).contains("`isOut` = '1'");
  }

  @Test
  void buildsDbEndpointSummarySqlWithExplicitServiceIds() {
    String sql = MetricQueryBuilder.dbEndpointSummarySql(
            "databuff",
            java.util.List.of("dad537de7e10e098"),
            0L,
            3_600_000L,
            50,
            null,
            null,
            null,
            null,
            1,
            java.util.List.of("9bf61532d56eb7b5"));
    assertThat(sql).contains("metric_service_db");
    assertThat(sql).contains("`service_id` = 'dad537de7e10e098'");
    assertThat(sql).contains("`srcServiceId` = '9bf61532d56eb7b5'");
    assertThat(sql).doesNotContain("NOT LIKE '%elastic%'");
    assertThat(sql).doesNotContain("`service` = 'mysql'");
  }

  @Test
  void buildsComponentEndpointSummarySqlForRpc() {
    String sql = MetricQueryBuilder.componentEndpointSummarySql(
            "databuff",
            DorisTableNames.METRIC_SERVICE_RPC,
            java.util.List.of("9bf61532d56eb7b5"),
            0L,
            3_600_000L,
            50,
            "/rpc",
            1,
            null,
            java.util.List.of("dad537de7e10e098"));
    assertThat(sql).contains("metric_service_rpc");
    assertThat(sql).contains("`service_id` = '9bf61532d56eb7b5'");
    assertThat(sql).contains("`srcServiceId` = 'dad537de7e10e098'");
    assertThat(sql).contains("`statusCode`");
    assertThat(sql).contains("`isIn` = '1'");
    assertThat(sql).doesNotMatch("(?s),\\s*FROM");
  }

  @Test
  void buildsComponentTrendBucketsSqlForServiceDb() {
    String sql = MetricQueryBuilder.componentTrendBucketsSql(
        "databuff", "metric_service_db", 0L, 3_600_000L, 60,
        "9bf61532d56eb7b5", "inst-a", "SELECT", 0, 1, null);
    assertThat(sql).contains("metric_service_db");
    assertThat(sql).contains("`service_id` = '9bf61532d56eb7b5'");
    assertThat(sql).contains("`isOut` = '1'");
    assertThat(sql).contains("max_duration_ns");
    assertThat(sql).contains("SUM(`readRows`) AS sum_read_rows");
  }

  @Test
  void buildsTraceErrorTrendBucketsSql() {
    String sql = MetricQueryBuilder.traceErrorTrendBucketsSql(
            "databuff",
            0L,
            3_600_000L,
            60,
            java.util.List.of("demo-order"),
            "host-1",
            "GET /orders");
    assertThat(sql).contains("metric_service_trace");
    assertThat(sql).contains("`errorType` = 'error'");
    assertThat(sql).contains("SUM(`error`) AS error_cnt");
    assertThat(sql).contains("HAVING SUM(`error`) > 0");
  }

  @Test
  void buildsComponentResourceTrendBucketsSqlForHttpEndpoint() {
    String sql = MetricQueryBuilder.componentResourceTrendBucketsSql(
            "databuff",
            DorisTableNames.METRIC_SERVICE_HTTP,
            0L,
            3_600_000L,
            60,
            "9bf61532d56eb7b5",
            null,
            "/demo/checkout",
            null,
            1,
            null);
    assertThat(sql).contains("metric_service_http");
    assertThat(sql).contains("SUM(`cnt`) AS request_cnt");
    assertThat(sql).contains("SUM(`error`) AS error_cnt");
    assertThat(sql).contains("SUM(`slow`) AS slow_cnt");
    assertThat(sql).contains("`service_id` = '9bf61532d56eb7b5'");
    assertThat(sql).contains("`url` = '/demo/checkout'");
    assertThat(sql).doesNotContain("metric_service_trace");
    assertThat(sql).doesNotContain("DISTINCT");
    assertThat(sql).doesNotContain("`isSlow`");
    assertThat(sql).doesNotContain("UNION ALL");
  }

  @Test
  void buildsComponentTrendBucketsSqlForRpcWithoutDbRowCounters() {
    String sql = MetricQueryBuilder.componentTrendBucketsSql(
            "databuff",
            DorisTableNames.METRIC_SERVICE_RPC,
            0L,
            3_600_000L,
            60,
            java.util.List.of("service-a"),
            null,
            null,
            1,
            null,
            null,
            java.util.List.of("service-b"));
    assertThat(sql).contains("metric_service_rpc");
    assertThat(sql).contains("0 AS sum_read_rows");
    assertThat(sql).contains("0 AS sum_update_rows");
    assertThat(sql).doesNotContain("SUM(`readRows`)");
  }

  @Test
  void rpcResourceFilterDoesNotReferenceSqlContent() {
    String sql = MetricQueryBuilder.componentCallStatsSummarySql(
            "databuff",
            DorisTableNames.METRIC_SERVICE_RPC,
            java.util.List.of("service-b"),
            java.util.List.of("service-a"),
            0L,
            3_600_000L,
            "Dubbo DemoOrderService.findInventory",
            null,
            1);
    assertThat(sql).contains("`resource` LIKE '%Dubbo DemoOrderService.findInventory%'");
    assertThat(sql).doesNotContain("sqlContent");
  }

  @Test
  void dbResourceFilterMatchesSqlContentColumn() {
    String sql = MetricQueryBuilder.dbEndpointSummarySql(
            "databuff",
            java.util.List.of("service-a"),
            0L,
            3_600_000L,
            50,
            "SELECT *",
            null,
            null,
            null,
            1,
            null);
    assertThat(sql).contains("sqlContent");
    assertThat(sql).contains("SELECT *");
  }

  @Test
  void buildsDbSlowSqlTopSummarySql() {
    String sql = MetricQueryBuilder.dbSlowSqlTopSummarySql(
            "databuff",
            java.util.List.of("dad537de7e10e098", "mysql"),
            0L,
            3_600_000L,
            50,
            null,
            null,
            1,
            null,
            1,
            null);
    assertThat(sql).contains("metric_service_db");
    assertThat(sql).contains("`isSlow` = '1'");
    assertThat(sql).contains("`isIn` = '1'");
    assertThat(sql).contains("max_duration_ns");
    assertThat(sql).contains("src_service_cnt");
  }

  @Test
  void buildsServiceSummarySql() {
        String sql = MetricQueryBuilder.serviceSummarySql("databuff", 0L, 3_600_000L, "callCnt", "desc", 0, 20);
        assertThat(sql).contains("request_cnt");
        assertThat(sql).contains("max_duration_ns");
        assertThat(sql).contains("LIMIT 20 OFFSET 0");
    }

    @Test
    void buildsServiceSummarySqlSortingByAvgLatencyUsesAverageNotTotal() {
        String sql = MetricQueryBuilder.serviceSummarySql(
                "databuff", 0L, 3_600_000L, "avgLatency", "desc", 0, 20);
        assertThat(sql).contains("ORDER BY sum_duration_ns / NULLIF(request_cnt, 0) DESC");
    }

    @Test
    void buildsServiceSummarySqlSortingByErrRateUsesRatio() {
        String sql = MetricQueryBuilder.serviceSummarySql(
                "databuff", 0L, 3_600_000L, "errRate", "asc", 0, 20);
        assertThat(sql).contains("ORDER BY error_cnt * 1.0 / NULLIF(request_cnt, 0) ASC");
    }

    @Test
    void buildsServiceSummarySqlWithWebFilter() {
        String sql = MetricQueryBuilder.serviceSummarySql(
                "databuff", 0L, 3_600_000L, "callCnt", "desc", 0, 20,
                null, null, null, "web");
        assertThat(sql).contains("NOT LIKE '[%%'");
        assertThat(sql).contains("LIMIT 20 OFFSET 0");
    }

    @Test
    void buildsServiceSummaryCountSqlWithFilters() {
        String sql = MetricQueryBuilder.serviceSummaryCountSql(
                "databuff", 0L, 3_600_000L, "demo", List.of("service-a"), 1, "custom");
        assertThat(sql).contains("COUNT(*) AS total_cnt");
        assertThat(sql).contains("LOWER(`service`) LIKE '%demo%'");
        assertThat(sql).contains("`service_id` IN ('" + PortalServiceIdResolver.normalize("service-a") + "')");
        assertThat(sql).doesNotContain(" AND `service` IN (");
        assertThat(sql).contains("HAVING SUM(`cnt`) = 0");
        assertThat(sql).contains("gateway");
    }

    @Test
    void buildsDistinctServicesSql() {
        String sql = MetricQueryBuilder.distinctServicesSql("databuff", 0L, 3_600_000L);
        assertThat(sql).contains("DISTINCT `service` AS tag_value");
    }

    @Test
    void buildsDbServiceSummarySql() {
        String sql = MetricQueryBuilder.dbServiceSummarySql("databuff", 0L, 3_600_000L);
        assertThat(sql).contains("metric_service_db");
        assertThat(sql).contains("slow_cnt");
        assertThat(sql).contains("`isIn` = '1'");
    }

    @Test
    void buildsDbDistinctServicesSql() {
        String sql = MetricQueryBuilder.dbDistinctServicesSql("databuff", 0L, 3_600_000L);
        assertThat(sql).contains("metric_service_db");
        assertThat(sql).contains("`isIn` = '1'");
    }

    @Test
    void buildsMqProducerAndConsumerSummarySql() {
        String producer = MetricQueryBuilder.mqProducerServiceSummarySql("databuff", 0L, 3_600_000L);
        assertThat(producer).contains("metric_service_mq");
        assertThat(producer).contains("GROUP BY `service`");

        String consumer = MetricQueryBuilder.mqConsumerServiceSummarySql("databuff", 0L, 3_600_000L);
        assertThat(consumer).contains("metric_service_mq");
        assertThat(consumer).contains("GROUP BY `srcService`");
    }

    @Test
    void buildsComponentServiceSummarySqlForRedis() {
        String sql = MetricQueryBuilder.componentServiceSummarySql(
                "databuff", "metric_service_redis", 0L, 3_600_000L,
                "service", "service_id", "command");
        assertThat(sql).contains("metric_service_redis");
        assertThat(sql).contains("SUM(`slow`)");
    }

    @Test
    void buildsMetaServicesSql() {
        String sql = MetricQueryBuilder.metaServicesSql("databuff", null);
        assertThat(sql).contains("meta_service");
        assertThat(sql).contains("`id`");
        assertThat(sql).contains("`name`");
        assertThat(sql).contains("`service_type`");

        String filtered = MetricQueryBuilder.metaServicesSql("databuff", "order");
        assertThat(filtered).contains("`name` LIKE '%order%'");
    }

    @Test
    void buildsMetaServiceByIdSql() {
        String sql = MetricQueryBuilder.metaServiceByIdSql("databuff", "demo-order");
        assertThat(sql).contains("meta_service");
        assertThat(sql).contains("`id` = 'demo-order'");
        assertThat(sql).contains("LIMIT 1");
    }

    @Test
    void buildsServiceSummaryByServiceSql() {
        String sql = MetricQueryBuilder.serviceSummaryByServiceSql("databuff", "demo-order", 0L, 3_600_000L);
        assertThat(sql).contains("metric_service");
        assertThat(sql).contains("`service_id` = '" + PortalServiceIdResolver.normalize("demo-order") + "'");
        assertThat(sql).contains("LIMIT 1");
    }

    @Test
    void buildsComponentInboundSummaryByServiceSql() {
        String serviceId = PortalServiceIdResolver.normalize("dad537de7e10e098");
        String sql = MetricQueryBuilder.componentInboundSummaryByServiceSql(
                "databuff", "metric_service_db", "dad537de7e10e098", 0L, 3_600_000L, "dbType");
        assertThat(sql).contains("metric_service_db");
        assertThat(sql).contains("`service_id` = '" + serviceId + "'");
        assertThat(sql).doesNotContain(" OR `service` = ");
        assertThat(sql).contains("`isIn` = '1'");
    }

    @Test
    void buildsServiceMetricHasDataSqlForJvm() {
        String serviceId = PortalServiceIdResolver.normalize("service-a");
        String sql = MetricQueryBuilder.serviceMetricHasDataSql(
                "databuff", "metric_jvm", "service-a", 0L, 3_600_000L);
        assertThat(sql).contains("metric_jvm");
        assertThat(sql).contains("COUNT(*)");
        assertThat(sql).contains("`service_id` = '" + serviceId + "'");
        assertThat(sql).doesNotContain("SUM(`cnt`)");
    }

    @Test
    void buildsServiceMetricHasDataSql() {
        String sql = MetricQueryBuilder.serviceMetricHasDataSql(
                "databuff", "metric_service_http", "demo-order", 0L, 3_600_000L);
        assertThat(sql).contains("metric_service_http");
        assertThat(sql).contains("`service_id` = '" + PortalServiceIdResolver.normalize("demo-order") + "'");
        assertThat(sql).contains("`srcServiceId` = '" + PortalServiceIdResolver.normalize("demo-order") + "'");
        assertThat(sql).doesNotContain("`service` = ");
        assertThat(sql).doesNotContain("`srcService` = ");
        assertThat(sql).contains("total_cnt");
    }

    @Test
    void buildsServiceMetricHasDataSqlForDbBySrcService() {
        String serviceId = PortalServiceIdResolver.normalize("service-a");
        String sql = MetricQueryBuilder.serviceMetricHasDataSql(
                "databuff", "metric_service_db", "service-a", 0L, 3_600_000L);
        assertThat(sql).contains("metric_service_db");
        assertThat(sql).contains("`srcServiceId` = '" + serviceId + "'");
        assertThat(sql).contains("`service_id` = '" + serviceId + "'");
        assertThat(sql).doesNotContain("`srcService` = ");
        assertThat(sql).doesNotContain("`service` = ");
        assertThat(sql).contains("`isIn` = '1'");
    }

    @Test
    void buildsServiceErrorRateSql() {
        String sql = MetricQueryBuilder.serviceErrorRateSql("databuff", "demo-order", 0L, 3_600_000L);
        assertThat(sql).contains("SUM(`error`)");
        assertThat(sql).contains("`service_id` = '" + PortalServiceIdResolver.normalize("demo-order") + "'");
    }

    @Test
    void buildsComponentWebDownstreamSummarySql() {
        String sql = MetricQueryBuilder.componentWebDownstreamSummarySql(
                "databuff", "metric_service_http", java.util.Set.of("service-a"), 0L, 3_600_000L, 50);
        assertThat(sql).contains("metric_service_http");
        assertThat(sql).contains("`isIn` = '1'");
        assertThat(sql).contains("GROUP BY `service`");
        assertThat(sql).contains("NOT LIKE '[%'");
    }

    @Test
    void buildsComponentWebDownstreamOutboundSummarySql() {
        String sql = MetricQueryBuilder.componentWebDownstreamOutboundSummarySql(
                "databuff", "metric_service_http", java.util.Set.of("service-a"), 0L, 3_600_000L, 50);
        assertThat(sql).contains("metric_service_http");
        assertThat(sql).contains("`isOut` = '1'");
        assertThat(sql).contains("GROUP BY `service`");
        assertThat(sql).contains("NOT LIKE '[%'");
    }

    @Test
    void buildsComponentWebUpstreamOutboundSummarySql() {
        String sql = MetricQueryBuilder.componentWebUpstreamOutboundSummarySql(
                "databuff", "metric_service_http", java.util.Set.of("service-b"), 0L, 3_600_000L, 50);
        assertThat(sql).contains("metric_service_http");
        assertThat(sql).contains("`isOut` = '1'");
        assertThat(sql).contains("GROUP BY `srcService`");
    }

    @Test
    void buildsDistinctSrcServicesSql() {
        String sql = MetricQueryBuilder.distinctSrcServicesSql(
                "databuff", "metric_service_http", 0L, 3_600_000L, " AND `isIn` = '1' ", 200);
        assertThat(sql).contains("metric_service_http");
        assertThat(sql).contains("GROUP BY `srcService`, `srcServiceId`");
        assertThat(sql).contains("`isIn` = '1'");
        assertThat(sql).contains("NOT LIKE '[%'");
    }

    @Test
    void buildsMetricServiceNameIdMapSql() {
        String sql = MetricQueryBuilder.metricServiceNameIdMapSql(
                "databuff", "metric_service_trace", 0L, 3_600_000L, " AND `service_id` = 'x' ", 100);
        assertThat(sql).contains("metric_service_trace");
        assertThat(sql).contains("map_key");
        assertThat(sql).contains("map_value");
        assertThat(sql).contains("`service_id` = 'x'");
        assertThat(sql).contains("LIMIT 100");
    }

    @Test
    void buildsMetricTagCountMapSql() {
        String sql = MetricQueryBuilder.metricTagCountMapSql(
                "databuff", "metric_service_trace", "resource", 0L, 3_600_000L, "", 50);
        assertThat(sql).contains("`resource` AS map_key");
        assertThat(sql).contains("SUM(`cnt`)");
        assertThat(sql).contains("LIMIT 50");
    }

    @Test
    void buildsMetricDurationRangeSql() {
        String sql = MetricQueryBuilder.metricDurationRangeSql(
                "databuff", "metric_service_trace", 0L, 3_600_000L, "");
        assertThat(sql).contains("MIN(`minDuration`)");
        assertThat(sql).contains("MAX(`maxDuration`)");
    }

    @Test
    void buildsComponentOutboundDownstreamSummarySql() {
        String sql = MetricQueryBuilder.componentOutboundDownstreamSummarySql(
                "databuff", "metric_service_redis", java.util.Set.of("service-a"), null, 1, false, 0L, 3_600_000L, 50);
        assertThat(sql).contains("metric_service_redis");
        assertThat(sql).contains("`isOut` = '1'");
    }

    @Test
    void buildsRpcDownstreamSummarySql() {
        String sql = MetricQueryBuilder.rpcDownstreamSummarySql(
                "databuff", java.util.Set.of("9bf61532d56eb7b5"), 0L, 3_600_000L, 50);
        assertThat(sql).contains("metric_service_rpc");
        assertThat(sql).contains("`isIn` = '1'");
        assertThat(sql).contains("`srcServiceId` = '9bf61532d56eb7b5'");
        assertThat(sql).doesNotContain("`srcService` = 'service-a'");
        assertThat(sql).contains("GROUP BY `service`");
        assertThat(sql).contains("NOT LIKE '[%'");
    }

    @Test
    void buildsRpcUpstreamSummarySql() {
        String sql = MetricQueryBuilder.rpcUpstreamSummarySql(
                "databuff", java.util.Set.of("service-b"), 0L, 3_600_000L, 50);
        assertThat(sql).contains("metric_service_rpc");
        assertThat(sql).contains("`isIn` = '1'");
        assertThat(sql).contains("GROUP BY `srcService`");
    }

    @Test
    void buildsOutboundComponentUpstreamSummarySql() {
        String sql = MetricQueryBuilder.componentOutboundUpstreamSummarySql(
                "databuff",
                DorisTableNames.METRIC_SERVICE_DB,
                java.util.Set.of("[mysql]demo_apm"),
                1,
                null,
                0L,
                3_600_000L,
                50,
                "");
        assertThat(sql).contains("metric_service_db");
        assertThat(sql).contains("`isIn` = '1'");
        assertThat(sql).contains("c72cc83a8831e407");
        assertThat(sql).contains("GROUP BY `srcService`");
        assertThat(sql).contains("NOT LIKE '[%'");
    }

    @Test
    void buildsTopologyEdgesSql() {
        String sql = MetricQueryBuilder.topologyEdgesSql("databuff", 0L, 3_600_000L, 50);
        assertThat(sql).contains("srcService");
        assertThat(sql).contains("metric_service_http");
        assertThat(sql).doesNotContain("trace_dc_span");
        assertThat(sql).contains("LIMIT 50");
    }

    @Test
    void buildsGlobalTopologyPeerEdgesSqlForOutbound() {
        String sql = MetricQueryBuilder.globalTopologyPeerEdgesSql(
                "databuff", "metric_service_http", 0L, 3_600_000L, 100, null, 1);
        assertThat(sql).contains("databuff.`metric_service_http`");
        assertThat(sql).contains("`isOut` = '1'");
        assertThat(sql).contains("`srcService`");
        assertThat(sql).doesNotContain("`isIn` = '1'");
    }

    @Test
    void buildsGlobalTopologyPeerEdgesSqlForInbound() {
        String sql = MetricQueryBuilder.globalTopologyPeerEdgesSql(
                "databuff", "metric_service_rpc", 0L, 3_600_000L, 100, 1, null);
        assertThat(sql).contains("databuff.`metric_service_rpc`");
        assertThat(sql).contains("`isIn` = '1'");
        assertThat(sql).doesNotContain("`isOut` = '1'");
        assertThat(sql).contains("`service` NOT LIKE '[%'");
    }

    @Test
    void buildsGlobalTopologyVirtualEdgesSqlForDbOutbound() {
        String sql = MetricQueryBuilder.globalTopologyVirtualEdgesSql(
                "databuff", "metric_service_db", 0L, 3_600_000L, 100, null, 1, false);
        assertThat(sql).contains("databuff.`metric_service_db`");
        assertThat(sql).contains("`isOut` = '1'");
        assertThat(sql).doesNotContain("`service` NOT LIKE '[%'");
    }

    @Test
    void buildsGlobalTopologyVirtualEdgesSqlForRemoteOutbound() {
        String sql = MetricQueryBuilder.globalTopologyVirtualEdgesSql(
                "databuff", "metric_service_remote", 0L, 3_600_000L, 100, null, 1, true);
        assertThat(sql).contains("databuff.`metric_service_remote`");
        assertThat(sql).contains("`service` LIKE '[%'");
    }

    @Test
    void buildsServiceFlowSql() {
        String serviceId = PortalServiceIdResolver.normalize("checkout");
        String sql = MetricQueryBuilder.serviceFlowSql("databuff", "checkout", 0L, 3_600_000L, 100);
        assertThat(sql).contains("databuff.`metric_service_flow`");
        assertThat(sql).contains("parentService");
        assertThat(sql).contains("src_service_id");
        assertThat(sql).contains("dst_service_id");
        assertThat(sql).contains("`service_id` = '" + serviceId + "'");
        assertThat(sql).contains("`parentServiceId` = '" + serviceId + "'");
        assertThat(sql).doesNotContain("`service` = ");
        assertThat(sql).doesNotContain("`parentService` = ");
    }

    @Test
    void buildsServiceFlowEntryPointsSqlGroupsByEntryPathIdOnly() {
        String sql = MetricQueryBuilder.serviceFlowEntryPointsSql(
                "databuff", 0L, 3_600_000L, java.util.List.of("123"));
        assertThat(sql).contains("GROUP BY `entryPathId`");
        assertThat(sql).doesNotContain("GROUP BY `entryPathId`, `service`, `service_id`");
        assertThat(sql).contains("MAX(NULLIF(`service`, '')) AS service");
        assertThat(sql).contains("HAVING MAX(NULLIF(`service`, '')) IS NOT NULL");
    }

    @Test
    void buildsServiceRequestCountSql() {
        String sql = MetricQueryBuilder.serviceRequestCountSql("databuff", "demo-order", 0L, 3_600_000L);
        assertThat(sql).contains("SUM(`cnt`)");
        assertThat(sql).contains("`service_id` = '" + PortalServiceIdResolver.normalize("demo-order") + "'");
    }

    @Test
    void buildsTopologyMiddlewareSql() {
        String sql = MetricQueryBuilder.topologyMiddlewareEdgesSql("databuff", 0L, 3_600_000L, 20);
        assertThat(sql).contains("metric_service_db");
        assertThat(sql).contains("srcService");
        assertThat(sql).contains("dstService");
        assertThat(sql).doesNotContain("trace_dc_span");
        assertThat(sql).doesNotContain("meta.peer.hostname");
    }

    @Test
    void buildsHttpEndpointSummarySql() {
        String sql = MetricQueryBuilder.httpEndpointSummarySql("databuff", "demo-order", 0L, 3_600_000L, 50);
        assertThat(sql).contains("databuff.`metric_service_http`");
        assertThat(sql).contains("httpMethod");
        assertThat(sql).contains("`service_id` = '" + PortalServiceIdResolver.normalize("demo-order") + "'");
        assertThat(sql).contains("service_id`, `service`, `url`");
    }

  @Test
  void buildsHttpEndpointSummarySqlWithExactUrlMatch() {
    String sql = MetricQueryBuilder.httpEndpointSummarySql(
            "databuff", java.util.List.of("demo-order"), 0L, 3_600_000L, 50,
            null, null, "/orders", 1, null, null, true);
    assertThat(sql).contains("`url` = '/orders'");
    assertThat(sql).doesNotContain("`url` LIKE");
  }

  @Test
  void buildsHttpTrendBucketsSqlWithExactUrlMatch() {
    String sql = MetricQueryBuilder.httpTrendBucketsSql(
            "databuff", 0L, 3_600_000L, 60, java.util.List.of("demo-order"),
            null, "/orders", 1, null, null, true);
    assertThat(sql).contains("`url` = '/orders'");
    assertThat(sql).doesNotContain("`url` LIKE");
  }

  @Test
  void buildsHttpEndpointSummarySqlWithFilters() {
        String sql = MetricQueryBuilder.httpEndpointSummarySql(
                "databuff", "demo-order", 0L, 3_600_000L, 50, "GET", "500", "/orders");
        assertThat(sql).contains("`httpMethod` = 'GET'");
        assertThat(sql).contains("`httpCode` = '500'");
        assertThat(sql).contains("`url` LIKE '%/orders%'");
    }

    @Test
    void buildsComponentResourceRelationSql() {
        String sql = MetricQueryBuilder.componentResourceRelationSql(
                "databuff",
                "metric_service_http",
                0L,
                3_600_000L,
                java.util.List.of("service-a"),
                null,
                "/demo/checkout",
                null,
                1,
                null,
                java.util.List.of("service_id", "service", "url"),
                50);
        assertThat(sql).contains("metric_service_http");
        assertThat(sql).contains("`isIn` = '1'");
        assertThat(sql).contains("`url` = '/demo/checkout'");
        assertThat(sql).contains("all_cnt");
        assertThat(sql).contains("req_rate");
    }

    @Test
    void buildsComponentResourceRelationSqlForDbWithoutUrlColumn() {
        String sql = MetricQueryBuilder.componentResourceRelationSql(
                "databuff",
                "metric_service_db",
                0L,
                3_600_000L,
                null,
                java.util.List.of("service-a"),
                null,
                "/demo/checkout",
                null,
                1,
                java.util.List.of("service_id", "service", "sqlContent"),
                50);
        assertThat(sql).contains("metric_service_db");
        assertThat(sql).contains("`rootResource` = '/demo/checkout'");
        assertThat(sql).contains("`srcServiceId` = '" + PortalServiceIdResolver.normalize("service-a") + "'");
        assertThat(sql).doesNotContain("`url`");
        assertThat(sql).doesNotContain("`srcService` = 'service-a'");
    }

    @Test
    void buildsHttpEndpointSummarySqlWithDirectionFilters() {
        String sql = MetricQueryBuilder.httpEndpointSummarySql(
                "databuff", "9bf61532d56eb7b5", 0L, 3_600_000L, 50,
                null, null, "/orders", 1, null, "dad537de7e10e098");
        assertThat(sql).contains("`isIn` = '1'");
        assertThat(sql).contains("`service_id` = '9bf61532d56eb7b5'");
        assertThat(sql).contains("`srcServiceId` = 'dad537de7e10e098'");
        assertThat(sql).doesNotContain("`srcService` =");
    }

    @Test
    void buildsHttpLatencyDistributionSql() {
        String sql = MetricQueryBuilder.httpLatencyDistributionSql("databuff", "demo-order", 0L, 3_600_000L);
        assertThat(sql).contains("durationRange");
        assertThat(sql).contains("service_http");
    }

    @Test
    void buildsHttpLatencyDistributionSqlWithFilters() {
        String sql = MetricQueryBuilder.httpLatencyDistributionSql(
                "databuff", "demo-order", 0L, 3_600_000L, "POST", "200", "/api");
        assertThat(sql).contains("`httpMethod` = 'POST'");
        assertThat(sql).contains("`httpCode` = '200'");
    }

    @Test
    void buildsExceptionDistFromSpanSql() {
        String sql = MetricQueryBuilder.exceptionDistFromSpanSql(
                "databuff", "exceptionName", 0L, 3_600_000L, "demo-order", null, null, null);
        assertThat(sql).contains("trace_dc_span");
        assertThat(sql).contains("exception_name");
        assertThat(sql).contains("meta.error.type");
    }

    @Test
    void buildsExceptionListSqlFromMetricServiceException() {
        String sql = MetricQueryBuilder.exceptionListSql(
                "databuff", 0L, 3_600_000L, "demo-order", "inst-1",
                "/api/orders", "InsufficientStockException", null, "start", "desc", 0, 20);
        assertThat(sql).contains("metric_service_exception");
        assertThat(sql).contains("`service_id` = '" + PortalServiceIdResolver.normalize("demo-order") + "'");
        assertThat(sql).contains("exceptionName");
        assertThat(sql).contains("ORDER BY `ts` DESC");
    }

    @Test
    void buildsExceptionDistFromSpanSqlByServiceEntryResource() {
        String sql = MetricQueryBuilder.exceptionDistFromSpanSql(
                "databuff", "resource", 0L, 3_600_000L, "demo-order", null, "/api/orders", null);
        assertThat(sql).contains("entry.resource");
        assertThat(sql).doesNotContain("GROUP BY COALESCE(NULLIF(`resource`, ''), `name`)");
    }

    @Test
    void buildsServiceErrorDistFromMetricExceptionSql() {
        String sql = MetricQueryBuilder.serviceErrorDistSql("databuff", 0L, 3_600_000L, null);
        assertThat(sql).contains("metric_service_exception");
        assertThat(sql).contains("SUM(`cnt`) AS err_cnt");
        assertThat(sql).contains("COALESCE(NULLIF(`service_id`, ''), `service`) AS service_id");
        assertThat(sql).contains("GROUP BY COALESCE(NULLIF(`service_id`, ''), `service`)");
        assertThat(sql).contains("FROM databuff.`metric_service_exception`");
        assertThat(sql).doesNotContain("FROM databuff.`metric_service`");
        assertThat(sql).doesNotContain("GROUP BY service_id\n");
    }

    @Test
    void buildsExceptionDistFromMetricServiceInstanceSql() {
        String sql = MetricQueryBuilder.exceptionDistFromMetricServiceInstanceSql(
                "databuff", 0L, 3_600_000L, "demo-order", "inst-1");
        assertThat(sql).contains("metric_service_exception");
        assertThat(sql).contains("service_instance");
        assertThat(sql).contains(
                "GROUP BY COALESCE(NULLIF(`service_id`, ''), `service`), `service_instance`");
        assertThat(sql).contains("inst-1");
    }

    @Test
    void buildsExceptionDistFromMetricRootResourceSql() {
        String sql = MetricQueryBuilder.exceptionDistFromMetricRootResourceSql(
                "databuff", 0L, 3_600_000L, "demo-order", null, "/api/orders");
        assertThat(sql).contains("metric_service_exception");
        assertThat(sql).contains("rootResource");
        assertThat(sql).contains("/api/orders");
        assertThat(sql).contains("rootResource` IS NOT NULL");
    }

    @Test
    void buildsMetricTagDistinctSql() {
        String sql = MetricQueryBuilder.metricTagDistinctSql(
                "databuff", "service_object_pool", "objectPoolName", 0L, 3_600_000L,
                " AND `service_id` = 'demo' ");
        assertThat(sql).contains("service_object_pool");
        assertThat(sql).contains("objectPoolName");
        assertThat(sql).contains("service_id");
    }

    @Test
    void buildsMetricFieldSeriesSql() {
        String sql = MetricQueryBuilder.metricFieldSeriesSql(
                "databuff", "service_thread_pool", "poolSize", 0L, 3_600_000L, "");
        assertThat(sql).contains("service_thread_pool");
        assertThat(sql).contains("poolSize");
        assertThat(sql).contains("GROUP BY epoch_sec");
        assertThat(sql).contains("SUM(`poolSize`)");
    }

    @Test
    void buildsMetricFieldSeriesSqlWithMeanAggregation() {
        String sql = MetricQueryBuilder.metricFieldSeriesSql(
                "databuff", "metric_jvm", "thread_count", 0L, 3_600_000L, "", 60, "mean");
        assertThat(sql).contains("AVG(`thread_count`)");
        assertThat(sql).doesNotContain("SUM(`thread_count`)");
    }

    @Test
    void buildsJvmGcCounterSeriesSqlWithDeltaAndMsConversion() {
        String countSql = MetricQueryBuilder.metricFieldSeriesSql(
                "databuff", "metric_jvm", "gc_minor_collection_count", 0L, 3_600_000L, "", 60, "mean");
        assertThat(countSql).contains("LAG(counter_value)");
        assertThat(countSql).contains("gc_minor_collection_count");
        assertThat(countSql).doesNotContain("* 1000");

        String timeSql = MetricQueryBuilder.metricFieldSeriesSql(
                "databuff", "metric_jvm", "gc_minor_collection_time", 0L, 3_600_000L, "", 60, "mean");
        assertThat(timeSql).contains("LAG(counter_value)");
        assertThat(timeSql).contains("gc_minor_collection_time");
        assertThat(timeSql).contains("* 1000");
    }

    @Test
    void buildsJvmGcCounterTopGroupsSqlWithDelta() {
        String sql = MetricQueryBuilder.metricTopGroupsSql(
                "databuff", "metric_jvm", "gc_minor_collection_count", "serviceInstance",
                0L, 3_600_000L, "", 10, "mean");
        assertThat(sql).contains("LAG(counter_value)");
        assertThat(sql).contains("service_instance");
    }

    @Test
    void resolvesMeanAndAvgAggregationAliases() {
        assertThat(MetricQueryBuilder.resolveFieldAggregation("thread_count", "mean"))
                .isEqualTo("AVG(`%s`)");
        assertThat(MetricQueryBuilder.resolveFieldAggregation("thread_count", "avg"))
                .isEqualTo("AVG(`%s`)");
        assertThat(MetricQueryBuilder.resolveFieldAggregation("thread_count", "sum"))
                .isEqualTo("SUM(`%s`)");
    }

    @Test
    void buildsMetricFieldSeriesByGroupSqlWithMinuteBuckets() {
        String sql = MetricQueryBuilder.metricFieldSeriesByGroupSql(
                "databuff", "metric_service_http", "cnt", "service", "demo-order",
                1_780_906_620_000L, 1_780_910_220_000L, "", 60);
        assertThat(sql).contains("metric_service_http");
        assertThat(sql).contains("GROUP BY epoch_sec");
        assertThat(sql).contains("`service` = 'demo-order'");
        assertThat(sql).contains("`ts` >= 1780906620000");
    }

    @Test
    void buildsMetricTopGroupsSql() {
        String sql = MetricQueryBuilder.metricTopGroupsSql(
                "databuff", "jvm", "thread_count", "serviceInstance", 0L, 3_600_000L,
                " AND `service_id` = 'demo' ", 5);
        assertThat(sql).contains("service_instance");
        assertThat(sql).contains("LIMIT 5");
    }

    @Test
    void buildsDerivedAvgDurationFieldSeriesSql() {
        String sql = MetricQueryBuilder.metricFieldSeriesSql(
                "databuff", "metric_service", "avgDuration", 0L, 3_600_000L, "", 60, "sum");
        assertThat(sql).contains("metric_service");
        assertThat(sql).contains("SUM(`sumDuration`) / NULLIF(SUM(`cnt`), 0) / 1000000");
        assertThat(sql).contains("GROUP BY epoch_sec");
        assertThat(sql).doesNotContain("`avgDuration`");
    }

    @Test
    void buildsDerivedErrorPctTopGroupsSql() {
        String sql = MetricQueryBuilder.metricTopGroupsSql(
                "databuff", "metric_service", "error.pct", "service", 0L, 3_600_000L, "", 10, "sum");
        assertThat(sql).contains("SUM(`error`) / NULLIF(SUM(`cnt`), 0) * 100");
        assertThat(sql).contains("GROUP BY `service`");
        assertThat(sql).doesNotContain("`error.pct`");
    }

    @Test
    void buildsCallSpanListSql() {
        String dstServiceId = PortalServiceIdResolver.normalize("demo-order");
        String sql = MetricQueryBuilder.callSpanListSql(
                "databuff", 0L, 3_600_000L,
                "demo-pay", null, null, null, "demo-order", null,
                "/orders", "GET", null, false, "service.http",
                "start", "desc", 20, 0);
        assertThat(sql).contains("trace_dc_span");
        assertThat(sql).contains("`dstServiceId` = '" + dstServiceId + "'");
        assertThat(sql).doesNotContain(" OR `dstService` = ");
        assertThat(sql).contains("LIKE '%/orders%'");
        assertThat(sql).contains("`meta.http.method`");
        assertThat(sql).contains("isOut` = 1");
        assertThat(sql).contains("LIMIT 20");
    }

    @Test
    void buildsHttpCallSpanSqlMatchingPathAndMetaUrl() {
        String sql = MetricQueryBuilder.callSpanListSql(
                "databuff", 0L, 3_600_000L,
                null, null, "9bf61532d56eb7b5", null,
                "5457a0119281bb98", null,
                "/api/orders/10001", "GET", null, false, "service.http",
                "start", "desc", 50, 0);
        assertThat(sql).contains("LIKE '%/api/orders/10001%'");
        assertThat(sql).contains("`meta.http.url`");
        assertThat(sql).contains("= 'GET'");
        assertThat(sql).contains("`meta.http.method`");
        assertThat(sql).contains("`srcServiceId` = '9bf61532d56eb7b5'");
        assertThat(sql).contains("`dstServiceId` = '5457a0119281bb98'");
    }

    @Test
    void buildsDbCallSpanSqlWithDstServiceAndStatement() {
        String sql = MetricQueryBuilder.callSpanListSql(
                "databuff", 0L, 3_600_000L,
                null, null, "9bf61532d56eb7b5", null,
                "dad537de7e10e098", null,
                "INSERT INTO demo_order_audit(order_id, channel) VALUES (?, ?)", null, null,
                false, "service.db", "start", "desc", 20, 0);
        assertThat(sql).contains("`dstServiceId` = 'dad537de7e10e098'");
        assertThat(sql).contains("`srcServiceId` = '9bf61532d56eb7b5'");
        assertThat(sql).doesNotContain("`serviceId` = 'dad537de7e10e098'");
        assertThat(sql).contains("db.statement");
        assertThat(sql).contains("isOut` = 1");
    }

    @Test
    void buildsCallSpanChildrenSql() {
        String serviceId = PortalServiceIdResolver.normalize("demo-order");
        String sql = MetricQueryBuilder.callSpanChildrenSql(
                "databuff", 0L, 3_600_000L, "demo-order", List.of("s1", "s2"));
        assertThat(sql).contains("`parent_id` IN ('s1', 's2')");
        assertThat(sql).contains("`serviceId` = '" + serviceId + "'");
        assertThat(sql).doesNotContain(" OR `service` = ");
        assertThat(sql).contains("isIn` = 1");
    }

    @Test
    void buildsCallSpanSqlFilteringRootResourceFromSpanMeta() {
        String sql = MetricQueryBuilder.callSpanListSql(
                "databuff", 0L, 3_600_000L,
                null, null, "9bf61532d56eb7b5", null,
                "806ee6fa18e6b22c", null,
                "/methodB7", "GET", "/methodA4", false, "service.http",
                "start", "desc", 50, 0);
        assertThat(sql).contains("get_json_string(`meta`, '$.\"root.resource\"')");
        assertThat(sql).contains("LIKE '%/methodA4%'");
        assertThat(sql).contains("LIKE '%/methodB7%'");
    }

    @Test
    void buildsMetricFilterClauseForAlarmOperators() {
        assertThat(MetricQueryBuilder.metricFilterClause("service", "=", "checkout"))
                .isEqualTo(" AND `service` = 'checkout' ");
        assertThat(MetricQueryBuilder.metricFilterClause("service", "!=", "checkout"))
                .isEqualTo(" AND `service` != 'checkout' ");
        assertThat(MetricQueryBuilder.metricFilterClause("service", "like", "check"))
                .isEqualTo(" AND `service` LIKE '%check%' ");
        assertThat(MetricQueryBuilder.metricFilterClause("service", "notLike", "slow"))
                .isEqualTo(" AND `service` NOT LIKE '%slow%' ");
    }
}
