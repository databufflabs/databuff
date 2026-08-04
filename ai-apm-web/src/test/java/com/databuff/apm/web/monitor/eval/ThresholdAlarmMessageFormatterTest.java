package com.databuff.apm.web.monitor.eval;

import com.databuff.apm.web.TestMetricCoreSupport;
import com.databuff.apm.web.monitor.EventRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ThresholdAlarmMessageFormatterTest {

    private ThresholdAlarmMessageFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new ThresholdAlarmMessageFormatter(TestMetricCoreSupport.catalogWithServiceMetrics());
    }

    @Test
    void formatsLegacyErrorRateThresholdMessage() {
        String message = formatter.legacyErrorRateThresholdMessage("checkout", 0.12, 0.05);

        assertThat(message).isEqualTo("错误率的12%值超过阈值5%");
    }

    @Test
    void formatsCatalogThresholdMessageWithMetricCn() {
        EventRule rule = catalogRule("service.error.pct", 5.0);

        String message = formatter.thresholdMessage(rule, 12.0, 5.0, "checkout", "checkout");

        assertThat(message).isEqualTo("错误率的12%值超过阈值5%");
    }

    @Test
    void formatsCatalogThresholdMessageWithGroupKey() {
        EventRule rule = catalogRule("service.error.pct", 5.0);

        String message = formatter.thresholdMessage(rule, 12.0, 5.0, "NullPointerException", "checkout");

        assertThat(message).isEqualTo("错误率（NullPointerException）的12%值超过阈值5%");
    }

    @Test
    void formatsLessThanThresholdMessage() {
        EventRule rule = catalogRule(
                "service.cnt", 1.0, null, EventRule.COMPARATOR_LT);

        String message = formatter.thresholdMessage(rule, 0.0, 1.0, "order-service", "order-service");

        assertThat(message).isEqualTo("请求次数的0个值低于阈值1个");
    }

    @Test
    void formatsMutationMessage() {
        EventRule rule = catalogRule("service.error.pct", 3.0);

        String message = formatter.mutationMessage(rule, 8.5, 3.0, "checkout", "checkout");

        assertThat(message).isEqualTo("错误率的变化值8.5%超过阈值3%");
    }

    @Test
    void eventAndAlarmShareSameMessageThroughEvaluatorPath() {
        String eventMessage = formatter.thresholdMessage(
                catalogRule("service.avgDuration", 100.0, null), 200.0, 100.0, "checkout", "checkout");

        assertThat(eventMessage).isEqualTo("平均耗时的200毫秒值超过阈值100毫秒");
    }

    private static EventRule catalogRule(String metricId, double threshold) {
        return catalogRule(metricId, threshold, "%", EventRule.COMPARATOR_GT);
    }

    private static EventRule catalogRule(String metricId, double threshold, String viewUnit) {
        return catalogRule(metricId, threshold, viewUnit, EventRule.COMPARATOR_GT);
    }

    private static EventRule catalogRule(
            String metricId, double threshold, String viewUnit, String comparator) {
        String viewUnitJson = viewUnit == null ? "" : ",\"view_unit\":\"" + viewUnit + "\"";
        String queryJson = "{\"1\":{\"way\":\"threshold\",\"period\":300" + viewUnitJson
                + ",\"thresholds\":{\"critical\":" + threshold + "},"
                + "\"A\":{\"metric\":\"" + metricId + "\",\"from\":[]}}}";
        return new EventRule(
                1L,
                "test rule",
                EventRule.CLASSIFY_SINGLE,
                EventRule.WAY_THRESHOLD,
                "checkout",
                EventRule.METRIC_ERROR_RATE,
                threshold,
                comparator,
                true,
                queryJson,
                Instant.now());
    }
}
