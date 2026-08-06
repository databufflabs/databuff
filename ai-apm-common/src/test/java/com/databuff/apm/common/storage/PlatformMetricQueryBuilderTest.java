package com.databuff.apm.common.storage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformMetricQueryBuilderTest {

    @Test
    void seriesSqlIncludesMetricInAndGroupBy() {
        String sql = PlatformMetricQueryBuilder.seriesSql(
                "databuff",
                1_700_000_000_000L,
                1_700_000_360_000L,
                List.of("ingest.otel.trace.req", "ingest.otel.trace.fail"),
                List.of(),
                List.of(),
                List.of("ingest"),
                List.of(),
                List.of(),
                List.of("metric"),
                "cnt",
                60);
        assertThat(sql).contains("databuff.`metric_platform`");
        assertThat(sql).contains("`metric` IN ('ingest.otel.trace.req', 'ingest.otel.trace.fail')");
        assertThat(sql).contains("`component` IN ('ingest')");
        assertThat(sql).contains("SUM(`cnt`) AS metric_value");
        assertThat(sql).contains("GROUP BY epoch_sec, `metric`");
    }

    @Test
    void seriesSqlSupportsMetricPrefixSuffixAndAutoValue() {
        String sql = PlatformMetricQueryBuilder.seriesSql(
                "databuff",
                1_700_000_000_000L,
                1_700_000_360_000L,
                List.of(),
                List.of("ingest.cluster.forward."),
                List.of(".cost_ms"),
                List.of(),
                List.of(),
                List.of(),
                List.of("metric", "dim"),
                "auto",
                120);
        assertThat(sql).contains("`metric` LIKE 'ingest.cluster.forward.%'");
        assertThat(sql).contains("`metric` LIKE '%.cost_ms'");
        assertThat(sql).contains("GROUP BY epoch_sec, `metric`, `dim`");
        assertThat(sql).contains("CASE");
        assertThat(sql).contains("FLOOR(`ts` / 1000 / 120)");
    }

    @Test
    void emptyMetricSelectorForcesNoRows() {
        String sql = PlatformMetricQueryBuilder.seriesSql(
                "databuff", 0, 60_000,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of("metric"), "cnt", 60);
        assertThat(sql).contains("AND 1 = 0");
    }

    @Test
    void tagValuesAllowsOpenMetricFilter() {
        String sql = PlatformMetricQueryBuilder.tagValuesSql(
                "databuff", 0, 60_000, "instance",
                List.of(), List.of(), List.of(), List.of("ingest"), List.of(), List.of(), 50);
        assertThat(sql).doesNotContain("1 = 0");
        assertThat(sql).contains("SELECT DISTINCT `instance` AS tag_value");
        assertThat(sql).contains("`component` IN ('ingest')");
        assertThat(sql).contains("LIMIT 50");
    }

    @Test
    void escapesSqlLiterals() {
        String sql = PlatformMetricQueryBuilder.seriesSql(
                "databuff", 0, 60_000,
                List.of("a'b"), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of("metric"), "gauge", 60);
        assertThat(sql).contains("'a''b'");
        assertThat(sql).contains("MAX(`val_gauge`)");
    }
}
