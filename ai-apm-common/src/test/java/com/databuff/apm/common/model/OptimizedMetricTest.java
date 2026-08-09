package com.databuff.apm.common.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OptimizedMetricTest {

    @Test
    void mergeRejectsDifferentFieldSizes() {
        OptimizedMetric left = new OptimizedMetric().withFieldValues(1);
        OptimizedMetric right = new OptimizedMetric().withFieldValues(1, 2);
        assertThatThrownBy(() -> left.merge(right))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mergeLegacyTraceFieldsSumsSlowAndKeepsMaxDuration() {
        OptimizedMetric left = new OptimizedMetric().withFieldValues(1, 1, 30_000_000L, 30_000_000L, 0);
        OptimizedMetric right = new OptimizedMetric().withFieldValues(1, 0, 600_000_000L, 600_000_000L, 1);
        OptimizedMetric merged = left.merge(right);
        assertThat(merged.fieldValues()).containsExactly(2L, 1L, 630_000_000L, 600_000_000L, 1L);
    }

    @Test
    void mergeLegacyTraceFieldsNormalizesThreeAndFiveFieldShapes() {
        OptimizedMetric left = new OptimizedMetric().withFieldValues(1, 0, 80L);
        OptimizedMetric right = new OptimizedMetric().withFieldValues(1, 1, 120L, 120L, 1);
        OptimizedMetric merged = left.merge(right);
        assertThat(merged.fieldValues()).containsExactly(2L, 1L, 200L, 120L, 1L);
    }

    @Test
    void serviceFlowFiveFieldsMergeAsElementWiseSums() {
        OptimizedMetric left = new OptimizedMetric()
                .withMeasurement("service.flow")
                .withFieldValues(1, 0, 1, 2, 100);
        OptimizedMetric right = new OptimizedMetric()
                .withMeasurement("service.flow")
                .withFieldValues(1, 1, 0, 3, 50);
        OptimizedMetric merged = left.merge(right);
        // (cnt, error, slow, srcCall, sumDuration) — all SUM, no maxDuration semantics.
        assertThat(merged.fieldValues()).containsExactly(2L, 1L, 1L, 5L, 150L);
    }
}
