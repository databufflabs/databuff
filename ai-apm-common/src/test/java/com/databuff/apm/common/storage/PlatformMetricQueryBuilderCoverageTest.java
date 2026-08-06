package com.databuff.apm.common.storage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformMetricQueryBuilderCoverageTest {

    private static final long FROM = 1_700_000_000_000L;
    private static final long TO = 1_700_000_360_000L;

    @Test
    void summarySql_withGroups() {
        String sql = PlatformMetricQueryBuilder.summarySql(
                "databuff", FROM, TO,
                List.of("ingest.otel.trace.req"), List.of(), List.of(),
                List.of("ingest"), List.of("host-1"), List.of(),
                List.of("metric", "instance"), "auto");
        assertThat(sql).contains("SELECT `metric`, `instance`, CASE");
        assertThat(sql).contains("`component` IN ('ingest')");
        assertThat(sql).contains("`instance` IN ('host-1')");
        assertThat(sql).contains("GROUP BY `metric`, `instance`");
        assertThat(sql).contains("ORDER BY `metric` ASC, `instance` ASC");
    }

    @Test
    void summarySql_withoutExplicitGroupsDefaultsToMetric() {
        String sql = PlatformMetricQueryBuilder.summarySql(
                "databuff", FROM, TO,
                List.of("a"), List.of(), List.of(),
                List.of(), List.of(), List.of(),
                List.of(), "sum");
        assertThat(sql).contains("SELECT `metric`, SUM(`val_sum`) AS metric_value");
        assertThat(sql).contains("GROUP BY `metric`");
        assertThat(sql).contains("ORDER BY `metric` ASC");
    }

    @Test
    void summarySql_emptyMetricsForcesNoRows() {
        String sql = PlatformMetricQueryBuilder.summarySql(
                "databuff", FROM, TO,
                List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(),
                List.of("metric"), "avg");
        assertThat(sql).contains("AND 1 = 0");
        assertThat(sql).contains("SUM(`val_sum`) / NULLIF(SUM(`cnt`), 0)");
    }

    @Test
    void summarySql_suffixOnlyFilter() {
        String sql = PlatformMetricQueryBuilder.summarySql(
                "databuff", FROM, TO,
                List.of(), List.of(), List.of(".fail"),
                List.of(), List.of(), List.of(),
                List.of("metric"), "max");
        assertThat(sql).contains("`metric` LIKE '%.fail'");
        assertThat(sql).contains("MAX(`val_max`)");
    }

    @Test
    void valueExpression_supportsAllModes() {
        assertThat(PlatformMetricQueryBuilder.valueExpression("cnt")).isEqualTo("SUM(`cnt`)");
        assertThat(PlatformMetricQueryBuilder.valueExpression("sum")).isEqualTo("SUM(`val_sum`)");
        assertThat(PlatformMetricQueryBuilder.valueExpression("avg"))
                .isEqualTo("SUM(`val_sum`) / NULLIF(SUM(`cnt`), 0)");
        assertThat(PlatformMetricQueryBuilder.valueExpression("max")).isEqualTo("MAX(`val_max`)");
        assertThat(PlatformMetricQueryBuilder.valueExpression("min")).isEqualTo("MIN(`val_min`)");
        assertThat(PlatformMetricQueryBuilder.valueExpression("gauge")).isEqualTo("MAX(`val_gauge`)");
        assertThat(PlatformMetricQueryBuilder.valueExpression("auto")).contains("CASE");
        assertThat(PlatformMetricQueryBuilder.valueExpression(null)).contains("CASE");
        assertThat(PlatformMetricQueryBuilder.valueExpression("bogus")).contains("CASE");
    }

    @Test
    void tagValuesSql_clampsLimit() {
        String sql = PlatformMetricQueryBuilder.tagValuesSql(
                "databuff", FROM, TO, "instance",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 0);
        assertThat(sql).contains("LIMIT 200");
        assertThat(PlatformMetricQueryBuilder.tagValuesSql(
                "databuff", FROM, TO, "dim",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), -5))
                .contains("LIMIT 200");
        assertThat(PlatformMetricQueryBuilder.tagValuesSql(
                "databuff", FROM, TO, "metric",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 9999))
                .contains("LIMIT 200");
        assertThat(PlatformMetricQueryBuilder.tagValuesSql(
                "databuff", FROM, TO, "metric",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 7))
                .contains("LIMIT 7");
    }

    @Test
    void hasMetricSelector_coversOverloads() {
        assertThat(PlatformMetricQueryBuilder.hasMetricSelector(List.of("a"), List.of(), List.of())).isTrue();
        assertThat(PlatformMetricQueryBuilder.hasMetricSelector(List.of(), List.of("p"), List.of())).isTrue();
        assertThat(PlatformMetricQueryBuilder.hasMetricSelector(List.of(), List.of(), List.of("s"))).isTrue();
        assertThat(PlatformMetricQueryBuilder.hasMetricSelector(List.of(), List.of(), List.of())).isFalse();
        assertThat(PlatformMetricQueryBuilder.hasMetricSelector(List.of(), null)).isFalse();
        assertThat(PlatformMetricQueryBuilder.hasMetricSelector(List.of("m"), null)).isTrue();
    }

    @Test
    void normalizeGroupBy_dedupesAndFiltersInvalidColumns() {
        assertThat(PlatformMetricQueryBuilder.normalizeGroupBy(null)).containsExactly("metric");
        assertThat(PlatformMetricQueryBuilder.normalizeGroupBy(List.of())).containsExactly("metric");
        assertThat(PlatformMetricQueryBuilder.normalizeGroupBy(
                java.util.Arrays.asList(null, "  ", "metric", "METRIC", "instance", "bogus", "dim")))
                .containsExactly("metric", "instance", "dim");
    }

    @Test
    void normalizeValueMode_defaultsToAuto() {
        assertThat(PlatformMetricQueryBuilder.normalizeValueMode(null)).isEqualTo("auto");
        assertThat(PlatformMetricQueryBuilder.normalizeValueMode("")).isEqualTo("auto");
        assertThat(PlatformMetricQueryBuilder.normalizeValueMode("  SUM  ")).isEqualTo("sum");
        assertThat(PlatformMetricQueryBuilder.normalizeValueMode("nope")).isEqualTo("auto");
    }

    @Test
    void normalizeTag_throwsOnMissingOrUnsupported() {
        assertThat(PlatformMetricQueryBuilder.normalizeTag("instance")).isEqualTo("instance");
        assertThat(PlatformMetricQueryBuilder.normalizeTag(" DIM ")).isEqualTo("dim");
        assertThatThrownBy(() -> PlatformMetricQueryBuilder.normalizeTag(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlatformMetricQueryBuilder.normalizeTag(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlatformMetricQueryBuilder.normalizeTag("bogus"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clampStep_boundsAndAligns() {
        assertThat(PlatformMetricQueryBuilder.clampStep(0)).isEqualTo(60);
        assertThat(PlatformMetricQueryBuilder.clampStep(-5)).isEqualTo(60);
        assertThat(PlatformMetricQueryBuilder.clampStep(30)).isEqualTo(60);
        assertThat(PlatformMetricQueryBuilder.clampStep(100)).isEqualTo(60);
        assertThat(PlatformMetricQueryBuilder.clampStep(150)).isEqualTo(120);
        assertThat(PlatformMetricQueryBuilder.clampStep(3600)).isEqualTo(3600);
        assertThat(PlatformMetricQueryBuilder.clampStep(5000)).isEqualTo(3600);
    }

    @Test
    void inFilter_nullValuesOmitClause() {
        String sql = PlatformMetricQueryBuilder.seriesSql(
                "databuff", FROM, TO,
                List.of("a"), List.of(), List.of(),
                List.of(), null, List.of(),
                List.of("metric"), "cnt", 60);
        assertThat(sql).doesNotContain("`instance` IN");
    }

    @Test
    void seriesSql_suffixOnlyFilter() {
        String sql = PlatformMetricQueryBuilder.seriesSql(
                "databuff", FROM, TO,
                List.of(), List.of(), List.of(".cost_ms"),
                List.of(), List.of(), List.of(),
                List.of("metric", "dim"), "min", 60);
        assertThat(sql).contains("AND (`metric` LIKE '%.cost_ms')");
        assertThat(sql).contains("MIN(`val_min`)");
        assertThat(sql).contains("GROUP BY epoch_sec, `metric`, `dim`");
    }

    @Test
    void normalizeList_filtersBlankNullAndOverlongValues() {
        String overlong = "x".repeat(300);
        String sql = PlatformMetricQueryBuilder.seriesSql(
                "databuff", FROM, TO,
                List.of("good"), List.of(), List.of(),
                java.util.Arrays.asList(null, "", "  ing  ", overlong, "a'b"), List.of(), List.of(),
                List.of("metric"), "cnt", 60);
        assertThat(sql).contains("`component` IN ('ing', 'a''b')");
        assertThat(sql).doesNotContain(overlong);
    }
}
