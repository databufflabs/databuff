package com.databuff.apm.common.serde;

import com.databuff.apm.common.model.OptimizedMetric;
import com.databuff.apm.common.storage.DorisJsonRow;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetricDorisJsonRowTest {

    @Test
    void encodesOptimizedMetricLazily() {
        OptimizedMetric metric = new OptimizedMetric()
                .withTimestamp(1_700_000_000_000_000_000L)
                .withMeasurement("service")
                .withTagValues("ok", "checkout", "svc-id", "inst")
                .withFieldValues(1, 0, 99)
                .initTsId();
        MetricDorisJsonRow row = MetricDorisJsonRow.of(metric);

        assertThat(row.estimatedBytes()).isGreaterThan(64L);
        String json = new String(DorisJsonRow.toByteArray(row));
        assertThat(json).contains("\"ts\":1700000000000");
        assertThat(json).contains("\"service\":\"checkout\"");
        assertThat(json).contains("\"cnt\":1");
        // Idempotent for Stream Load retry re-queue.
        assertThat(new String(DorisJsonRow.toByteArray(row))).isEqualTo(json);
    }

    @Test
    void encodesPrebuiltMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ts", 1_700_000_000_000L);
        map.put("service", "demo");
        map.put("thread_count", 7);
        MetricDorisJsonRow row = MetricDorisJsonRow.ofMap(map);

        String json = new String(DorisJsonRow.toByteArray(row));
        assertThat(json).contains("\"thread_count\":7");
        assertThat(json).contains("\"service\":\"demo\"");
        assertThat(row.estimatedBytes()).isGreaterThan(32L);
    }
}
