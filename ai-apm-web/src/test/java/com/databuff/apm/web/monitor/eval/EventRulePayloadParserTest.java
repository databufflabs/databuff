package com.databuff.apm.web.monitor.eval;

import com.databuff.apm.web.monitor.EventRule;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EventRulePayloadParserTest {

    @Test
    void derivesServiceThresholdAndQueryFromBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("classification", "singleMetric");
        body.put("ruleName", "order error rate");
        body.put("query", Map.of(
                "1", Map.of(
                        "way", "threshold",
                        "period", 300,
                        "A", Map.of(
                                "metric", "databuff.service.error_rate",
                                "from", List.of(Map.of("type", "service", "value", "demo-order"))),
                        "thresholds", Map.of(
                                "critical", Map.of("value", 0.08, "comparator", ">")))));

        EventRulePayloadParser.DerivedFields derived = EventRulePayloadParser.derive(body);

        assertThat(derived.classify()).isEqualTo("singleMetric");
        assertThat(derived.service()).isEqualTo("demo-order");
        assertThat(derived.threshold()).isEqualTo(0.08);
        assertThat(derived.comparator()).isEqualTo(EventRule.COMPARATOR_GT);
        assertThat(derived.metric()).isEqualTo(EventRule.METRIC_ERROR_RATE);
        assertThat(derived.queryJson()).contains("demo-order");
    }

    @Test
    void derivesLessThanComparatorFromFrontendComparisonField() {
        Map<String, Object> queryItem = new LinkedHashMap<>();
        queryItem.put("way", "threshold");
        queryItem.put("period", 300);
        queryItem.put("comparison", "<");
        Map<String, Object> thresholds = new LinkedHashMap<>();
        thresholds.put("critical", 1);
        queryItem.put("thresholds", thresholds);
        queryItem.put("A", Map.of(
                "metric", "service.cnt",
                "aggs", "sum",
                "by", List.of(),
                "from", List.of(Map.of(
                        "left", "service",
                        "operator", "=",
                        "right", "order-service",
                        "connector", "AND"))));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", Map.of("1", queryItem));

        EventRulePayloadParser.DerivedFields derived = EventRulePayloadParser.derive(body);

        assertThat(derived.comparator()).isEqualTo(EventRule.COMPARATOR_LT);
        assertThat(derived.threshold()).isEqualTo(1.0);
        assertThat(derived.metric()).isEqualTo(EventRule.METRIC_REQUEST_COUNT);
        assertThat(derived.service()).isEqualTo("order-service");
    }

    @Test
    void derivesGreaterOrEqualFromNestedCriticalComparator() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", Map.of(
                "1", Map.of(
                        "way", "threshold",
                        "thresholds", Map.of(
                                "critical", Map.of("value", 10, "comparator", ">=")),
                        "A", Map.of("metric", "service.exception.cnt", "from", List.of()))));

        EventRulePayloadParser.DerivedFields derived = EventRulePayloadParser.derive(body);

        assertThat(derived.comparator()).isEqualTo(EventRule.COMPARATOR_GTE);
        assertThat(derived.threshold()).isEqualTo(10.0);
    }

    @Test
    void extractsCriticalAndWarningThresholdLevels() {
        Map<String, Object> primary = new LinkedHashMap<>();
        Map<String, Object> thresholds = new LinkedHashMap<>();
        thresholds.put("critical", 10);
        thresholds.put("warning", 5);
        primary.put("thresholds", thresholds);

        EventRulePayloadParser.ThresholdLevels levels =
                EventRulePayloadParser.extractThresholdLevels(primary);

        assertThat(levels.hasCritical()).isTrue();
        assertThat(levels.hasWarning()).isTrue();
        assertThat(levels.critical()).isEqualTo(10.0);
        assertThat(levels.warning()).isEqualTo(5.0);
    }

    @Test
    void extractsMissingWarningAsAbsent() {
        Map<String, Object> primary = Map.of("thresholds", Map.of("critical", 1));

        EventRulePayloadParser.ThresholdLevels levels =
                EventRulePayloadParser.extractThresholdLevels(primary);

        assertThat(levels.hasCritical()).isTrue();
        assertThat(levels.hasWarning()).isFalse();
    }

    @Test
    void treatsNullAndBlankWarningAsAbsent() {
        Map<String, Object> thresholds = new LinkedHashMap<>();
        thresholds.put("critical", 1);
        thresholds.put("warning", null);
        Map<String, Object> primary = new LinkedHashMap<>();
        primary.put("thresholds", thresholds);

        assertThat(EventRulePayloadParser.extractThresholdLevels(primary).hasWarning()).isFalse();

        thresholds.put("warning", "");
        assertThat(EventRulePayloadParser.extractThresholdLevels(primary).hasWarning()).isFalse();

        thresholds.put("warning", "null");
        assertThat(EventRulePayloadParser.extractThresholdLevels(primary).hasWarning()).isFalse();
    }

    @Test
    void sanitizesUnsupportedQueryFields() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", Map.of(
                "1", Map.of(
                        "way", "baseline",
                        "expr", "A+B",
                        "exprName", "custom metric",
                        "no_data_timeframe", 10,
                        "require_full_window", true,
                        "evaluation_delay", 5,
                        "A", Map.of(
                                "metric", "service.cnt",
                                "from", List.of(Map.of("left", "service", "right", "demo-order")),
                                "by", List.of("service")),
                        "B", Map.of(
                                "metric", "service.error.pct",
                                "by", List.of("service")),
                        "thresholds", Map.of(
                                "critical", Map.of("value", 1, "comparator", ">")))));

        EventRulePayloadParser.DerivedFields derived = EventRulePayloadParser.derive(body);

        assertThat(derived.detectionWay()).isEqualTo(EventRule.WAY_THRESHOLD);
        assertThat(derived.queryJson()).doesNotContain("expr", "exprName", "evaluation_delay", "require_full_window", "\"B\"");
    }

    @Test
    void extractsPrimaryGroupByFieldsFromPrimaryQueryItem() {
        Map<String, Object> primary = Map.of(
                "way", "threshold",
                "A", Map.of(
                        "metric", "service.cnt",
                        "by", List.of("service", "hostName")));

        assertThat(EventRulePayloadParser.extractPrimaryGroupByFields(primary))
                .containsExactly("service", "hostName");
    }

    @Test
    void extractsDistinctGroupByFieldsFromQuery() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", Map.of(
                "1", Map.of(
                        "way", "threshold",
                        "A", Map.of(
                                "metric", "service.cnt",
                                "by", List.of("service", "hostName")),
                        "B", Map.of(
                                "metric", "service.error.pct",
                                "by", List.of("service", "resource")))));

        EventRulePayloadParser.DerivedFields derived = EventRulePayloadParser.derive(body);

        assertThat(EventRulePayloadParser.extractGroupByFields(derived.queryJson()))
                .containsExactly("service", "hostName");
    }
}
