package com.databuff.apm.web.monitor.eval;

import com.databuff.apm.web.TestMetricCoreSupport;
import com.databuff.apm.web.TestStorageSupport;
import com.databuff.apm.web.monitor.EventRule;
import com.databuff.apm.web.monitor.RuleMetricEvaluationService;
import com.databuff.apm.web.monitor.ThresholdEvaluationService;

import com.databuff.apm.common.query.ApmQueryModels.ErrorRateSnapshot;
import com.databuff.apm.common.storage.ApmReadRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration-style tests that exercise the real {@link RuleMetricEvaluationService} (SQL
 * building + scalar value computation) and {@link SingleMetricRuleEvaluator} mutation path.
 * Only the bottom-most {@link ApmReadRepository} is mocked, returning realistic
 * {@link ErrorRateSnapshot} fixtures — everything above (metric query SQL, error-rate scalar
 * computation, comparePeriod windowing, ratio/threshold logic, message formatting) runs against
 * production code.
 */
class SingleMetricRuleEvaluatorIntegrationTest {

    private static final Pattern TS_UPPER_BOUND = Pattern.compile("`ts` < (\\d+)");

    private static EventRule mutationRule(String fluctuate, double threshold, String comparePeriodSec) {
        String queryJson = "{\"1\":{\"way\":\"mutation\",\"period\":300"
                + (comparePeriodSec == null ? "" : ",\"comparePeriod\":" + comparePeriodSec)
                + ",\"fluctuate\":\"" + fluctuate + "\""
                + ",\"view_unit\":\"%\""
                + ",\"thresholds\":{\"critical\":" + threshold + "}"
                + ",\"A\":{\"metric\":\"service.error.pct\",\"from\":[]}}}";
        return new EventRule(
                1L,
                "mutation integration rule",
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

    private static SingleMetricRuleEvaluator realEvaluator(ApmReadRepository reader) {
        RuleMetricEvaluationService metricService = new RuleMetricEvaluationService(
                reader, TestStorageSupport.storage(), TestMetricCoreSupport.catalogWithServiceMetrics());
        ThresholdEvaluationService thresholdService = new ThresholdEvaluationService(
                reader, TestStorageSupport.storage());
        ThresholdAlarmMessageFormatter formatter =
                new ThresholdAlarmMessageFormatter(TestMetricCoreSupport.catalogWithServiceMetrics());
        return new SingleMetricRuleEvaluator(thresholdService, metricService, formatter);
    }

    private static long extractTsUpperBound(String sql) {
        Matcher matcher = TS_UPPER_BOUND.matcher(sql);
        assertThat(matcher.find()).as("expected `ts` < bound in SQL: %s", sql).isTrue();
        return Long.parseLong(matcher.group(1));
    }

    @Test
    void yoyUpTriggersThroughRealMetricPipeline() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        // current window: 8 errors / 100 total -> 8%; previous window: 5 errors / 100 -> 5%.
        // ratio (8-5)/5 = 0.6 > threshold 0.5 (front-end stores 50% as 0.5 via _scale=0.01).
        when(reader.queryErrorRate(anyString()))
                .thenReturn(new ErrorRateSnapshot(8, 100))
                .thenReturn(new ErrorRateSnapshot(5, 100));

        List<RuleEvaluationResult> results = realEvaluator(reader)
                .evaluateAll(mutationRule("yoyUp", 0.5, "3600"), 300_000L);

        assertThat(results).hasSize(1);
        RuleEvaluationResult result = results.get(0);
        assertThat(result.triggered()).isTrue();
        assertThat(result.detectionWay()).isEqualTo(EventRule.WAY_MUTATION);
        assertThat(result.message()).isEqualTo("错误率的变化值60%超过阈值50%");
    }

    @Test
    void yoyUpDoesNotTriggerWhenErrorRateDecreasedThroughRealPipeline() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        // current 4%, previous 5% -> ratio -0.2, wrong direction -> no alarm.
        when(reader.queryErrorRate(anyString()))
                .thenReturn(new ErrorRateSnapshot(4, 100))
                .thenReturn(new ErrorRateSnapshot(5, 100));

        List<RuleEvaluationResult> results = realEvaluator(reader)
                .evaluateAll(mutationRule("yoyUp", 0.5, "3600"), 300_000L);

        assertThat(results).isEmpty();
    }

    @Test
    void valUpTriggersOnAbsoluteIncreaseThroughRealPipeline() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        // current 8%, previous 5% -> +3 percentage points > threshold 2 (val mode, _scale=1).
        when(reader.queryErrorRate(anyString()))
                .thenReturn(new ErrorRateSnapshot(8, 100))
                .thenReturn(new ErrorRateSnapshot(5, 100));

        List<RuleEvaluationResult> results = realEvaluator(reader)
                .evaluateAll(mutationRule("valUp", 2.0, "3600"), 300_000L);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).message()).isEqualTo("错误率的变化值3%超过阈值2%");
    }

    @Test
    void realPipelineIssuesComparePeriodOffsetSqlWindows() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryErrorRate(anyString()))
                .thenReturn(new ErrorRateSnapshot(8, 100))
                .thenReturn(new ErrorRateSnapshot(5, 100));

        realEvaluator(reader).evaluateAll(mutationRule("yoyUp", 0.5, "3600"), 300_000L);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(reader, times(2)).queryErrorRate(sqlCaptor.capture());
        long currentUpper = extractTsUpperBound(sqlCaptor.getAllValues().get(0));
        long previousUpper = extractTsUpperBound(sqlCaptor.getAllValues().get(1));

        // Both windows are lookback (300s) wide and the previous window ends exactly
        // comparePeriod (3600s) before the current window — proving comparePeriod drives the SQL.
        assertThat(currentUpper - previousUpper).isEqualTo(3_600_000L);
    }

    @Test
    void realPipelineFallbackComparePeriodUsesAdjacentWindow() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryErrorRate(anyString()))
                .thenReturn(new ErrorRateSnapshot(8, 100))
                .thenReturn(new ErrorRateSnapshot(5, 100));

        realEvaluator(reader).evaluateAll(mutationRule("yoyUp", 0.5, null), 300_000L);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(reader, times(2)).queryErrorRate(sqlCaptor.capture());
        long currentUpper = extractTsUpperBound(sqlCaptor.getAllValues().get(0));
        long previousUpper = extractTsUpperBound(sqlCaptor.getAllValues().get(1));

        // No comparePeriod -> previous window immediately precedes current (legacy behavior).
        assertThat(currentUpper - previousUpper).isEqualTo(300_000L);
    }

    @Test
    void yoyWithZeroPreviousDoesNotFireThroughRealPipeline() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        // previous 0/0 -> totalCount 0 -> scalarValue returns 0 -> ratio undefined -> skip.
        when(reader.queryErrorRate(anyString()))
                .thenReturn(new ErrorRateSnapshot(8, 100))
                .thenReturn(new ErrorRateSnapshot(0, 0));

        List<RuleEvaluationResult> results = realEvaluator(reader)
                .evaluateAll(mutationRule("yoyUp", 0.5, "3600"), 300_000L);

        assertThat(results).isEmpty();
    }
}
