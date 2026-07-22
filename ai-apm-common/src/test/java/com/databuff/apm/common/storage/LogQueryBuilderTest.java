package com.databuff.apm.common.storage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LogQueryBuilderTest {

    @Test
    void distinctSeveritiesSqlAppliesCrossFiltersButNotSeverityFilter() {
        String sql = LogQueryBuilder.distinctSeveritiesSql(
                "databuff",
                "trace-1",
                null,
                List.of("svc-1"),
                List.of("inst-1"),
                List.of("host-a"),
                "timeout",
                0L,
                3_600_000L);

        assertThat(sql).contains("SELECT DISTINCT severity");
        assertThat(sql).contains("trace_id = 'trace-1'");
        assertThat(sql).contains("service_id IN ('svc-1')");
        assertThat(sql).contains("service_instance IN ('inst-1')");
        assertThat(sql).contains("hostname IN ('host-a')");
        assertThat(sql).contains("body LIKE '%timeout%'");
        assertThat(sql).doesNotContain("severity IN");
    }

    @Test
    void trendBucketsSqlGroupsByBucketAndSeverityWithoutSeverityFilter() {
        String sql = LogQueryBuilder.trendBucketsSql(
                "databuff",
                "trace-1",
                null,
                null,
                List.of("svc-1"),
                List.of(),
                List.of("inst-1"),
                List.of("host-a"),
                List.of(),
                "timeout",
                0L,
                3_600_000L,
                60);

        assertThat(sql).contains("bucket_epoch_sec");
        assertThat(sql).contains("FLOOR(time_ns / 1000000000");
        assertThat(sql).contains("GROUP BY bucket_epoch_sec, severity");
        assertThat(sql).contains("trace_id = 'trace-1'");
        assertThat(sql).contains("service_id IN ('svc-1')");
        assertThat(sql).contains("service_instance IN ('inst-1')");
        assertThat(sql).contains("hostname IN ('host-a')");
        assertThat(sql).contains("body LIKE '%timeout%'");
        assertThat(sql).doesNotContain("severity IN");
    }

    @Test
    void trendBucketsSqlAppliesSeverityFilterWhenProvided() {
        String sql = LogQueryBuilder.trendBucketsSql(
                "databuff",
                null,
                null,
                null,
                List.of(),
                List.of("order-api"),
                List.of(),
                List.of(),
                List.of("ERROR"),
                null,
                0L,
                3_600_000L,
                60);

        assertThat(sql).contains("service IN ('order-api')");
        assertThat(sql).contains("severity IN ('ERROR')");
    }

    @Test
    void searchSqlSelectsServiceInstance() {
        String sql = LogQueryBuilder.searchSql(
                "databuff",
                null,
                null,
                null,
                List.of(),
                List.of("order-api"),
                List.of("pod-1"),
                List.of(),
                List.of("ERROR"),
                "timeout",
                0L,
                3_600_000L,
                0,
                50);

        assertThat(sql).contains("service_instance");
        assertThat(sql).contains("service_instance IN ('pod-1')");
        assertThat(sql).contains("severity IN ('ERROR')");
        assertThat(sql).doesNotContain("attributes_json");
        assertThat(sql).doesNotContain("resource_json");
    }

    @Test
    void detailSqlSelectsFullRecordByTimeNs() {
        String sql = LogQueryBuilder.detailSql("databuff", 1_720_000_000_000_000_000L, "svc-1");

        assertThat(sql).contains("attributes_json");
        assertThat(sql).contains("resource_json");
        assertThat(sql).contains("severity_number");
        assertThat(sql).contains("observed_time_ns");
        assertThat(sql).contains("time_ns = 1720000000000000000");
        assertThat(sql).contains("service_id = 'svc-1'");
        assertThat(sql).contains("LIMIT 1");
    }

    @Test
    void countAndDistinctSqlBuilders() {
        assertThat(LogQueryBuilder.countSql(
                "databuff", "trace-1", "span-1", "svc-1", List.of(), List.of("checkout"),
                List.of("inst"), List.of("host"), List.of("ERROR"), "err", 0L, 3_600_000L))
                .contains("COUNT(*)").contains("span_id = 'span-1'");
        assertThat(LogQueryBuilder.distinctServicesSql(
                "databuff", "trace-1", null, List.of("inst"), List.of("host"), List.of("ERROR"), "err", 0L, 3_600_000L))
                .contains("DISTINCT service");
        assertThat(LogQueryBuilder.distinctServiceInstancesSql(
                "databuff", "trace-1", null, List.of("svc"), List.of("host"), List.of(), null, 0L, 3_600_000L))
                .contains("service_instance");
        assertThat(LogQueryBuilder.distinctHostsSql(
                "databuff", null, "span-1", List.of("svc"), List.of("inst"), List.of(), null, 0L, 3_600_000L))
                .contains("hostname");
        assertThat(LogQueryBuilder.spanIdsWithLogsSql("databuff", "trace-abc"))
                .contains("trace_id = 'trace-abc'").contains("span_id");
    }
}
