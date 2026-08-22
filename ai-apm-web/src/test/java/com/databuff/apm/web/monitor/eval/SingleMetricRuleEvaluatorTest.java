package com.databuff.apm.web.monitor.eval;

import com.databuff.apm.web.TestMetricCoreSupport;
import com.databuff.apm.web.monitor.EventRule;
import com.databuff.apm.web.monitor.RuleMetricEvaluationService;
import com.databuff.apm.web.monitor.RuleMetricEvaluationService.GroupMetricValue;
import com.databuff.apm.web.monitor.ThresholdEvaluationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SingleMetricRuleEvaluatorTest {

    private static EventRule mutationRule(String fluctuate, double threshold, String comparePeriodSec) {
        String queryJson = "{\"1\":{\"way\":\"mutation\",\"period\":300"
                + (comparePeriodSec == null ? "" : ",\"comparePeriod\":" + comparePeriodSec)
                + ",\"fluctuate\":\"" + fluctuate + "\""
                + ",\"view_unit\":\"%\""
                + ",\"thresholds\":{\"critical\":" + threshold + "}"
                + ",\"A\":{\"metric\":\"service.error.pct\",\"from\":[]}}}";
        return new EventRule(
                1L,
                "mutation rule",
                EventRule.CLASSIFY_SINGLE,
                EventRule.WAY_MUTATION,
                "checkout",
                EventRule.METRIC_ERROR_RATE,
                threshold,
                EventRule.COMPARATOR_GT,
                true,
                queryJson,
                java.time.Instant.now());
    }

    private static SingleMetricRuleEvaluator evaluator(RuleMetricEvaluationService metricService) {
        ThresholdEvaluationService thresholdService = mock(ThresholdEvaluationService.class);
        ThresholdAlarmMessageFormatter formatter =
                new ThresholdAlarmMessageFormatter(TestMetricCoreSupport.catalogWithServiceMetrics());
        return new SingleMetricRuleEvaluator(thresholdService, metricService, formatter);
    }

    @Test
    void computeMutationChangeValUpReturnsSignedIncrease() {
        assertThat(SingleMetricRuleEvaluator.computeMutationChange(
                EventRule.FLUCTUATE_VAL_UP, 8, 5)).isEqualTo(3);
        assertThat(SingleMetricRuleEvaluator.computeMutationChange(
                EventRule.FLUCTUATE_VAL_UP, 3, 5)).isEqualTo(-2);
    }

    @Test
    void computeMutationChangeValDownReturnsSignedDecrease() {
        assertThat(SingleMetricRuleEvaluator.computeMutationChange(
                EventRule.FLUCTUATE_VAL_DOWN, 3, 5)).isEqualTo(2);
        assertThat(SingleMetricRuleEvaluator.computeMutationChange(
                EventRule.FLUCTUATE_VAL_DOWN, 8, 5)).isEqualTo(-3);
    }

    @Test
    void computeMutationChangeYoyUpReturnsRatio() {
        // (8 - 5) / 5 = 0.6
        assertThat(SingleMetricRuleEvaluator.computeMutationChange(
                EventRule.FLUCTUATE_YOY_UP, 8, 5)).isEqualTo(0.6);
        // decrease yields negative ratio -> won't breach a non-negative threshold
        assertThat(SingleMetricRuleEvaluator.computeMutationChange(
                EventRule.FLUCTUATE_YOY_UP, 4, 5)).isEqualTo(-0.2);
    }

    @Test
    void computeMutationChangeYoyDownReturnsNegativeRatio() {
        // (5 - 4) / 5 = 0.2
        assertThat(SingleMetricRuleEvaluator.computeMutationChange(
                EventRule.FLUCTUATE_YOY_DOWN, 4, 5)).isEqualTo(0.2);
        assertThat(SingleMetricRuleEvaluator.computeMutationChange(
                EventRule.FLUCTUATE_YOY_DOWN, 6, 5)).isEqualTo(-0.2);
    }

    @Test
    void computeMutationChangeYoyWithZeroPreviousIsUndefined() {
        assertThat(SingleMetricRuleEvaluator.computeMutationChange(
                EventRule.FLUCTUATE_YOY_UP, 8, 0)).isNaN();
        assertThat(SingleMetricRuleEvaluator.computeMutationChange(
                EventRule.FLUCTUATE_YOY_DOWN, 8, 0)).isNaN();
    }

    @Test
    void yoyUpTriggersWhenRatioExceedsThreshold() {
        RuleMetricEvaluationService metricService = mock(RuleMetricEvaluationService.class);
        // current 8%, previous 5% -> ratio 0.6 > threshold 0.5
        when(metricService.evaluateRuleGroups(any(), anyLong(), anyLong()))
                .thenReturn(List.of(new GroupMetricValue("checkout", "checkout", 8.0)))
                .thenReturn(List.of(new GroupMetricValue("checkout", "checkout", 5.0)));

        List<RuleEvaluationResult> results = evaluator(metricService)
                .evaluateAll(mutationRule("yoyUp", 0.5, "3600"), 300_000L);

        assertThat(results).hasSize(1);
        RuleEvaluationResult result = results.get(0);
        assertThat(result.triggered()).isTrue();
        assertThat(result.detectionWay()).isEqualTo(EventRule.WAY_MUTATION);
        // delta 0.6 * 100 = 60%, threshold 0.5 * 100 = 50%
        assertThat(result.message()).isEqualTo("错误率的变化值60%超过阈值50%");
    }

    @Test
    void yoyUpDoesNotTriggerWhenMetricDecreased() {
        RuleMetricEvaluationService metricService = mock(RuleMetricEvaluationService.class);
        // current 4%, previous 5% -> ratio -0.2, wrong direction
        when(metricService.evaluateRuleGroups(any(), anyLong(), anyLong()))
                .thenReturn(List.of(new GroupMetricValue("checkout", "checkout", 4.0)))
                .thenReturn(List.of(new GroupMetricValue("checkout", "checkout", 5.0)));

        List<RuleEvaluationResult> results = evaluator(metricService)
                .evaluateAll(mutationRule("yoyUp", 0.5, "3600"), 300_000L);

        assertThat(results).isEmpty();
    }

    @Test
    void yoyDownTriggersWhenMetricDecreased() {
        RuleMetricEvaluationService metricService = mock(RuleMetricEvaluationService.class);
        // current 3%, previous 5% -> (5-3)/5 = 0.4 > threshold 0.2
        when(metricService.evaluateRuleGroups(any(), anyLong(), anyLong()))
                .thenReturn(List.of(new GroupMetricValue("checkout", "checkout", 3.0)))
                .thenReturn(List.of(new GroupMetricValue("checkout", "checkout", 5.0)));

        List<RuleEvaluationResult> results = evaluator(metricService)
                .evaluateAll(mutationRule("yoyDown", 0.2, "3600"), 300_000L);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).message()).isEqualTo("错误率的变化值40%超过阈值20%");
    }

    @Test
    void valUpTriggersOnAbsoluteIncrease() {
        RuleMetricEvaluationService metricService = mock(RuleMetricEvaluationService.class);
        // current 8%, previous 5% -> +3 > threshold 2 (percentage points)
        when(metricService.evaluateRuleGroups(any(), anyLong(), anyLong()))
                .thenReturn(List.of(new GroupMetricValue("checkout", "checkout", 8.0)))
                .thenReturn(List.of(new GroupMetricValue("checkout", "checkout", 5.0)));

        List<RuleEvaluationResult> results = evaluator(metricService)
                .evaluateAll(mutationRule("valUp", 2.0, "3600"), 300_000L);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).message()).isEqualTo("错误率的变化值3%超过阈值2%");
    }

    @Test
    void valDownDoesNotTriggerOnIncrease() {
        RuleMetricEvaluationService metricService = mock(RuleMetricEvaluationService.class);
        when(metricService.evaluateRuleGroups(any(), anyLong(), anyLong()))
                .thenReturn(List.of(new GroupMetricValue("checkout", "checkout", 8.0)))
                .thenReturn(List.of(new GroupMetricValue("checkout", "checkout", 5.0)));

        List<RuleEvaluationResult> results = evaluator(metricService)
                .evaluateAll(mutationRule("valDown", 2.0, "3600"), 300_000L);

        assertThat(results).isEmpty();
    }

    @Test
    void comparePeriodOffsetsPreviousWindow() {
        RuleMetricEvaluationService metricService = mock(RuleMetricEvaluationService.class);
        when(metricService.evaluateRuleGroups(any(), anyLong(), anyLong()))
                .thenReturn(List.of(new GroupMetricValue("checkout", "checkout", 8.0)))
                .thenReturn(List.of(new GroupMetricValue("checkout", "checkout", 5.0)));

        evaluator(metricService).evaluateAll(mutationRule("yoyUp", 0.5, "3600"), 300_000L);

        ArgumentCaptor<Long> fromCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> toCaptor = ArgumentCaptor.forClass(Long.class);
        verify(metricService, times(2)).evaluateRuleGroups(any(), fromCaptor.capture(), toCaptor.capture());

        long currentFrom = fromCaptor.getAllValues().get(0);
        long currentTo = toCaptor.getAllValues().get(0);
        long previousFrom = fromCaptor.getAllValues().get(1);
        long previousTo = toCaptor.getAllValues().get(1);

        // current window length == lookback (300s)
        assertThat(currentTo - currentFrom).isEqualTo(300_000L);
        // previous window has the same length ...
        assertThat(previousTo - previousFrom).isEqualTo(300_000L);
        // ... and ends exactly comparePeriod (3600s) before the current window ends
        assertThat(currentTo - previousTo).isEqualTo(3_600_000L);
    }

    @Test
    void missingComparePeriodFallsBackToImmediatelyPrecedingWindow() {
        RuleMetricEvaluationService metricService = mock(RuleMetricEvaluationService.class);
        when(metricService.evaluateRuleGroups(any(), anyLong(), anyLong()))
                .thenReturn(List.of(new GroupMetricValue("checkout", "checkout", 8.0)))
                .thenReturn(List.of(new GroupMetricValue("checkout", "checkout", 5.0)));

        evaluator(metricService).evaluateAll(mutationRule("yoyUp", 0.5, null), 300_000L);

        ArgumentCaptor<Long> toCaptor = ArgumentCaptor.forClass(Long.class);
        verify(metricService, times(2)).evaluateRuleGroups(any(), anyLong(), toCaptor.capture());
        long currentTo = toCaptor.getAllValues().get(0);
        long previousTo = toCaptor.getAllValues().get(1);
        // fallback: previous window ends one lookback before current -> adjacent windows
        assertThat(currentTo - previousTo).isEqualTo(300_000L);
    }

    @Test
    void yoyWithZeroPreviousSkipsGroup() {
        RuleMetricEvaluationService metricService = mock(RuleMetricEvaluationService.class);
        // previous 0% -> ratio undefined, must not fire (avoids false alarm / NaN message)
        when(metricService.evaluateRuleGroups(any(), anyLong(), anyLong()))
                .thenReturn(List.of(new GroupMetricValue("checkout", "checkout", 8.0)))
                .thenReturn(List.of(new GroupMetricValue("checkout", "checkout", 0.0)));

        List<RuleEvaluationResult> results = evaluator(metricService)
                .evaluateAll(mutationRule("yoyUp", 0.5, "3600"), 300_000L);

        assertThat(results).isEmpty();
    }

    @Test
    void missingGroupInPreviousWindowTreatedAsZero() {
        RuleMetricEvaluationService metricService = mock(RuleMetricEvaluationService.class);
        // previous window has no matching group -> previousValue 0 -> yoy undefined -> skip
        when(metricService.evaluateRuleGroups(any(), anyLong(), anyLong()))
                .thenReturn(List.of(new GroupMetricValue("checkout", "checkout", 8.0)))
                .thenReturn(List.of());

        List<RuleEvaluationResult> results = evaluator(metricService)
                .evaluateAll(mutationRule("yoyUp", 0.5, "3600"), 300_000L);

        assertThat(results).isEmpty();
    }

    @Test
    void parserExtractsComparePeriodAndFluctuate() {
        Map<String, Object> primary = Map.of(
                "period", 300,
                "comparePeriod", 3600,
                "fluctuate", "yoyDown");

        assertThat(EventRulePayloadParser.extractComparePeriodMillis(primary, 300_000L))
                .isEqualTo(3_600_000L);
        assertThat(EventRulePayloadParser.extractFluctuate(primary))
                .isEqualTo(EventRule.FLUCTUATE_YOY_DOWN);
        assertThat(EventRulePayloadParser.isYoyFluctuate("yoyDown")).isTrue();
        assertThat(EventRulePayloadParser.isYoyFluctuate("valUp")).isFalse();
    }

    @Test
    void parserComparePeriodFallsBackToLookback() {
        Map<String, Object> primary = Map.of("period", 300);
        assertThat(EventRulePayloadParser.extractComparePeriodMillis(primary, 300_000L))
                .isEqualTo(300_000L);
    }

    @Test
    void parserFluctuateFallsBackToValUp() {
        Map<String, Object> primary = Map.of("period", 300);
        assertThat(EventRulePayloadParser.extractFluctuate(primary))
                .isEqualTo(EventRule.FLUCTUATE_VAL_UP);
    }

    @Test
    void parserFluctuateRejectsUnknownValue() {
        Map<String, Object> primary = Map.of("fluctuate", "nonsense");
        assertThat(EventRulePayloadParser.extractFluctuate(primary))
                .isEqualTo(EventRule.FLUCTUATE_VAL_UP);
    }

    @Test
    void lessThanRequestCountTriggersWhenNoDataReturnsZero() {
        RuleMetricEvaluationService metricService = mock(RuleMetricEvaluationService.class);
        when(metricService.evaluateRuleGroups(any(), anyLong()))
                .thenReturn(List.of(new GroupMetricValue("order-service", "order-service", 0.0)));

        String queryJson = "{\"1\":{\"way\":\"threshold\",\"period\":300,\"comparison\":\"<\","
                + "\"thresholds\":{\"critical\":1},"
                + "\"A\":{\"metric\":\"service.cnt\",\"aggs\":\"sum\",\"by\":[],"
                + "\"from\":[{\"left\":\"service\",\"operator\":\"=\",\"right\":\"order-service\"}]}}}";
        // Denormalized column still says gt (legacy save); queryJson.comparison must win.
        EventRule rule = new EventRule(
                2L,
                "order-service no data",
                EventRule.CLASSIFY_SINGLE,
                EventRule.WAY_THRESHOLD,
                "order-service",
                EventRule.METRIC_REQUEST_COUNT,
                1.0,
                EventRule.COMPARATOR_GT,
                true,
                queryJson,
                java.time.Instant.now());

        List<RuleEvaluationResult> results = evaluator(metricService).evaluateAll(rule, 300_000L);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).triggered()).isTrue();
        assertThat(results.get(0).message()).contains("低于阈值");
    }

    @Test
    void lessThanRequestCountDoesNotTriggerWhenAboveThreshold() {
        RuleMetricEvaluationService metricService = mock(RuleMetricEvaluationService.class);
        when(metricService.evaluateRuleGroups(any(), anyLong()))
                .thenReturn(List.of(new GroupMetricValue("order-service", "order-service", 42.0)));

        String queryJson = "{\"1\":{\"way\":\"threshold\",\"period\":300,\"comparison\":\"<\","
                + "\"thresholds\":{\"critical\":1},"
                + "\"A\":{\"metric\":\"service.cnt\",\"from\":[]}}}";
        EventRule rule = new EventRule(
                3L,
                "order-service no data",
                EventRule.CLASSIFY_SINGLE,
                EventRule.WAY_THRESHOLD,
                "order-service",
                EventRule.METRIC_REQUEST_COUNT,
                1.0,
                EventRule.COMPARATOR_LT,
                true,
                queryJson,
                java.time.Instant.now());

        assertThat(evaluator(metricService).evaluateAll(rule, 300_000L)).isEmpty();
    }

    @Test
    void warningThresholdFiresWarningLevelWhenCriticalNotBreached() {
        RuleMetricEvaluationService metricService = mock(RuleMetricEvaluationService.class);
        when(metricService.evaluateRuleGroups(any(), anyLong()))
                .thenReturn(List.of(new GroupMetricValue("checkout", "checkout", 7.0)));

        String queryJson = "{\"1\":{\"way\":\"threshold\",\"period\":300,\"comparison\":\">\","
                + "\"thresholds\":{\"critical\":10,\"warning\":5},"
                + "\"A\":{\"metric\":\"service.error.pct\",\"from\":[]}}}";
        EventRule rule = new EventRule(
                4L,
                "error rate dual band",
                EventRule.CLASSIFY_SINGLE,
                EventRule.WAY_THRESHOLD,
                "checkout",
                EventRule.METRIC_ERROR_RATE,
                10.0,
                EventRule.COMPARATOR_GT,
                true,
                queryJson,
                java.time.Instant.now());

        List<RuleEvaluationResult> results = evaluator(metricService).evaluateAll(rule, 300_000L);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).level()).isEqualTo(EventRule.LEVEL_WARNING);
        assertThat(results.get(0).message()).contains("超过阈值5%");
    }

    @Test
    void criticalThresholdWinsWhenBothBandsBreached() {
        RuleMetricEvaluationService metricService = mock(RuleMetricEvaluationService.class);
        when(metricService.evaluateRuleGroups(any(), anyLong()))
                .thenReturn(List.of(new GroupMetricValue("checkout", "checkout", 15.0)));

        String queryJson = "{\"1\":{\"way\":\"threshold\",\"period\":300,\"comparison\":\">\","
                + "\"thresholds\":{\"critical\":10,\"warning\":5},"
                + "\"A\":{\"metric\":\"service.error.pct\",\"from\":[]}}}";
        EventRule rule = new EventRule(
                5L,
                "error rate dual band",
                EventRule.CLASSIFY_SINGLE,
                EventRule.WAY_THRESHOLD,
                "checkout",
                EventRule.METRIC_ERROR_RATE,
                10.0,
                EventRule.COMPARATOR_GT,
                true,
                queryJson,
                java.time.Instant.now());

        List<RuleEvaluationResult> results = evaluator(metricService).evaluateAll(rule, 300_000L);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).level()).isEqualTo(EventRule.LEVEL_CRITICAL);
        assertThat(results.get(0).message()).contains("超过阈值10%");
    }

    @Test
    void warningOnlyRuleCanTrigger() {
        RuleMetricEvaluationService metricService = mock(RuleMetricEvaluationService.class);
        when(metricService.evaluateRuleGroups(any(), anyLong()))
                .thenReturn(List.of(new GroupMetricValue("checkout", "checkout", 8.0)));

        // No critical in query; denormalized rule.threshold (100) is the critical fallback and
        // won't breach, so only the warning band (5) fires.
        String queryJson = "{\"1\":{\"way\":\"threshold\",\"period\":300,\"comparison\":\">\","
                + "\"thresholds\":{\"warning\":5},"
                + "\"A\":{\"metric\":\"service.error.pct\",\"from\":[]}}}";
        EventRule rule = new EventRule(
                6L,
                "warning only",
                EventRule.CLASSIFY_SINGLE,
                EventRule.WAY_THRESHOLD,
                "checkout",
                EventRule.METRIC_ERROR_RATE,
                100.0,
                EventRule.COMPARATOR_GT,
                true,
                queryJson,
                java.time.Instant.now());

        List<RuleEvaluationResult> results = evaluator(metricService).evaluateAll(rule, 300_000L);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).level()).isEqualTo(EventRule.LEVEL_WARNING);
    }

    @Test
    void catalogThresholdResultCarriesStructuredMetricNumbers() {
        RuleMetricEvaluationService metricService = mock(RuleMetricEvaluationService.class);
        when(metricService.evaluateRuleGroups(any(), anyLong()))
                .thenReturn(List.of(new GroupMetricValue("order-service", "TimeoutException", 8.7)));

        String queryJson = "{\"1\":{\"way\":\"threshold\",\"period\":300,\"comparison\":\">\","
                + "\"thresholds\":{\"critical\":8},"
                + "\"A\":{\"metric\":\"service.error.pct\",\"from\":[]}}}";
        EventRule rule = new EventRule(
                9L,
                "error rate grouped",
                EventRule.CLASSIFY_SINGLE,
                EventRule.WAY_THRESHOLD,
                "order-service",
                EventRule.METRIC_ERROR_RATE,
                8.0,
                EventRule.COMPARATOR_GT,
                true,
                queryJson,
                java.time.Instant.now());

        List<RuleEvaluationResult> results = evaluator(metricService).evaluateAll(rule, 300_000L);

        assertThat(results).hasSize(1);
        RuleEvaluationResult result = results.get(0);
        assertThat(result.metricId()).isEqualTo("service.error.pct");
        assertThat(result.metricLabel()).isEqualTo("错误率");
        assertThat(result.metricUnit()).isEqualTo("%");
        assertThat(result.value()).isEqualTo(8.7);
        assertThat(result.threshold()).isEqualTo(8.0);
        assertThat(result.comparator()).isEqualTo("gt");
    }

    @Test
    void legacyThresholdResultCarriesPercentScaledNumbers() {
        RuleMetricEvaluationService metricService = mock(RuleMetricEvaluationService.class);
        ThresholdEvaluationService thresholdService = mock(ThresholdEvaluationService.class);
        when(thresholdService.currentErrorRate(any(), anyLong())).thenReturn(0.052);
        ThresholdAlarmMessageFormatter formatter =
                new ThresholdAlarmMessageFormatter(TestMetricCoreSupport.catalogWithServiceMetrics());
        SingleMetricRuleEvaluator evaluator = new SingleMetricRuleEvaluator(
                thresholdService, metricService, formatter);
        EventRule rule = new EventRule(
                11L, "legacy error rate", EventRule.CLASSIFY_SINGLE, EventRule.WAY_THRESHOLD,
                "checkout", EventRule.METRIC_ERROR_RATE, 0.05, EventRule.COMPARATOR_GT, true,
                null, java.time.Instant.now());

        List<RuleEvaluationResult> results = evaluator.evaluateAll(rule, 300_000L);

        assertThat(results).hasSize(1);
        RuleEvaluationResult result = results.get(0);
        // legacy error rate is a fraction; payload numbers match the message scale (percent)
        assertThat(result.value()).isEqualTo(5.2);
        assertThat(result.threshold()).isEqualTo(5.0);
        assertThat(result.metricUnit()).isEqualTo("%");
        assertThat(result.comparator()).isEqualTo("gt");
    }

    @Test
    void includingNormalReturnsObservedCatalogGroupWithoutTriggering() {
        RuleMetricEvaluationService metricService = mock(RuleMetricEvaluationService.class);
        when(metricService.evaluateRuleGroups(any(), anyLong()))
                .thenReturn(List.of(new GroupMetricValue("order-service", "TimeoutException", 4.0)));
        String queryJson = "{\"1\":{\"way\":\"threshold\",\"period\":300,\"comparison\":\">\","
                + "\"thresholds\":{\"critical\":8},"
                + "\"A\":{\"metric\":\"service.error.pct\",\"from\":[]}}}";
        EventRule rule = new EventRule(
                12L, "error rate grouped", EventRule.CLASSIFY_SINGLE, EventRule.WAY_THRESHOLD,
                "order-service", EventRule.METRIC_ERROR_RATE, 8.0, EventRule.COMPARATOR_GT,
                true, queryJson, java.time.Instant.now());

        assertThat(evaluator(metricService).evaluateAll(rule, 300_000L)).isEmpty();
        List<RuleEvaluationResult> observed =
                evaluator(metricService).evaluateAllIncludingNormal(rule, 300_000L);

        assertThat(observed).hasSize(1);
        assertThat(observed.get(0).triggered()).isFalse();
        assertThat(observed.get(0).groupKey()).isEqualTo("TimeoutException");
        assertThat(observed.get(0).value()).isEqualTo(4.0);
    }
}
