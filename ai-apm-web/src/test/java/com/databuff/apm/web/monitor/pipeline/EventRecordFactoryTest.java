package com.databuff.apm.web.monitor.pipeline;

import com.databuff.apm.web.monitor.TestMonitorRecordIds;
import com.databuff.apm.web.monitor.eval.RuleEvaluationResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EventRecordFactoryTest {

    @Test
    void carriesStructuredMetricNumbersFromEvaluation() {
        EventRecordFactory factory = new EventRecordFactory(TestMonitorRecordIds.create());
        RuleEvaluationResult result = new RuleEvaluationResult(
                true, "critical", "错误率（TimeoutException）的8.7%值超过阈值8%",
                "threshold", "order-service", "TimeoutException",
                "service.error.pct", "错误率", "%", 8.7, 8.0, "gt");

        EventRecord event = factory.fromEvaluation(
                12L, "订单服务错误率", "order-service", result, false, Instant.now());

        assertThat(event.id()).startsWith("E");
        assertThat(event.status()).isEqualTo(EventRecord.STATUS_TRIGGER);
        assertThat(event.metricId()).isEqualTo("service.error.pct");
        assertThat(event.metricLabel()).isEqualTo("错误率");
        assertThat(event.metricUnit()).isEqualTo("%");
        assertThat(event.value()).isEqualTo(8.7);
        assertThat(event.threshold()).isEqualTo(8.0);
        assertThat(event.comparator()).isEqualTo("gt");
        assertThat(event.hasMetric()).isTrue();
    }

    @Test
    void legacySixFieldResultLeavesMetricBlockEmpty() {
        EventRecordFactory factory = new EventRecordFactory(TestMonitorRecordIds.create());
        RuleEvaluationResult result = new RuleEvaluationResult(
                true, "critical", "m", "threshold", "s", "s");

        EventRecord event = factory.fromEvaluation(
                1L, "r", "s", result, false, Instant.now());

        assertThat(event.metricId()).isNull();
        assertThat(event.value()).isNull();
        assertThat(event.hasMetric()).isFalse();
    }
}
