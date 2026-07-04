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
                List.of("host-a"),
                "timeout",
                0L,
                3_600_000L);

        assertThat(sql).contains("SELECT DISTINCT severity");
        assertThat(sql).contains("trace_id = 'trace-1'");
        assertThat(sql).contains("service_id IN ('svc-1')");
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
                List.of("host-a"),
                "timeout",
                0L,
                3_600_000L,
                60);

        assertThat(sql).contains("bucket_epoch_sec");
        assertThat(sql).contains("FLOOR(time_ns / 1000000000");
        assertThat(sql).contains("GROUP BY bucket_epoch_sec, severity");
        assertThat(sql).contains("trace_id = 'trace-1'");
        assertThat(sql).contains("service_id IN ('svc-1')");
        assertThat(sql).contains("hostname IN ('host-a')");
        assertThat(sql).contains("body LIKE '%timeout%'");
        assertThat(sql).doesNotContain("severity IN");
    }
}
