package com.databuff.apm.web.ai.tool;

import com.databuff.apm.web.monitor.ThresholdEvaluationService;
import com.databuff.apm.web.TestStorageSupport;

import com.databuff.apm.common.query.ApmQueryModels.TrafficLightPoint;

import com.databuff.apm.common.storage.ApmReadRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApmToolkitTest {

    @Test
    void listsServiceHealth() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryTrafficLight(anyString())).thenReturn(java.util.List.of(
                new TrafficLightPoint("2026-06-01", "demo-order", 2, 10),
                new TrafficLightPoint("2026-06-01", " ", 1, 5),
                new TrafficLightPoint("2026-06-01", "empty", 0, 0)));
        ThresholdEvaluationService evaluation = mock(ThresholdEvaluationService.class);
        ApmToolkit toolkit = new ApmToolkit(reader, evaluation, TestStorageSupport.storage());

        assertThat(toolkit.listServiceHealth(60_000)).hasSize(2)
                .first()
                .matches(item -> item.service().equals("demo-order") && item.errorRate() == 0.2);
    }

    @Test
    void toleratesQueryFailures() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryTrafficLight(anyString())).thenThrow(new RuntimeException("down"));
        when(reader.queryCallSpanCount(anyString())).thenThrow(new RuntimeException("down"));
        ThresholdEvaluationService evaluation = mock(ThresholdEvaluationService.class);
        when(evaluation.currentErrorRate("demo", 60_000)).thenReturn(0.1);
        ApmToolkit toolkit = new ApmToolkit(reader, evaluation, TestStorageSupport.storage());

        assertThat(toolkit.listServiceHealth(60_000)).isEmpty();
        assertThat(toolkit.countRecentSpans(60_000)).isZero();
        assertThat(toolkit.serviceErrorRate("demo", 60_000)).isEqualTo(0.1);
    }

    @Test
    void countRecentSpansUsesCountSql() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        when(reader.queryCallSpanCount(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            assertThat(sql).contains("COUNT(*)");
            assertThat(sql).contains("trace_dc_span");
            assertThat(sql).doesNotContain("LIMIT");
            return 1_234L;
        });
        ApmToolkit toolkit = new ApmToolkit(
                reader, mock(ThresholdEvaluationService.class), TestStorageSupport.storage());

        assertThat(toolkit.countRecentSpans(60_000)).isEqualTo(1_234);
    }
}
