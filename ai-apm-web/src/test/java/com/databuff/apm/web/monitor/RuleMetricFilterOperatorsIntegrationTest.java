package com.databuff.apm.web.monitor;

import com.databuff.apm.web.TestMetricCoreSupport;
import com.databuff.apm.web.TestStorageSupport;
import com.databuff.apm.web.metric.MetricQueryService;
import com.databuff.apm.common.storage.ApmReadRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proves detection-rule filter operators from UI ({@code =}/{@code !=}/{@code like}/{@code notLike})
 * flow into the Doris SQL used by alarm evaluation and metric preview charts.
 */
class RuleMetricFilterOperatorsIntegrationTest {

    @ParameterizedTest(name = "eval operator {0}")
    @CsvSource({
            "=,checkout,`service` = 'checkout'",
            "!=,checkout,`service` != 'checkout'",
            "like,check,`service` LIKE '%check%'",
            "notLike,check,`service` NOT LIKE '%check%'"
    })
    void alarmEvaluationSqlIncludesFilterOperator(String operator, String right, String expectedFragment)
            throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryMetricScalar(anyString())).thenReturn(42.0);

        RuleMetricEvaluationService service = new RuleMetricEvaluationService(
                reader, TestStorageSupport.storage(), TestMetricCoreSupport.catalogWithServiceMetrics());

        double value = service.evaluateRule(thresholdRule(operator, right), 60_000L);
        assertThat(value).isEqualTo(42.0);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(reader).queryMetricScalar(sqlCaptor.capture());
        assertThat(sqlCaptor.getValue())
                .as("evaluation SQL must apply UI filter operator %s", operator)
                .contains(expectedFragment);
    }

    @Test
    void metricPreviewChartSqlIncludesAllFourFilterOperators() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryMetricSeries(anyString())).thenReturn(List.of());

        MetricQueryService queryService = new MetricQueryService(reader, TestStorageSupport.storage());
        queryService.metricChart(Map.of(
                "start", 1_710_000_000L,
                "end", 1_710_003_600L,
                "query", Map.of("A", Map.of(
                        "metric", "service.avgDuration",
                        "aggs", "avg",
                        "from", List.of(
                                Map.of("connector", "AND", "left", "service", "operator", "=", "right", "checkout"),
                                Map.of("connector", "AND", "left", "service", "operator", "!=", "right", "other"),
                                Map.of("connector", "AND", "left", "service", "operator", "like", "right", "check"),
                                Map.of("connector", "AND", "left", "service", "operator", "notLike", "right", "slow"))))));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(reader).queryMetricSeries(sqlCaptor.capture());
        assertThat(sqlCaptor.getValue())
                .contains("`service` = 'checkout'")
                .contains("`service` != 'other'")
                .contains("`service` LIKE '%check%'")
                .contains("`service` NOT LIKE '%slow%'");
    }

    private static EventRule thresholdRule(String operator, String right) {
        String queryJson = "{"
                + "\"1\":{"
                + "\"way\":\"threshold\",\"period\":60,\"unit\":\"ms\","
                + "\"thresholds\":{\"critical\":1000},"
                + "\"A\":{"
                + "\"metric\":\"service.avgDuration\",\"aggs\":\"avg\",\"by\":[],"
                + "\"from\":[{"
                + "\"connector\":\"AND\",\"left\":\"service\","
                + "\"operator\":\"" + operator + "\",\"right\":\"" + right + "\","
                + "\"caseInsensitive\":true"
                + "}]"
                + "}"
                + "}"
                + "}";
        return new EventRule(
                1L,
                "filter operator proof",
                EventRule.CLASSIFY_SINGLE,
                EventRule.WAY_THRESHOLD,
                "*",
                "service.avgDuration",
                1000,
                EventRule.COMPARATOR_GT,
                true,
                queryJson,
                Instant.now());
    }
}
