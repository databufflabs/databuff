package com.databuff.apm.web.monitor.eval;

import com.databuff.apm.web.monitor.EventRule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EventRulePayloadParserMoreTest {

    @Test
    void derivesDefaultsWhenQueryMissing() {
        EventRulePayloadParser.DerivedFields derived = EventRulePayloadParser.derive(Map.of());

        assertThat(derived.classify()).isEqualTo(EventRule.CLASSIFY_SINGLE);
        assertThat(derived.detectionWay()).isEqualTo(EventRule.WAY_THRESHOLD);
        assertThat(derived.service()).isEqualTo("*");
        assertThat(derived.metric()).isEqualTo(EventRule.METRIC_ERROR_RATE);
        assertThat(derived.threshold()).isEqualTo(0.05);
        assertThat(derived.comparator()).isEqualTo(EventRule.COMPARATOR_GT);
        assertThat(derived.queryJson()).isNull();
    }

    @Test
    void derivesBodyFieldsWhenQueryEmpty() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", Map.of());
        body.put("detectionWay", EventRule.WAY_MUTATION);
        body.put("service", "demo-order");
        body.put("metric", "custom.metric");
        body.put("threshold", "0.2");
        body.put("comparator", "<");

        EventRulePayloadParser.DerivedFields derived = EventRulePayloadParser.derive(body);

        assertThat(derived.detectionWay()).isEqualTo(EventRule.WAY_MUTATION);
        assertThat(derived.service()).isEqualTo("demo-order");
        assertThat(derived.metric()).isEqualTo("custom.metric");
        assertThat(derived.threshold()).isEqualTo(0.2);
        assertThat(derived.comparator()).isEqualTo("<");
    }

    @Test
    void normalizesWayToThresholdForUnknown() {
        assertThat(EventRulePayloadParser.normalizeWay("baseline")).isEqualTo(EventRule.WAY_THRESHOLD);
        assertThat(EventRulePayloadParser.normalizeWay(null)).isEqualTo(EventRule.WAY_THRESHOLD);
    }

    @Test
    void extractsServiceFallbackToWildcard() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("A", Map.of("from", List.of(Map.of("type", "component", "value", "x"))));
        assertThat(EventRulePayloadParser.extractService(item)).isEqualTo("*");

        assertThat(EventRulePayloadParser.extractService(Map.of("A", "not-map"))).isEqualTo("*");
        assertThat(EventRulePayloadParser.extractService(Map.of())).isEqualTo("*");
    }

    @Test
    void extractsMetricIdentifierFromRuleAndFallbacks() {
        EventRule rule = rule(EventRule.METRIC_REQUEST_COUNT,
                "{\"1\":{\"A\":{\"metric\":\"service.exception.cnt\"}}}");
        assertThat(EventRulePayloadParser.extractMetricIdentifier(rule))
                .isEqualTo("service.exception.cnt");

        EventRule countRule = rule(EventRule.METRIC_REQUEST_COUNT, "{\"1\":{}}");
        assertThat(EventRulePayloadParser.extractMetricIdentifier(countRule)).isEqualTo("service.cnt");

        EventRule errorRule = rule(EventRule.METRIC_ERROR_RATE, "{\"1\":{}}");
        assertThat(EventRulePayloadParser.extractMetricIdentifier(errorRule)).isEqualTo("service.error.pct");
    }

    @Test
    void extractsViewUnitWhenPresent() {
        assertThat(EventRulePayloadParser.extractViewUnit(null)).isNull();
        assertThat(EventRulePayloadParser.extractViewUnit(Map.of())).isNull();
        assertThat(EventRulePayloadParser.extractViewUnit(Map.of("view_unit", "ms"))).isEqualTo("ms");
    }

    @Test
    void extractsThresholdLevelsFromNestedAndPlainValues() {
        Map<String, Object> thresholds = new LinkedHashMap<>();
        thresholds.put("critical", Map.of("value", 5));
        thresholds.put("warning", "7");
        EventRulePayloadParser.ThresholdLevels levels =
                EventRulePayloadParser.extractThresholdLevels(Map.of("thresholds", thresholds));
        assertThat(levels.critical()).isEqualTo(5.0);
        assertThat(levels.warning()).isEqualTo(7.0);

        assertThat(EventRulePayloadParser.extractThresholdLevels(Map.of("thresholds", "no-map")).hasCritical())
                .isFalse();
        assertThat(EventRulePayloadParser.extractThresholdLevels(Map.of()).hasCritical()).isFalse();
    }

    @Test
    void extractThresholdFallsBackToBodyWhenNoCritical() {
        Map<String, Object> item = Map.of("thresholds", Map.of("warning", 1));
        assertThat(EventRulePayloadParser.extractThreshold(item, Map.of("threshold", 0.3)))
                .isEqualTo(0.3);
    }

    @Test
    void extractsComparatorDefaultsToGt() {
        assertThat(EventRulePayloadParser.extractComparator(Map.of())).isEqualTo(EventRule.COMPARATOR_GT);
    }

    @Test
    void normalizesFluctuateModes() {
        assertThat(EventRulePayloadParser.extractFluctuate(Map.of("fluctuate", "valDown")))
                .isEqualTo(EventRule.FLUCTUATE_VAL_DOWN);
        assertThat(EventRulePayloadParser.extractFluctuate(Map.of("fluctuate", "yoyUp")))
                .isEqualTo(EventRule.FLUCTUATE_YOY_UP);
        assertThat(EventRulePayloadParser.extractFluctuate(Map.of("fluctuate", "bogus")))
                .isEqualTo(EventRule.FLUCTUATE_VAL_UP);
        assertThat(EventRulePayloadParser.extractFluctuate(Map.of())).isEqualTo(EventRule.FLUCTUATE_VAL_UP);
    }

    @Test
    void detectsYoyFluctuate() {
        assertThat(EventRulePayloadParser.isYoyFluctuate(EventRule.FLUCTUATE_YOY_UP)).isTrue();
        assertThat(EventRulePayloadParser.isYoyFluctuate(EventRule.FLUCTUATE_YOY_DOWN)).isTrue();
        assertThat(EventRulePayloadParser.isYoyFluctuate(EventRule.FLUCTUATE_VAL_UP)).isFalse();
    }

    @Test
    void lookbackMinutesComputesFromPeriod() {
        assertThat(EventRulePayloadParser.lookbackMinutes(Map.of())).isEqualTo(5);
        assertThat(EventRulePayloadParser.lookbackMinutes(Map.of("period", 600))).isEqualTo(10);
        assertThat(EventRulePayloadParser.lookbackMinutes(Map.of("period", 30))).isEqualTo(1);
    }

    @Test
    void extractComparePeriodMillisFallsBackAndConverts() {
        assertThat(EventRulePayloadParser.extractComparePeriodMillis(Map.of(), 300_000L))
                .isEqualTo(300_000L);
        assertThat(EventRulePayloadParser.extractComparePeriodMillis(Map.of("comparePeriod", 600), 300_000L))
                .isEqualTo(600_000L);
    }

    @Test
    void parseQueryHandlesInvalidJson() {
        assertThat(EventRulePayloadParser.parseQuery("{not-json")).isEmpty();
        assertThat(EventRulePayloadParser.parseQuery(null)).isEmpty();
        assertThat(EventRulePayloadParser.parseQuery("  ")).isEmpty();
    }

    @Test
    void serializeQueryHandlesStringsAndMaps() {
        assertThat(EventRulePayloadParser.serializeQuery(null)).isNull();
        assertThat(EventRulePayloadParser.serializeQuery("  ")).isNull();
        assertThat(EventRulePayloadParser.serializeQuery("{\"a\":1}")).isEqualTo("{\"a\":1}");
        assertThat(EventRulePayloadParser.serializeQuery(Map.of("a", 1))).isEqualTo("{\"a\":1}");
    }

    @Test
    void primaryGroupByFieldsEdgeCases() {
        assertThat(EventRulePayloadParser.extractPrimaryGroupByFields(null)).isEmpty();
        assertThat(EventRulePayloadParser.extractPrimaryGroupByFields(Map.of("A", "no-map"))).isEmpty();
        assertThat(EventRulePayloadParser.extractPrimaryGroupByFields(
                Map.of("A", Map.of("by", "not-list")))).isEmpty();
        java.util.List<Object> byValues = new java.util.ArrayList<>();
        byValues.add("service");
        byValues.add(null);
        byValues.add("  ");
        byValues.add("host");
        assertThat(EventRulePayloadParser.extractPrimaryGroupByFields(
                Map.of("A", Map.of("by", byValues))))
                .containsExactly("service", "host");
    }

    @Test
    void groupByFieldsSkipsNonMetricEntries() {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("1", Map.of("expr", List.of("x")));
        assertThat(EventRulePayloadParser.extractGroupByFields("{\"1\":{\"expr\":[1]}}")).isEmpty();
        assertThat(EventRulePayloadParser.extractGroupByFields(null)).isEmpty();
    }

    @Test
    void sanitizeQueryJsonKeepsInvalidInput() {
        assertThat(EventRulePayloadParser.sanitizeQueryJson("{bad-json")).isEqualTo("{bad-json");
    }

    @Test
    void mapsMetricNamesByKeyword() {
        assertThat(EventRulePayloadParser.extractMetric(
                Map.of("A", Map.of("metric", "service.error.pct"))))
                .isEqualTo(EventRule.METRIC_ERROR_RATE);
        assertThat(EventRulePayloadParser.extractMetric(
                Map.of("A", Map.of("metric", "service.throughput"))))
                .isEqualTo(EventRule.METRIC_REQUEST_COUNT);
        assertThat(EventRulePayloadParser.extractMetric(
                Map.of("A", Map.of("metric", "service.call.cnt"))))
                .isEqualTo(EventRule.METRIC_REQUEST_COUNT);
        assertThat(EventRulePayloadParser.extractMetric(Map.of("A", "no-map")))
                .isEqualTo(EventRule.METRIC_ERROR_RATE);
    }

    @Test
    void deriveWithNonMapPrimaryUsesDefaults() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", Map.of("1", "not-a-map"));

        EventRulePayloadParser.DerivedFields derived = EventRulePayloadParser.derive(body);

        assertThat(derived.detectionWay()).isEqualTo(EventRule.WAY_THRESHOLD);
        assertThat(derived.service()).isEqualTo("*");
        assertThat(derived.metric()).isEqualTo(EventRule.METRIC_ERROR_RATE);
        assertThat(derived.comparator()).isEqualTo(EventRule.COMPARATOR_GT);
    }

    private static EventRule rule(String metric, String queryJson) {
        return new EventRule(
                1L, "rule", EventRule.CLASSIFY_SINGLE, EventRule.WAY_THRESHOLD,
                "*", metric, 0.05, EventRule.COMPARATOR_GT, true, queryJson, Instant.now());
    }
}
